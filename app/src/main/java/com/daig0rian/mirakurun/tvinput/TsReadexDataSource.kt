package com.daig0rian.mirakurun.tvinput

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

private const val TAG = "TsReadexDataSource"
private const val TS_PACKET_SIZE = 188
private const val BATCH_PACKETS = 64
private const val BATCH_SIZE = TS_PACKET_SIZE * BATCH_PACKETS

// PID assigned by tsreadex for normalized caption stream (servicefilter.cpp: 0x0130)
private const val CAPTION_PID = 0x0130

// Callback invoked on the caller's thread when a complete caption PES unit is assembled.
// ptsMs: PTS in milliseconds (PTS_90kHz / 90). pesPayload: PES private-data payload
// (starts with data_identifier=0x80, no MPEG-2 PES header).
internal fun interface CaptionPesListener {
    fun onCaptionPes(ptsMs: Long, pesPayload: ByteArray)
}

@UnstableApi
internal class TsReadexDataSource(private val upstream: DataSource) : DataSource {

    private var filterHandle: Long = 0

    private val partialPacket = ByteArray(TS_PACKET_SIZE)
    private var partialLen = 0

    private var outputBytes: ByteArray = EMPTY
    private var outputPos = 0

    private val readBuf = ByteArray(BATCH_SIZE)

    // Caption PES assembly state
    var captionPesListener: CaptionPesListener? = null
    private val captionPesBuf = ByteArray(65536)
    private var captionPesLen = 0
    private var captionPesPts = -1L  // ms, or -1 if unknown

    // Diagnostics
    private var pid0x0111Seen = false
    private var lastPmtVersion = -1
    private var totalBytesOut = 0L

    companion object {
        private val EMPTY = ByteArray(0)
    }

    override fun open(dataSpec: DataSpec): Long {
        filterHandle = TsReadexFilter.create(
            programNumberOrIndex = -1,
            audio1Mode = 1 + 8,
            captionMode = 1,
        )
        Log.d(TAG, "open: filter handle=$filterHandle uri=${dataSpec.uri}")
        partialLen = 0
        outputBytes = EMPTY
        outputPos = 0
        captionPesLen = 0
        captionPesPts = -1L
        pid0x0111Seen = false
        lastPmtVersion = -1
        totalBytesOut = 0L
        return upstream.open(dataSpec)
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        if (outputPos < outputBytes.size) {
            val toCopy = minOf(outputBytes.size - outputPos, length)
            System.arraycopy(outputBytes, outputPos, target, offset, toCopy)
            outputPos += toCopy
            return toCopy
        }

        while (true) {
            val upstreamRead = upstream.read(readBuf, 0, readBuf.size)
            if (upstreamRead == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
            if (upstreamRead <= 0) continue

            val available = partialLen + upstreamRead
            val combined: ByteArray
            if (partialLen > 0) {
                combined = ByteArray(available)
                System.arraycopy(partialPacket, 0, combined, 0, partialLen)
                System.arraycopy(readBuf, 0, combined, partialLen, upstreamRead)
            } else {
                combined = readBuf.copyOf(upstreamRead)
            }

            val fullPackets = available / TS_PACKET_SIZE
            val leftover = available % TS_PACKET_SIZE

            if (fullPackets == 0) {
                System.arraycopy(combined, 0, partialPacket, 0, leftover)
                partialLen = leftover
                continue
            }

            partialLen = leftover
            if (leftover > 0) {
                System.arraycopy(combined, fullPackets * TS_PACKET_SIZE, partialPacket, 0, leftover)
            }

            val processed = TsReadexFilter.processPackets(filterHandle, combined, fullPackets * TS_PACKET_SIZE)
            if (processed.isNotEmpty()) {
                logDiagnostics(processed)
                if (captionPesListener != null) extractCaptionPes(processed)
                outputBytes = processed
                outputPos = 0
                val toCopy = minOf(processed.size, length)
                System.arraycopy(processed, 0, target, offset, toCopy)
                outputPos = toCopy
                return toCopy
            }
            // processed is empty (all packets filtered) — loop again
        }
    }

    // Scans the filtered TS chunk for PID 0x0130 (caption) and assembles PES units.
    // PES dispatch happens when the NEXT payload_unit_start_indicator is seen.
    private fun extractCaptionPes(chunk: ByteArray) {
        var i = 0
        while (i + TS_PACKET_SIZE <= chunk.size) {
            val b0 = chunk[i]
            if (b0 != 0x47.toByte()) { i += TS_PACKET_SIZE; continue }

            val pid = ((chunk[i + 1].toInt() and 0x1F) shl 8) or (chunk[i + 2].toInt() and 0xFF)
            if (pid != CAPTION_PID) { i += TS_PACKET_SIZE; continue }

            val pusi = (chunk[i + 1].toInt() and 0x40) != 0
            val adaptationFieldControl = (chunk[i + 3].toInt() shr 4) and 0x03
            // 0x01=payload only, 0x03=adaptation+payload; skip 0x02 (adaptation only)
            val hasPayload = (adaptationFieldControl and 0x01) != 0
            if (!hasPayload) { i += TS_PACKET_SIZE; continue }

            val adaptationFieldLength = if ((adaptationFieldControl and 0x02) != 0) {
                (chunk[i + 4].toInt() and 0xFF) + 1  // length byte itself included
            } else {
                0
            }
            val payloadStart = i + 4 + adaptationFieldLength
            val payloadEnd = i + TS_PACKET_SIZE
            if (payloadStart >= payloadEnd) { i += TS_PACKET_SIZE; continue }

            if (pusi) {
                // Dispatch the previously accumulated PES (if any)
                dispatchCaptionPes()
                // Start new PES: parse PES header to extract PTS and payload offset
                parsePesStart(chunk, payloadStart, payloadEnd)
            } else {
                // Append payload to current PES accumulation buffer
                if (captionPesLen > 0) {
                    appendToCaptionPes(chunk, payloadStart, payloadEnd)
                }
            }
            i += TS_PACKET_SIZE
        }
    }

    private fun parsePesStart(chunk: ByteArray, payloadStart: Int, payloadEnd: Int) {
        val avail = payloadEnd - payloadStart
        // PES header minimum: 9 bytes (6 fixed + 3 optional header prefix)
        if (avail < 9) return

        // Validate PES start code (0x000001) and stream_id
        if (chunk[payloadStart] != 0x00.toByte() ||
            chunk[payloadStart + 1] != 0x00.toByte() ||
            chunk[payloadStart + 2] != 0x01.toByte()) return

        val flagsByte = chunk[payloadStart + 7].toInt() and 0xFF
        val ptsDtsFlags = (flagsByte shr 6) and 0x03
        val headerDataLength = chunk[payloadStart + 8].toInt() and 0xFF

        val pesPayloadOffset = 9 + headerDataLength
        if (pesPayloadOffset > avail) return

        // Extract PTS if present (ptsDtsFlags == 0b10 or 0b11)
        captionPesPts = if ((ptsDtsFlags and 0x02) != 0 && avail >= 14) {
            val p = payloadStart + 9
            val pts90k = ((chunk[p].toLong() and 0x0E) shl 29) or
                         ((chunk[p + 1].toLong() and 0xFF) shl 22) or
                         ((chunk[p + 2].toLong() and 0xFE) shl 14) or
                         ((chunk[p + 3].toLong() and 0xFF) shl 7) or
                         ((chunk[p + 4].toLong() and 0xFE) ushr 1)
            pts90k / 90L
        } else {
            -1L
        }

        // Copy PES payload (private data starting with 0x80) into accumulation buffer
        val dataStart = payloadStart + pesPayloadOffset
        val dataLen = payloadEnd - dataStart
        if (dataLen > 0 && dataLen <= captionPesBuf.size) {
            System.arraycopy(chunk, dataStart, captionPesBuf, 0, dataLen)
            captionPesLen = dataLen
        }
    }

    private fun appendToCaptionPes(chunk: ByteArray, from: Int, to: Int) {
        val dataLen = to - from
        if (captionPesLen + dataLen > captionPesBuf.size) return  // overflow guard
        System.arraycopy(chunk, from, captionPesBuf, captionPesLen, dataLen)
        captionPesLen += dataLen
    }

    private fun dispatchCaptionPes() {
        if (captionPesLen <= 0 || captionPesPts < 0) {
            captionPesLen = 0
            return
        }
        // Validate: must start with data_identifier=0x80 (caption)
        if (captionPesBuf[0] != 0x80.toByte()) {
            captionPesLen = 0
            return
        }
        val payload = captionPesBuf.copyOf(captionPesLen)
        captionPesListener?.onCaptionPes(captionPesPts, payload)
        captionPesLen = 0
    }

    private fun logDiagnostics(chunk: ByteArray) {
        if (!pid0x0111Seen) {
            var i = 0
            while (i + TS_PACKET_SIZE <= chunk.size) {
                if (chunk[i] == 0x47.toByte()) {
                    val pid = ((chunk[i + 1].toInt() and 0x1F) shl 8) or (chunk[i + 2].toInt() and 0xFF)
                    if (pid == 0x0111) {
                        pid0x0111Seen = true
                        Log.i(TAG, "DIAG: first PID 0x0111 at outputOffset=$totalBytesOut")
                        break
                    }
                }
                i += TS_PACKET_SIZE
            }
        }
        // PMT version tracking (tsreadex writes PMT to PID 0x01F0)
        var i = 0
        while (i + TS_PACKET_SIZE <= chunk.size) {
            if (chunk[i] == 0x47.toByte()) {
                val pid = ((chunk[i + 1].toInt() and 0x1F) shl 8) or (chunk[i + 2].toInt() and 0xFF)
                if (pid == 0x01F0 && (chunk[i + 1].toInt() and 0x40) != 0) {
                    val payloadStart = i + 4
                    if (payloadStart + 7 <= chunk.size) {
                        val pointerField = chunk[payloadStart].toInt() and 0xFF
                        val sectionStart = payloadStart + 1 + pointerField
                        if (sectionStart + 6 <= chunk.size) {
                            val ver = (chunk[sectionStart + 5].toInt() shr 1) and 0x1F
                            if (ver != lastPmtVersion) {
                                Log.i(TAG, "DIAG: PMT version $lastPmtVersion → $ver at outputOffset=$totalBytesOut")
                                lastPmtVersion = ver
                            }
                        }
                    }
                    break
                }
            }
            i += TS_PACKET_SIZE
        }
        totalBytesOut += chunk.size
    }

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (filterHandle != 0L) {
                TsReadexFilter.destroy(filterHandle)
                filterHandle = 0
            }
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        var captionPesListener: CaptionPesListener? = null

        override fun createDataSource(): DataSource =
            TsReadexDataSource(upstreamFactory.createDataSource()).also {
                it.captionPesListener = captionPesListener
            }
    }
}

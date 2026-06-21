package com.daig0rian.mirakurun.tvinput

// JNI bridge for libaribcaption. Same shared library (libtsreadex.so) as TsReadexFilter.
internal object AribCaptionFilter {

    init {
        System.loadLibrary("tsreadex")
    }


    fun create(frameWidth: Int, frameHeight: Int): Long =
        nativeCreate(frameWidth, frameHeight)

    fun setFrameSize(handle: Long, w: Int, h: Int) =
        nativeSetFrameSize(handle, w, h)

    // pesPayload: PES private-data payload (starts with data_identifier=0x80).
    // ptsMs: PTS in milliseconds (PTS_90kHz / 90).
    // Returns true if a caption was decoded.
    fun decode(handle: Long, ptsMs: Long, pesPayload: ByteArray, offset: Int, len: Int): Boolean =
        nativeDecode(handle, ptsMs, pesPayload, offset, len)

    fun render(handle: Long, ptsMs: Long): Array<CaptionImage> =
        nativeRender(handle, ptsMs)

    fun flush(handle: Long) = nativeFlush(handle)

    fun destroy(handle: Long) = nativeDestroy(handle)

    @JvmStatic private external fun nativeCreate(frameWidth: Int, frameHeight: Int): Long
    @JvmStatic private external fun nativeSetFrameSize(handle: Long, w: Int, h: Int)
    @JvmStatic private external fun nativeDecode(handle: Long, ptsMs: Long, pesPayload: ByteArray, offset: Int, len: Int): Boolean
    @JvmStatic private external fun nativeRender(handle: Long, ptsMs: Long): Array<CaptionImage>
    @JvmStatic private external fun nativeFlush(handle: Long)
    @JvmStatic private external fun nativeDestroy(handle: Long)
}

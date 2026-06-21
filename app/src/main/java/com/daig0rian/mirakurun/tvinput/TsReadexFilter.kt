package com.daig0rian.mirakurun.tvinput

internal object TsReadexFilter {

    init {
        System.loadLibrary("tsreadex")
    }

    // programNumberOrIndex: -1 = first service in PAT
    // audio1Mode: 1+8 = ensure audio1 present + split dual-mono into two mono PIDs
    // captionMode: 1 = ensure caption stream present
    fun create(programNumberOrIndex: Int, audio1Mode: Int, captionMode: Int): Long =
        nativeCreate(programNumberOrIndex, audio1Mode, captionMode)

    fun processPackets(handle: Long, input: ByteArray, inputLen: Int): ByteArray =
        nativeProcessPackets(handle, input, inputLen)

    fun destroy(handle: Long) = nativeDestroy(handle)

    @JvmStatic private external fun nativeCreate(programNumberOrIndex: Int, audio1Mode: Int, captionMode: Int): Long
    @JvmStatic private external fun nativeProcessPackets(handle: Long, input: ByteArray, inputLen: Int): ByteArray
    @JvmStatic private external fun nativeDestroy(handle: Long)
}

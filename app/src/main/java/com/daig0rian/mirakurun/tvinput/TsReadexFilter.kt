package com.daig0rian.mirakurun.tvinput

internal object TsReadexFilter {

    init {
        System.loadLibrary("tsreadex")
    }

    fun create(programNumberOrIndex: Int, audio1Mode: Int, audio2Mode: Int, captionMode: Int): Long =
        nativeCreate(programNumberOrIndex, audio1Mode, audio2Mode, captionMode)

    fun processPackets(handle: Long, input: ByteArray, inputLen: Int): ByteArray =
        nativeProcessPackets(handle, input, inputLen)

    fun destroy(handle: Long) = nativeDestroy(handle)

    @JvmStatic private external fun nativeCreate(programNumberOrIndex: Int, audio1Mode: Int, audio2Mode: Int, captionMode: Int): Long
    @JvmStatic private external fun nativeProcessPackets(handle: Long, input: ByteArray, inputLen: Int): ByteArray
    @JvmStatic private external fun nativeDestroy(handle: Long)
}

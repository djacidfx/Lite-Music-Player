package org.nift4.gramophone.hificore

import java.nio.ByteBuffer

class Buffer(bufferSizeFrames: Int, frameSize: Int) {
    private val ptr = nativeCreateBuffer(bufferSizeFrames, frameSize)
    private var released = false

    fun getPtr(): Long {
        if (released) {
            throw IllegalStateException("Streaming was already released")
        }
        return ptr
    }

    fun write(
        buffer: ByteBuffer
    ): Boolean {
        if (!buffer.isDirect) {
            throw IllegalArgumentException("Buffer must be direct")
        }
        val progress = nativeWrite(getPtr(), buffer, buffer.position(),
            buffer.remaining())
        buffer.position(buffer.position() + progress)
        return !buffer.hasRemaining()
    }

    @JvmName("getUnderrunCount")
    fun getUnderrunCount(): UInt {
        return nativeGetUnderrunCount(getPtr()).toUInt()
    }

    fun release() {
        if (released)
            return
        nativeRelease(getPtr())
        released = true
    }

    protected fun finalize() {
        release()
    }

    private external fun nativeCreateBuffer(bufferSizeFrames: Int, frameSize: Int): Long
    private external fun nativeGetUnderrunCount(ptr: Long): Int
    private external fun nativeWrite(ptr: Long, buf: ByteBuffer, position: Int, remaining: Int): Int
    //TODO:private external fun nativeGetWriteCounter(ptr: Long, out: LongArray)
    //TODO:private external fun nativeResetWriteCounter(ptr: Long)
    private external fun nativeRelease(ptr: Long)
}
package org.nift4.gramophone.hificore

import com.jwoolston.libusb.UsbDevice
import com.jwoolston.libusb.UsbInterface

class SoftMixedStreaming private constructor(
    device: UsbDevice, usbInterface: UsbInterface, handle: Long, ptr: Long
) : Streaming(device, usbInterface, handle, ptr, true) {
    private val buffers = arrayListOf<Buffer>()

    companion object {
        private external fun nativeCreateSoftMixer(): Long
        // TODO: impl creation code
    }

    fun addBuffer(buf: Buffer) {
        val ret = nativeAddBuffer(getPtr(), buf.getPtr())
        if (ret == 1) {
            synchronized(buffers) {
                buffers.add(buf)
            }
            return
        }
        if (ret == 0) {
            throw IllegalArgumentException("This buffer was already added")
        }
        if (ret == -1) {
            throw IllegalArgumentException("This buffer is wrong size for this mixer")
        }
        throw IllegalStateException("forgot to handle return code: $ret")
    }

    fun removeBuffer(buf: Buffer): Boolean {
        val ret = nativeRemoveBuffer(getPtr(), buf.getPtr())
        if (ret) {
            synchronized(buffers) {
                buffers.remove(buf)
            }
            return true
        }
        return false
    }

    private external fun nativeAddBuffer(ptr: Long, buf: Long): Int
    private external fun nativeRemoveBuffer(ptr: Long, buf: Long): Boolean
}
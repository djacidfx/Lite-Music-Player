package org.nift4.gramophone.hificore

import com.jwoolston.libusb.UsbDevice
import com.jwoolston.libusb.UsbInterface
import java.nio.ByteBuffer

abstract class BufferedStreaming(
    device: UsbDevice, usbInterface: UsbInterface, handle: Long, ptr: Long
) : Streaming(device, usbInterface, handle, ptr) {
    companion object {
        external fun nativeCreateBuffer(bufferSizeBytes: Int): Long
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

    private external fun nativeWrite(ptr: Long, buf: ByteBuffer, position: Int, remaining: Int): Int
}
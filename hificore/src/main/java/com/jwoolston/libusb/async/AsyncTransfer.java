/*
 * Copyright (C) 2017 Jared Woolston
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jwoolston.libusb.async;

import androidx.annotation.NonNull;

import com.jwoolston.libusb.LibusbError;
import com.jwoolston.libusb.UsbDevice;
import com.jwoolston.libusb.UsbEndpoint;

import java.nio.ByteBuffer;

/**
 * @author Jared Woolston (Jared.Woolston@gmail.com)
 */
public class AsyncTransfer {

    private final int isoSlots;
    protected final UsbDevice device;
    private final long nativeObject;
    private ByteBuffer buffer;
    private TransferCallback callback;

    public AsyncTransfer(@NonNull UsbDevice device, int isoSlots) {
        this.isoSlots = isoSlots;
        this.device = device;
        this.nativeObject = nativeAllocate(isoSlots);
    }

    public boolean isInFlight() {
        return nativeIsInFlight(nativeObject);
    }

    public long getNativeObject() {
        return nativeObject;
    }

    /** Calls {@link UsbDevice#cancelAsyncTransfer(AsyncTransfer)}. */
    public LibusbError cancel() {
        return device.cancelAsyncTransfer(this);
    }

    public void setBuffer(@NonNull ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer should be direct");
        }
        if (isInFlight()) {
            throw new IllegalStateException("Transfer is in flight, can't change buffer anymore");
        }
        this.buffer = buffer;
    }

    public final ByteBuffer getBuffer() {
        if (buffer == null) {
            throw new IllegalStateException("Buffer of transfer not set yet");
        }
        if (isInFlight()) {
            throw new IllegalStateException("Transfer is in flight, can't use buffer anymore");
        }
        return buffer;
    }

    public boolean hasBuffer() {
        return buffer != null;
    }

    public ByteBuffer ensureSize(int size) {
        if (hasBuffer()) {
            ByteBuffer buffer = getBuffer();
            // Avoid allocation churn if similarly sized buffer is available
            if (buffer.capacity() >= size && buffer.capacity() <= 2 * size) {
                buffer.clear();
                buffer.limit(size);
                return buffer;
            }
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        setBuffer(buffer);
        return buffer;
    }

    public void setCallback(@NonNull TransferCallback callback) {
        this.callback = callback;
    }

    // JNI will call this after the transfer already completed.
    public TransferCallback getCallback() {
        return callback;
    }

    /**
     * Convenience function that prepares an empty {@link AsyncTransfer} to be sent out as control
     * transfer. First, sets the transfer type to control transfer, the target endpoint to 0, and
     * the request timeout to the parameter value. Then, writes the control setup packet into the
     * buffer, and finally writes the control data from the byteArr parameter into the buffer.
     * The buffer position is then reset so that the buffer is ready for sending.
     *
     * @param requestType
     * @param request
     * @param value
     * @param index
     * @param byteArr
     * @param offset
     * @param length
     * @param timeout milliseconds or 0 for infinite
     */
    public void fillControlTransfer(int requestType, int request, int value, int index,
                                    byte[] byteArr, int offset, int length, int timeout) {
        ByteBuffer buffer = ensureSize(8 + length);
        fillControlTransferWithSetupIntoCurrentBuffer(requestType, request, value, index, timeout,
                length);
        buffer.put(byteArr, offset, length);
        buffer.reset();
    }

    /**
     * Sets the transfer type to control transfer, the target endpoint to 0, and the request timeout
     * to the parameter value. The buffer is not modified or used.
     *
     * @param timeout milliseconds or 0 for infinite
     */
    public void fillControlTransfer(int timeout) {
        nativeFillControlTransfer(getNativeObject(), timeout);
    }

    /**
     * Convenience function that calls both {@link
     * #writeControlTransferSetupIntoCurrentBuffer(int, int, int, int, int)} and {@link
     * #fillControlTransfer(int)}. After this function returns, write the control data (if any) into
     * the buffer with {@link ByteBuffer#put} or similar, and then call {@link ByteBuffer#reset()}
     * to prepare the buffer for sending.
     *
     * @param requestType
     * @param request
     * @param value
     * @param index
     * @param timeout
     * @param length length of the control data that will be written after this function returned
     */
    public void fillControlTransferWithSetupIntoCurrentBuffer(int requestType, int request,
                                                              int value, int index, int timeout,
                                                              int length) {
        fillControlTransfer(timeout);
        buffer.mark();
        writeControlTransferSetupIntoCurrentBuffer(requestType, request, value, index, length);
        buffer.limit(buffer.position() + length);
    }

    /**
     * Writes 8 bytes into buffer at the current position, advancing the position by 8. The written
     * bytes are the control setup packet, filled with the parameter values.
     *
     * @param requestType
     * @param request
     * @param value
     * @param index
     */
    public void writeControlTransferSetupIntoCurrentBuffer(int requestType, int request, int value,
                                                           int index, int length) {
        ByteBuffer buffer = getBuffer();
        int startPos = buffer.position();
        nativeSetupControlTransfer(buffer, requestType, request, value, index, length, startPos);
        buffer.position(startPos + 8);
    }

    private native void nativeSetupControlTransfer(ByteBuffer buffer,
                                                  int requestType, int request, int value,
                                                  int index, int length, int offset);

    private native void nativeFillControlTransfer(long nativeObject, int timeout);

    /**
     * Sets the transfer type to bulk transfer, and the target endpoint plus the request timeout
     * to the parameter value. The buffer is not modified or used.
     *
     * @param endpoint target endpoint
     * @param timeout milliseconds or 0 for infinite
     */
    public void fillBulkTransfer(UsbEndpoint endpoint, int timeout) {
        nativeFillBulkTransfer(getNativeObject(), endpoint.getAddress(), timeout);
    }

    /**
     * Convenience function that sets the transfer type to bulk transfer and applies the requested
     * target endpoint and timeout parameter values. Then, it writes the passed buffer into the
     * transfer buffer, and resets the buffer position so that the buffer is ready for sending.
     *
     * @param endpoint target endpoint
     * @param buffer
     * @param offset
     * @param length
     * @param timeout milliseconds or 0 for infinite
     */
    public void fillBulkTransfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length, int timeout) {
        fillBulkTransfer(endpoint, timeout);
        ByteBuffer buffer1 = ensureSize(length);
        buffer1.put(buffer, offset, length);
        buffer1.position(0);
    }

    private native void nativeFillBulkTransfer(long nativeObject, int address, int timeout);

    /**
     * Sets the transfer type to bulk stream transfer, and the target endpoint plus the request
     * timeout to the parameter value. The buffer is not modified or used.
     *
     * @param endpoint target endpoint
     * @param timeout milliseconds or 0 for infinite
     * @param streamId
     */
    public void fillBulkStreamTransfer(UsbEndpoint endpoint, int timeout, int streamId) {
        nativeFillBulkStreamTransfer(getNativeObject(), endpoint.getAddress(), timeout, streamId);
    }

    /**
     * Convenience function that sets the transfer type to bulk stream transfer and applies the
     * requested target endpoint and timeout parameter values. Then, it writes the passed buffer
     * into the transfer buffer, and resets the buffer position so that the buffer is ready for
     * sending.
     *
     * @param endpoint target endpoint
     * @param buffer
     * @param offset
     * @param length
     * @param timeout milliseconds or 0 for infinite
     * @param streamId
     */
    public void fillBulkStreamTransfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length, int timeout, int streamId) {
        fillBulkStreamTransfer(endpoint, timeout, streamId);
        ByteBuffer buffer1 = ensureSize(length);
        buffer1.put(buffer, offset, length);
        buffer1.position(0);
    }

    // TODO: bulk stream api only makes sense if also libusb_alloc_streams / free_streams is exposed
    private native void nativeFillBulkStreamTransfer(long nativeObject, int address, int timeout, int streamId);

    /**
     * Sets the transfer type to interrupt transfer, and the target endpoint plus the request
     * timeout to the parameter value. The buffer is not modified or used.
     *
     * @param endpoint target endpoint
     * @param timeout milliseconds or 0 for infinite
     */
    public void fillInterruptTransfer(UsbEndpoint endpoint, int timeout) {
        nativeFillInterruptTransfer(getNativeObject(), endpoint.getAddress(), timeout);
    }

    /**
     * Convenience function that sets the transfer type to interrupt transfer and applies the
     * requested target endpoint and timeout parameter values. Then, it writes the passed buffer
     * into the transfer buffer, and resets the buffer position so that the buffer is ready for
     * sending.
     *
     * @param endpoint target endpoint
     * @param buffer
     * @param offset
     * @param length
     * @param timeout milliseconds or 0 for infinite
     */
    public void fillInterruptTransfer(UsbEndpoint endpoint, byte[] buffer, int offset, int length, int timeout) {
        fillInterruptTransfer(endpoint, timeout);
        ByteBuffer buffer1 = ensureSize(length);
        buffer1.put(buffer, offset, length);
        buffer1.position(0);
    }

    private native void nativeFillInterruptTransfer(long nativeObject, int address, int timeout);

    /**
     * Sets the transfer type to isochronous transfer, and the target endpoint plus the request
     * timeout plus the packet count to the parameter value. The buffer is not modified or used.
     *
     * @param endpoint target endpoint
     * @param timeout milliseconds or 0 for infinite
     * @param numPackets
     */
    public void fillIsochronousTransfer(UsbEndpoint endpoint, int timeout, int numPackets) {
        if (numPackets > isoSlots) {
            throw new IllegalArgumentException("Transfer was allocated with maximum of " + isoSlots
                    + " packets but tried to set packet count to " + numPackets);
        }
        nativeFillIsochronousTransfer(getNativeObject(), endpoint.getAddress(), timeout, numPackets);
    }

    /** Set the packet size of a specific isochronous packet (or -1 for all packets). */
    public void setIsochronousPacketSize(int packetNumber, int size) {
        int ret = nativeSetIsochronousPacket(getNativeObject(), packetNumber, size);
        if (ret < 0) {
            throw new IllegalArgumentException("Transfer is set to " + -ret +
                    " packets but tried to change packet " + packetNumber);
        }
    }

    private native void nativeFillIsochronousTransfer(long nativeObject, int address, int timeout, int numPackets);
    private native int nativeSetIsochronousPacket(long nativeObject, int packetNumber, int size);

    public void setFlags(int flags, int mask) {
        nativeSetFlags(getNativeObject(), flags, mask);
    }
    private native void nativeSetFlags(long nativeObject, int flags, int mask);

    @Override
    protected void finalize() throws Throwable {
        if (isInFlight())
            throw new IllegalStateException("JNI should have had a reference on this in-progress transfer");
        nativeDestroy(nativeObject);
        super.finalize();
    }

    private native long nativeAllocate(int isoSlots);
    private native boolean nativeIsInFlight(long nativeObject);
    private native void nativeDestroy(long nativeObject);
}

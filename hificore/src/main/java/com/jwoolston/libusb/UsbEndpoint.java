/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.jwoolston.libusb;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A class representing an endpoint on a {@link UsbInterface}. Endpoints are the channels for sending and receiving
 * data over USB. Typically bulk endpoints are used for sending non-trivial amounts of data. Interrupt endpoints are
 * used for sending small amounts of data, typically events, separately from the main data streams. The endpoint zero
 * is a special endpoint for control messages sent from the host to device.
 */
public class UsbEndpoint implements Parcelable {

    final int address;
    final int attributes;
    final int maxPacketSize;
    final int interval;
    final byte refresh;
    final byte synchAddress;
    final byte[] extra;

    /**
     * UsbEndpoint should only be instantiated by UsbService implementation
     */
    private UsbEndpoint(int address, int attributes, int maxPacketSize, int interval, byte refresh, byte synchAddress, byte[] extra) {
        this.address = address;
        this.attributes = attributes;
        this.maxPacketSize = maxPacketSize;
        this.interval = interval;
        this.refresh = refresh;
        this.synchAddress = synchAddress;
        this.extra = extra;
    }

    /**
     * Returns the endpoint's address field. The address is a bitfield containing both the endpoint number as well as
     * the data direction of the endpoint. the endpoint number and direction can also be accessed via
     * {@link #getEndpointNumber} and {@link #getDirection}.
     *
     * @return the endpoint's address
     */
    public int getAddress() {
        return address;
    }

    /**
     * Extracts the endpoint's endpoint number from its address
     *
     * @return the endpoint's endpoint number
     */
    public int getEndpointNumber() {
        return address & UsbConstants.USB_ENDPOINT_NUMBER_MASK;
    }

    /**
     * Returns the endpoint's direction. Returns {@link UsbConstants#USB_DIR_OUT} if the direction is host to device,
     * and {@link UsbConstants#USB_DIR_IN} if the direction is device to host.
     *
     * @return the endpoint's direction
     * @see UsbConstants#USB_DIR_IN
     * @see UsbConstants#USB_DIR_OUT
     */
    public int getDirection() {
        return address & UsbConstants.USB_ENDPOINT_DIR_MASK;
    }

    /**
     * Returns the endpoint's attributes field.
     *
     * @return the endpoint's attributes
     */
    public int getAttributes() {
        return attributes;
    }

    /**
     * Returns the endpoint's type. Possible results are:
     * <ul>
     * <li>{@link UsbConstants#USB_ENDPOINT_XFER_CONTROL} (endpoint zero)
     * <li>{@link UsbConstants#USB_ENDPOINT_XFER_ISOC} (isochronous endpoint)
     * <li>{@link UsbConstants#USB_ENDPOINT_XFER_BULK} (bulk endpoint)
     * <li>{@link UsbConstants#USB_ENDPOINT_XFER_INT} (interrupt endpoint)
     * </ul>
     *
     * @return the endpoint's type
     */
    public int getType() {
        return attributes & UsbConstants.USB_ENDPOINT_XFERTYPE_MASK;
    }

    /**
     * Returns the endpoint's maximum packet size.
     *
     * @return the endpoint's maximum packet size
     */
    public int getMaxPacketSize() {
        return maxPacketSize;
    }

    /**
     * Returns the endpoint's interval field.
     *
     * @return the endpoint's interval
     */
    public int getInterval() {
        return interval;
    }

    /** For audio devices only: the rate at which synchronization feedback
     * is provided. */
    public byte getRefresh() {
        return refresh;
    }

    /** For audio devices only: the address of the synch endpoint */
    public byte getSynchAddress() {
        return synchAddress;
    }

    public byte[] getExtra() {
        return extra;
    }

    @NonNull
    @Override
    public String toString() {
        return "UsbEndpoint{" +
                "address=" + address +
                ", attributes=" + attributes +
                ", maxPacketSize=" + maxPacketSize +
                ", interval=" + interval +
                ", refresh=" + refresh +
                ", synchAddress=" + synchAddress +
                ", extra=" + Arrays.toString(extra) +
                '}';
    }

    private static final int INDEX_ADDRESS = 2;
    private static final int INDEX_ATTRIBUTES = 3;
    private static final int INDEX_MAX_PACKET_SIZE = 4;
    private static final int INDEX_INTERVAL = 6;
    private static final int REFRESH_INTERVAL = 7;
    private static final int SYNCHADDRESS_INTERVAL = 8;

    static UsbEndpoint fromNativeObject(@NotNull ByteBuffer nativeObject) {
        final int address = 0xFF & nativeObject.get(INDEX_ADDRESS);
        final int attributes = 0xFF & nativeObject.get(INDEX_ATTRIBUTES);
        final int maxPacketSize = (0xFF & nativeObject.get(INDEX_MAX_PACKET_SIZE))
                | ((0xFF & nativeObject.get(INDEX_MAX_PACKET_SIZE + 1)) << 8);
        final int interval = 0xFF & nativeObject.get(INDEX_INTERVAL);
        final byte refresh = nativeObject.get(REFRESH_INTERVAL);
        final byte synchAddress = nativeObject.get(SYNCHADDRESS_INTERVAL);
        ByteBuffer extraTmp = nativeGetExtra(nativeObject);
        ByteBuffer extra = ByteBuffer.allocate(extraTmp.capacity());
        extra.put(extraTmp);
        return new UsbEndpoint(address, attributes, maxPacketSize, interval, refresh, synchAddress,
                extra.array());
    }

    public static final Parcelable.Creator<UsbEndpoint> CREATOR =
            new Parcelable.Creator<>() {
                public UsbEndpoint createFromParcel(Parcel in) {
                    int address = in.readInt();
                    int attributes = in.readInt();
                    int maxPacketSize = in.readInt();
                    int interval = in.readInt();
                    byte refresh = in.readByte();
                    byte synchAddress = in.readByte();
                    byte[] extra = in.createByteArray();
                    return new UsbEndpoint(address, attributes, maxPacketSize, interval, refresh, synchAddress, extra);
                }

                public UsbEndpoint[] newArray(int size) {
                    return new UsbEndpoint[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(address);
        parcel.writeInt(attributes);
        parcel.writeInt(maxPacketSize);
        parcel.writeInt(interval);
        parcel.writeByte(refresh);
        parcel.writeByte(synchAddress);
        parcel.writeByteArray(extra);
    }

    private static native ByteBuffer nativeGetExtra(@NonNull ByteBuffer nativeObject);
}
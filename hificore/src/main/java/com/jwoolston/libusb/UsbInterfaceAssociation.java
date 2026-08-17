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
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A class representing a USB function, defined as collection of interfaces on a {@link UsbDevice}
 * USB devices can have one or more interface associations, each one providing a different piece of
 * functionality, separate from the other interface associations.
 */
public class UsbInterfaceAssociation implements Parcelable {

    final int    firstInterface;
    final int    interfaceCount;
    final int    interfaceClass;
    final int    subclass;
    final int    protocol;
    final @Nullable String name;

    /**
     * UsbInterface should only be instantiated by UsbManager implementation
     */
    private UsbInterfaceAssociation(int firstInterface, int interfaceCount, int interfaceClass, int subClass, int protocol, @Nullable String name) {
        this.firstInterface = firstInterface;
        this.interfaceCount = interfaceCount;
        this.interfaceClass = interfaceClass;
        subclass = subClass;
        this.protocol = protocol;
        this.name = name;
    }

    /**
     * Returns the first interface ID part of this interface association.
     *
     * @return the interface's ID
     */
    public int getFirstInterface() {
        return firstInterface;
    }

    /**
     * Returns the amount of interfaces part of this interface association.<p>
     *
     * The IDs are continuous to {@link #getFirstInterface()}, so the last interface ID is {@code
     * getFirstInterface() + getInterfaceCount() - 1}.
     *
     * @return the interface count
     */
    public int getInterfaceCount() {
        return interfaceCount;
    }

    /**
     * Returns the interface's name.
     *
     * @return the interface's name, or {@code null} if the property could not be read
     */
    public @Nullable
    String getName() {
        return name;
    }

    /**
     * Returns the interface's class field. Some useful constants for USB classes can be found in {@link UsbConstants}
     *
     * @return the interface's class
     */
    public int getInterfaceClass() {
        return interfaceClass;
    }

    /**
     * Returns the interface's subclass field.
     *
     * @return the interface's subclass
     */
    public int getInterfaceSubclass() {
        return subclass;
    }

    /**
     * Returns the interface's protocol field.
     *
     * @return the interface's protocol
     */
    public int getInterfaceProtocol() {
        return protocol;
    }

    @NonNull
    @Override
    public String toString() {
        return "UsbInterface{" +
                "firstInterface=" + firstInterface +
                ", interfaceCount=" + interfaceCount +
                ", name=" + name +
                ", interfaceClass=" + interfaceClass +
                ", subclass=" + subclass +
                ", protocol=" + protocol +
                '}';
    }

    public static final Creator<UsbInterfaceAssociation> CREATOR =
            new Creator<>() {
                public UsbInterfaceAssociation createFromParcel(Parcel in) {
                    int firstInterface = in.readInt();
                    int interfaceCount = in.readInt();
                    String name = in.readString();
                    int Class = in.readInt();
                    int subClass = in.readInt();
                    int protocol = in.readInt();
                    return new UsbInterfaceAssociation(firstInterface, interfaceCount, Class, subClass, protocol, name);
                }

                public UsbInterfaceAssociation[] newArray(int size) {
                    return new UsbInterfaceAssociation[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(firstInterface);
        parcel.writeInt(interfaceCount);
        parcel.writeString(name);
        parcel.writeInt(interfaceClass);
        parcel.writeInt(subclass);
        parcel.writeInt(protocol);
    }

    private static final int INDEX_FIRST_INTERFACE_ID = 2;
    private static final int INDEX_INTERFACE_COUNT = 3;
    private static final int INDEX_INTERFACE_CLASS = 4;
    private static final int INDEX_INTERFACE_SUBCLASS = 5;
    private static final int INDEX_INTERFACE_PROTOCOL = 6;
    private static final int INDEX_INTERFACE_STRING_INDEX = 7;

    static UsbInterfaceAssociation fromNativeDescriptor(@NotNull UsbDevice device, @NotNull ByteBuffer nativeDescriptor) {
        final int firstInterface = 0xFF & nativeDescriptor.get(INDEX_FIRST_INTERFACE_ID);
        final int interfaceCount = 0xFF & nativeDescriptor.get(INDEX_INTERFACE_COUNT);
        final int interfaceClass = 0xFF & nativeDescriptor.get(INDEX_INTERFACE_CLASS);
        final int subclass = 0xFF & nativeDescriptor.get(INDEX_INTERFACE_SUBCLASS);
        final int protocol = 0xFF & nativeDescriptor.get(INDEX_INTERFACE_PROTOCOL);
        final int stringIndex = 0xFF & nativeDescriptor.get(INDEX_INTERFACE_STRING_INDEX);
        String name = UsbDevice.nativeGetStringDescriptor(device.getNativeObject(), stringIndex);
        return new UsbInterfaceAssociation(firstInterface, interfaceCount,
                interfaceClass, subclass, protocol, name);
    }
}
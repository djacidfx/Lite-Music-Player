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

package com.jwoolston.libusb;

import com.jwoolston.libusb.util.Preconditions;

import org.jetbrains.annotations.NotNull;

/**
 * @author Jared Woolston (Jared.Woolston@gmail.com)
 */
class LibUsbDeviceDescriptor {

    private final long nativeObject;

    private boolean isValid = true;

    @NotNull
    static LibUsbDeviceDescriptor getDeviceDescriptor(@NotNull UsbDevice device) {
        return new LibUsbDeviceDescriptor(nativeGetDeviceDescriptorFromHandle(device.getNativeObject()));
    }

    private LibUsbDeviceDescriptor(long nativeObject) {
        Preconditions.checkArgument(nativeObject != 0, "LibUsbDeviceDescriptor Initialization failed.");
        this.nativeObject = nativeObject;
    }

    long getNativeObject() {
        if (isValid) {
            return nativeObject;
        } else {
            throw new IllegalStateException("Descriptor is no longer valid.");
        }
    }

    public void destroy() {
        nativeDestroy(nativeObject);
        isValid = false;
    }

    @Override
    protected void finalize() throws Throwable {
        if (isValid) {
            destroy();
        }
        super.finalize();
    }

    public int getVendorId() {
        return nativeGetVendorId(getNativeObject());
    }

    public int getProductId() {
        return nativeGetProductId(getNativeObject());
    }

    public int getDeviceClass() {
        return nativeGetDeviceClass(getNativeObject());
    }

    public int getDeviceSubclass() {
        return nativeGetDeviceSubclass(getNativeObject());
    }

    public int getDeviceProtocol() {
        return nativeGetDeviceProtocol(getNativeObject());
    }

    private static native long nativeGetDeviceDescriptorFromHandle(long device);

    private static native void nativeDestroy(long descriptor);

    private static native int nativeGetVendorId(long descriptor);

    private static native int nativeGetProductId(long descriptor);

    private static native int nativeGetDeviceClass(long descriptor);

    private static native int nativeGetDeviceSubclass(long descriptor);

    private static native int nativeGetDeviceProtocol(long descriptor);
}

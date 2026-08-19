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

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.media3.common.util.Log;

import org.jetbrains.annotations.NotNull;
import org.nift4.gramophone.hificore.AdaptiveDynamicRangeCompression;

/**
 * This class allows you to access the state of USB and communicate with USB devices.
 * Currently only host mode is supported in the public API.
 * <p>
 * This class API is based on the Android {@code android.hardware.usb.UsbManager} class.
 *
 * @author Jared Woolston (Jared.Woolston@gmail.com)
 */
public class UsbManager {

    static {
        if (!AdaptiveDynamicRangeCompression.getLibLoaded()) {
            throw new IllegalStateException("can't load usb jni lib");
        }
    }

    private static final String TAG = "UsbManager";
    @GuardedBy("#lock")
    private int refCount;
    @GuardedBy("#lock")
    private long nativeObject;

    final Object lock = new Object();
    private volatile AsyncUSBThread asyncUsbThread;

    private native long nativeInitialize();

    private native void nativeSetLoggingLevel(long nativeContext, int level);

    private native void nativeDestroy(long context);

    private final android.hardware.usb.UsbManager androidUsbManager;

    public UsbManager(@NotNull Context context) {
        UsbDevice.initialize(); // must be called before nativeInitialize because log is set up here
        nativeObject = nativeInitialize();
        setNativeLogLevel(LoggingLevel.WARNING); // logging uses JNI, it's expensive, so be careful
        androidUsbManager = (android.hardware.usb.UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    /** Open a device. If already open, TODO then what?. */
    @NonNull
    public UsbDevice openDevice(@NonNull android.hardware.usb.UsbDevice device) {
        UsbDeviceConnection connection = androidUsbManager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("Failed to open " + device);
        }
        synchronized (lock) {
            if (refCount < 0)
                throw new IllegalStateException("Negative ref count");
            refCount++;
            UsbDevice d = new UsbDevice(this, device, connection);
            // start event handler after opening device as documented in libusb async-io docs
            startAsyncIfNeeded();
            return d;
        }
    }

    public void setNativeLogLevel(@NotNull LoggingLevel level) {
        synchronized (lock) {
            nativeSetLoggingLevel(getNativeObject(), level.ordinal());
        }
    }

    public void destroy() {
        synchronized (lock) {
            if (refCount != 0) {
                throw new IllegalStateException("Can't close UsbManager if some device is still open!");
            }
            if (nativeObject != 0) {
                nativeDestroy(getNativeObject());
                nativeObject = 0;
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        destroy();
        super.finalize();
    }

    public long getNativeObject() {
        synchronized (lock) {
            if (nativeObject == 0) {
                throw new IllegalStateException("This UsbManager was already destroyed");
            }
            return nativeObject;
        }
    }

    @GuardedBy("#lock")
    void onClosingDevice() {
        refCount--;
        if (refCount < 0)
            throw new IllegalStateException("Negative ref count");
        // We may need to shut down the async communication thread
        if (refCount == 0) {
            asyncUsbThread.shutdown();
        }
    }

    @GuardedBy("#lock")
    void onDeviceClosed() {
        try {
            asyncUsbThread.join();
            asyncUsbThread = null;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    void startAsyncIfNeeded() {
        if (asyncUsbThread == null) {
            Log.d(TAG, "Starting async usb thread.");
            asyncUsbThread = new AsyncUSBThread(this);
            asyncUsbThread.start();
        }
    }

    public enum LoggingLevel {
        NONE,
        ERROR,
        WARNING,
        INFO,
        DEBUG
    }
}
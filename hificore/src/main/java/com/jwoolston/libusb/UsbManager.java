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
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.media3.common.util.Log;

import org.jetbrains.annotations.NotNull;
import org.nift4.gramophone.hificore.AdaptiveDynamicRangeCompression;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;

/**
 * This class allows you to access the state of USB and communicate with USB devices.
 * Currently only host mode is supported in the public API.
 * <p>
 * This class API is based on the Android {@code android.hardware.usb.UsbManager} class.
 *
 * @author Jared Woolston (Jared.Woolston@gmail.com)
 */
public class UsbManager implements MessageQueue.OnFileDescriptorEventListener {

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
    @GuardedBy("#transfers")
    private final HashMap<Long, WeakReference<AsyncTransfer>> transfers = new HashMap<>();
    @GuardedBy("#looperFd")
    private final IdentityHashMap<Looper, ParcelFileDescriptor> loopers = new IdentityHashMap<>();

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
        // both of these throw clauses shouldn't be reachable from finalizer. if everything is
        // leaked, GC will close it in the right order at least :)
        synchronized (lock) {
            if (refCount != 0) {
                throw new IllegalStateException("Can't destroy UsbManager if some device is still open!");
            }
            synchronized (transfers) {
                if (!transfers.isEmpty()) {
                    throw new IllegalStateException("Can't destroy UsbManager if some transfer is still not released!");
                }
                if (nativeObject != 0) {
                    nativeDestroy(nativeObject);
                    nativeObject = 0;
                }
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        destroy();
        super.finalize();
    }

    @GuardedBy("#lock") // except AsyncUSBThread :)
    public long getNativeObject() {
        if (nativeObject == 0) {
            throw new IllegalStateException("This UsbManager was already destroyed");
        }
        return nativeObject;
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

    ParcelFileDescriptor getWriteFdForLooper(Looper l) {
        synchronized (loopers) {
            ParcelFileDescriptor pfd = loopers.get(l);
            if (pfd == null) {
                pfd = ParcelFileDescriptor.adoptFd(nativeEventfd(false));
                l.getQueue().addOnFileDescriptorEventListener(pfd.getFileDescriptor(), EVENT_INPUT,
                        this);
                loopers.put(l, pfd);
            }
            try {
                return pfd.dup();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Warning: after calling this, events destined for this looper will be silently dropped!
     * This is intended to be used when stopping a looper.
     */
    public void disableUsbEventsForLooper(Looper l) {
        synchronized (loopers) {
            try (ParcelFileDescriptor pfd = loopers.remove(l)) {
                if (pfd == null) {
                    return;
                }
                l.getQueue().removeOnFileDescriptorEventListener(pfd.getFileDescriptor());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public int onFileDescriptorEvents(@NonNull FileDescriptor fd, int events) {
        Looper l = Looper.myLooper();
        synchronized (loopers) {
            ParcelFileDescriptor pfd = loopers.get(l);
            if (pfd == null) {
                return 0;
            }
            readEventfd(pfd.getFd());
        }
        ArrayList<AsyncTransfer> toCallback = new ArrayList<>();
        synchronized (transfers) {
            for (WeakReference<AsyncTransfer> ref : transfers.values()) {
                AsyncTransfer transfer = ref.get();
                if (transfer != null && transfer.callbackLooper == l &&
                        transfer.readyForCallback()) {
                    toCallback.add(transfer);
                }
            }
        }
        for (AsyncTransfer transfer : toCallback) {
            transfer.callbackOnLooper();
        }
        return EVENT_INPUT;
    }

    void onTransferAdded(AsyncTransfer transfer) {
        synchronized (transfers) {
            transfers.put(transfer.getNativeObject(), new WeakReference<>(transfer));
        }
    }

    void onTransferReleased(AsyncTransfer transfer) {
        synchronized (transfers) {
            transfers.remove(transfer.getNativeObject());
        }
    }

    native int nativeEventfd(boolean block);
    native void readEventfd(int fd);

    public enum LoggingLevel {
        NONE,
        ERROR,
        WARNING,
        INFO,
        DEBUG
    }
}
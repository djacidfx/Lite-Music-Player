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
import android.util.Pair;

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
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

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
    @GuardedBy("#loopers")
    private final IdentityHashMap<Pair<ParcelFileDescriptor, ReentrantLock>, Looper> deadLooperReaper = new IdentityHashMap<>();
    @GuardedBy("#loopers")
    private final IdentityHashMap<Looper, Pair<Pair<ParcelFileDescriptor, ReentrantLock>, Integer>> loopers = new IdentityHashMap<>();

    final Object lock = new Object();
    volatile AsyncUSBThread asyncUsbThread;

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

    /**
     * Open a device. If already open, TODO then what?.
     *
     * @param requireRealTime If true, disallows the event handling thread to be blocked for a long
     *                        time by enforcing every transfer has a callback looper set.
     */
    @NonNull
    public UsbDevice openDevice(@NonNull android.hardware.usb.UsbDevice device, boolean requireRealTime) {
        UsbDeviceConnection connection = androidUsbManager.openDevice(device);
        if (connection == null) {
            throw new IllegalStateException("Failed to open " + device);
        }
        synchronized (lock) {
            if (refCount < 0)
                throw new IllegalStateException("Negative ref count");
            refCount++;
            UsbDevice d = new UsbDevice(this, device, connection, requireRealTime);
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
            synchronized (loopers) {
                synchronized (transfers) {
                    if (!transfers.isEmpty()) {
                        throw new IllegalStateException("Can't destroy UsbManager if some transfer is still not released!");
                    }
                    for (Map.Entry<Looper, Pair<Pair<ParcelFileDescriptor, ReentrantLock>, Integer>> l : loopers.entrySet()) {
                        l.getKey().getQueue().removeOnFileDescriptorEventListener(
                                l.getValue().first.first.getFileDescriptor());
                        try {
                            l.getValue().first.first.close();
                        } catch (IOException e) {
                            Log.e(TAG, "failed to close eventfd", e);
                        }
                    }
                    if (nativeObject != 0) {
                        nativeDestroy(nativeObject);
                        nativeObject = 0;
                    }
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
            Pair<Pair<ParcelFileDescriptor, ReentrantLock>, Integer> pfd = loopers.get(l);
            if (pfd == null) {
                throw new IllegalArgumentException("Please manually call both " +
                        "enableUsbEventsForLooper and disableUsbEventsForLooper");
            }
            try {
                return pfd.first.first.dup();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Must be called before submitting a transfer with {@link
     * AsyncTransfer#setCallbackLooper(Looper)} set to {@param l}. Don't forget to call {@link
     * UsbManager#disableUsbEventsForLooper(Looper, boolean)} later to prevent memory leaks. This
     * function is thread safe and can be called from any thread.<p>
     *
     * This function uses reference counting, so for every call to enable, there should be one call
     * to disable.
     */
    public void enableUsbEventsForLooper(Looper l) {
        if (!l.getThread().isAlive()) {
            throw new IllegalArgumentException("The looper is dead");
        }
        synchronized (loopers) {
            Pair<Pair<ParcelFileDescriptor, ReentrantLock>, Integer> pfd = loopers.get(l);
            if (pfd != null) {
                loopers.put(l, new Pair<>(pfd.first, pfd.second + 1));
                return;
            }
            pfd = new Pair<>(new Pair<>(ParcelFileDescriptor.adoptFd(nativeEventfd(false)),
                    new ReentrantLock()), 1);
            try {
                l.getQueue().addOnFileDescriptorEventListener(pfd.first.first.getFileDescriptor(),
                        EVENT_INPUT, this);
                loopers.put(l, pfd);
            } catch (Throwable t) {
                try {
                    pfd.first.first.close();
                } catch (Throwable e) {
                    t.addSuppressed(e);
                }
                throw t;
            }
        }
    }

    /**
     * Must be called before or after stopping a looper to prevent memory leaks. This function is
     * thread safe and can be called from any thread.<p>
     *
     * This function uses reference counting, so for every call to enable, there should be one call
     * to disable. (Except if {@param force} is passed, then events will always be disabled)<p>
     *
     * Warning: if there is any still-running transfer destined to go to this looper, its callback
     * WON'T be called after this function returns, and it will be automatically garbage-collected.
     * (This only happens if the reference count reached zero, so an application should likewise be
     * prepared to continue to get callbacks for old events and discard them manually.)
     */
    public void disableUsbEventsForLooper(Looper l, boolean force) {
        Pair<Pair<ParcelFileDescriptor, ReentrantLock>, Integer> pfd;
        boolean handToMainThread = false;
        synchronized (loopers) {
            pfd = loopers.remove(l);
            if (pfd == null) {
                return;
            }
            if (pfd.second > 1 && !force) {
                loopers.put(l, new Pair<>(pfd.first, pfd.second - 1));
                return;
            }
            l.getQueue().removeOnFileDescriptorEventListener(pfd.first.first.getFileDescriptor());
            pfd.first.second.lock();
            try {
                // at this point the other thread is not handling transfers anymore and the
                // transfers' in-flight state stays the same
                synchronized (transfers) {
                    for (WeakReference<AsyncTransfer> ref : transfers.values()) {
                        AsyncTransfer transfer = ref.get();
                        if (transfer != null && transfer.callbackLooper == l && transfer.isInFlight()) {
                            transfer.callback = null;
                            handToMainThread = true;
                        }
                    }
                    if (handToMainThread) {
                        deadLooperReaper.put(pfd.first, l);
                        Looper.getMainLooper().getQueue().addOnFileDescriptorEventListener(
                                pfd.first.first.getFileDescriptor(), EVENT_INPUT, this);
                    }
                }
            } finally {
                pfd.first.second.unlock();
            }
        }
        if (!handToMainThread) {
            try {
                pfd.first.first.close();
            } catch (IOException e) {
                Log.e(TAG, "failed to close eventfd", e);
            }
        }
    }

    @Override
    public int onFileDescriptorEvents(@NonNull FileDescriptor fd, int events) {
        Looper l = Looper.myLooper();
        boolean unregister = false;
        Pair<ParcelFileDescriptor, ReentrantLock> pfd;
        synchronized (loopers) {
            Pair<Pair<ParcelFileDescriptor, ReentrantLock>, Integer> tmp = loopers.get(l);
            pfd = tmp != null ? tmp.first : null;
            if ((pfd == null || pfd.first.getFileDescriptor() != fd) && l == Looper.getMainLooper()) {
                for (Map.Entry<Pair<ParcelFileDescriptor, ReentrantLock>, Looper> candidate : deadLooperReaper.entrySet()) {
                    if (candidate.getKey().first.getFileDescriptor() == fd) {
                        pfd = candidate.getKey();
                        l = candidate.getValue();
                        unregister = true;
                    }
                }
            }
            if (pfd == null) {
                return 0;
            }
            pfd.second.lock();
        }
        try {
            readEventfd(pfd.first.getFd());
            ArrayList<AsyncTransfer> toCallback = new ArrayList<>();
            synchronized (transfers) {
                for (WeakReference<AsyncTransfer> ref : transfers.values()) {
                    AsyncTransfer transfer = ref.get();
                    if (transfer != null && transfer.callbackLooper == l && transfer.isInFlight()) {
                        if (transfer.readyForCallback()) {
                            toCallback.add(transfer);
                        } else {
                            unregister = false;
                        }
                    }
                }
            }
            for (AsyncTransfer transfer : toCallback) {
                transfer.callbackOnLooper();
            }
            if (unregister) {
                synchronized (loopers) {
                    deadLooperReaper.remove(pfd);
                }
                try {
                    pfd.first.close();
                } catch (IOException e) {
                    Log.e(TAG, "failed to close eventfd", e);
                }
                return 0;
            }
            return EVENT_INPUT;
        } finally {
            pfd.second.unlock();
        }
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
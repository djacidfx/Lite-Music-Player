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

import org.jetbrains.annotations.NotNull;

/**
 * @author Jared Woolston (Jared.Woolston@gmail.com)
 */
public class AsyncUSBThread extends Thread {

    private static final String THREAD_NAME = "Async USB Handler";

    @NotNull
    private final UsbManager context;

    AsyncUSBThread(@NotNull UsbManager context) {
        super(THREAD_NAME);
        setPriority(9);
        this.context = context;
    }

    void shutdown() {
        nativeShutdown();
    }

    @Override
    public void run() {
        nativeHandleEvents(context.getNativeObject());
    }

    private static native void nativeShutdown();
    private static native void nativeHandleEvents(long context);
}

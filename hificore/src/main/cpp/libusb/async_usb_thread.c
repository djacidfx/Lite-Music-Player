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
//
// Created by Jared Woolston (Jared.Woolston@gmail.com)
//

#include "common.h"

#define  LOG_TAG    "AsyncUsbThread-Native"

int completed;

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncUSBThread_nativeHandleEvents(JNIEnv *env, jclass type, jlong context) {
    struct libusb_context *ctx = (libusb_context *) context;
    while (!completed) {
        libusb_handle_events_completed(ctx, &completed);
    }
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncUSBThread_nativeShutdown(JNIEnv *env, jclass clazz) {
    completed = 1;
}
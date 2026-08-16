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

#define  LOG_TAG    "UsbManager-Native"

JNIEXPORT jlong JNICALL
Java_com_jwoolston_libusb_UsbManager_nativeInitialize(JNIEnv *env, jobject instance) {
    initializeUacLog(env);
    LOGD("Initializing libusb.");
    struct libusb_context *ctx;
    const struct libusb_init_option options[1] = {
        {LIBUSB_OPTION_NO_DEVICE_DISCOVERY}
    };
    int r = libusb_init_context(&ctx, &options[0], 1);
    if (r < 0) {
        LOGE("Initialization returned: %i", r);
        return 0;
    } else {
        return (jlong)ctx;
    }
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_UsbManager_nativeSetLoggingLevel(JNIEnv *env, jobject instance,
                                                           jlong nativeContext, jint level) {
    struct libusb_context *ctx = (libusb_context *) nativeContext;
    libusb_set_option(ctx, LIBUSB_OPTION_LOG_LEVEL, level);
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_UsbManager_nativeDestroy(JNIEnv *env, jobject instance,
                                                   jlong context) {
    LOGD("De-initializing libusb.");
    struct libusb_context *ctx = (libusb_context *) context;
    libusb_exit(ctx);
}


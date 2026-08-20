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

#ifndef ANDROID_LIBUSB_WRAPPER_COMMON_H
#define ANDROID_LIBUSB_WRAPPER_COMMON_H

#include <jni.h>
#include <libusb.h>
#include <stdatomic.h>

#include "logging.h"

struct transfer_callback_holder {
    JavaVM *vm;
    jobject buffer;
    jobject transfer;
    int fd;
    atomic_int ready;
};
void LIBUSB_CALL libusb_transfer_callback(struct libusb_transfer *transfer);

#endif //ANDROID_LIBUSB_WRAPPER_COMMON_H

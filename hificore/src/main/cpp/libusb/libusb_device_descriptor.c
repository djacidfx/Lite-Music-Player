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

#include <stdlib.h>
#include "common.h"

JNIEXPORT jlong JNICALL
Java_com_jwoolston_libusb_LibUsbDeviceDescriptor_nativeGetDeviceDescriptorFromHandle(JNIEnv *env, jclass type,
                                                                                     jlong handle) {
    struct libusb_device_handle *deviceHandle = (struct libusb_device_handle *) handle;

    struct libusb_device *devicePtr = libusb_get_device(deviceHandle);

    struct libusb_device_descriptor* descriptor = malloc(sizeof(struct libusb_device_descriptor));
    libusb_get_device_descriptor(devicePtr, descriptor);
    return (jlong)descriptor;
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_LibUsbDeviceDescriptor_nativeDestroy(JNIEnv *env, jclass type, jlong descriptor) {
    struct libusb_device_descriptor *deviceDescriptor = (struct libusb_device_descriptor *) descriptor;
    free(deviceDescriptor);
}

JNIEXPORT jint JNICALL
Java_com_jwoolston_libusb_LibUsbDeviceDescriptor_nativeGetVendorId(JNIEnv *env, jclass type, jlong descriptor) {
    struct libusb_device_descriptor *deviceDescriptor = (struct libusb_device_descriptor *) descriptor;
    return deviceDescriptor->idVendor;
}

JNIEXPORT jint JNICALL
Java_com_jwoolston_libusb_LibUsbDeviceDescriptor_nativeGetProductId(JNIEnv *env, jclass type, jlong descriptor) {
    struct libusb_device_descriptor *deviceDescriptor = (struct libusb_device_descriptor *) descriptor;
    return deviceDescriptor->idProduct;
}

JNIEXPORT jint JNICALL
Java_com_jwoolston_libusb_LibUsbDeviceDescriptor_nativeGetDeviceClass(JNIEnv *env, jclass type, jlong descriptor) {
    struct libusb_device_descriptor *deviceDescriptor = (struct libusb_device_descriptor *) descriptor;
    return deviceDescriptor->bDeviceClass;
}

JNIEXPORT jint JNICALL
Java_com_jwoolston_libusb_LibUsbDeviceDescriptor_nativeGetDeviceSubclass(JNIEnv *env, jclass type, jlong descriptor) {
    struct libusb_device_descriptor *deviceDescriptor = (struct libusb_device_descriptor *) descriptor;
    return deviceDescriptor->bDeviceSubClass;
}

JNIEXPORT jint JNICALL
Java_com_jwoolston_libusb_LibUsbDeviceDescriptor_nativeGetDeviceProtocol(JNIEnv *env, jclass type, jlong descriptor) {
    struct libusb_device_descriptor *deviceDescriptor = (struct libusb_device_descriptor *) descriptor;
    return deviceDescriptor->bDeviceProtocol;
}
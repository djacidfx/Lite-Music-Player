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

#include <string.h>
#include "common.h"

#define  LOG_TAG    "UsbConfInterface-Native"

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbInterface_nativeGetInterfaceDescriptor(JNIEnv *env, jclass type,
                                                                    jobject nativeObject, jint index) {
    struct libusb_interface *interface = (struct libusb_interface *) (*env)->GetDirectBufferAddress(env, nativeObject);
    if (index >= interface->num_altsetting) {
        return NULL;
    }

    return ((*env)->NewDirectByteBuffer(env, (void *) (interface->altsetting + index), sizeof(struct
            libusb_interface_descriptor)));
}

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbInterface_nativeGetEndpoint(JNIEnv *env, jclass type, jobject nativeDescriptor,
                                                         jint index) {
    struct libusb_interface_descriptor *descriptor = (struct libusb_interface_descriptor *)
            (*env)->GetDirectBufferAddress(env, nativeDescriptor);
    if (index >= descriptor->bNumEndpoints) {
        return NULL;
    }

    return ((*env)->NewDirectByteBuffer(env, (void *) (descriptor->endpoint + index), sizeof(struct
            libusb_endpoint_descriptor)));
}

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbInterface_nativeGetExtra(JNIEnv *env, jclass clazz,
                                                      jobject native_descriptor) {
    struct libusb_interface_descriptor *descriptor = (struct libusb_interface_descriptor *)
            (*env)->GetDirectBufferAddress(env, native_descriptor);

    return ((*env)->NewDirectByteBuffer(env, (void *) (descriptor->extra), descriptor->extra_length));
}

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbEndpoint_nativeGetExtra(JNIEnv *env, jclass clazz,
                                                     jobject native_object) {
    struct libusb_endpoint_descriptor *descriptor = (struct libusb_endpoint_descriptor *)
            (*env)->GetDirectBufferAddress(env, native_object);

    return ((*env)->NewDirectByteBuffer(env, (void *) (descriptor->extra), descriptor->extra_length));
}
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

#define  LOG_TAG    "UsbConfiguration-Native"

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbConfiguration_nativeGetConfiguration(JNIEnv *env, jclass type, jlong device,
                                                                  jint configuration) {
    struct libusb_device_handle *deviceHandle = (struct libusb_device_handle *) device;
    struct libusb_config_descriptor *config;
    int retval = libusb_get_config_descriptor(libusb_get_device(deviceHandle), (uint8_t) (0xFF & configuration), &config);
    if (retval) {
        LOGE("Error fetching configuration descriptor: %s", libusb_strerror(retval));
        return NULL;
    }

    return ((*env)->NewDirectByteBuffer(env, (void *) config, sizeof(struct libusb_config_descriptor)));
}

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbConfiguration_nativeGetInterface(JNIEnv *env, jclass type, jobject nativeObject,
                                                              jint interfaceIndex) {
    struct libusb_config_descriptor *config = (struct libusb_config_descriptor *)
            (*env)->GetDirectBufferAddress(env, nativeObject);

    return ((*env)->NewDirectByteBuffer(env, (void *) (config->interface + interfaceIndex), sizeof(struct
            libusb_interface)));
}

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbConfiguration_nativeGetInterfaceAssociationArray(JNIEnv *env, jclass type, jlong device,
                                                                         jint configuration) {
    struct libusb_device_handle *deviceHandle = (struct libusb_device_handle *) device;

    struct libusb_interface_association_descriptor_array* out = NULL;
    int ret = libusb_get_interface_association_descriptors(libusb_get_device(deviceHandle),
                                                 configuration, &out);
    if (ret != LIBUSB_SUCCESS) {
        LOGE("Failed to get IAD: %d", ret);
        return NULL;
    }

    return ((*env)->NewDirectByteBuffer(env, (void *) (out), out->length));
}

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbConfiguration_nativeGetInterfaceAssociation(JNIEnv *env, jclass type, jobject array,
                                                                              jint index) {
    struct libusb_interface_association_descriptor_array* arrayPtr =
            (*env)->GetDirectBufferAddress(env, array);

    return ((*env)->NewDirectByteBuffer(env, (void *) (&arrayPtr->iad[index]),
                                        sizeof(struct libusb_interface_association_descriptor)));
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_UsbConfiguration_nativeDestroyInterfaceAssociationArray(JNIEnv *env, jclass type, jobject array) {
    struct libusb_interface_association_descriptor_array* arrayPtr =
            (*env)->GetDirectBufferAddress(env, array);
    libusb_free_interface_association_descriptors(arrayPtr);
}

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_UsbConfiguration_nativeGetExtra(JNIEnv *env, jclass type, jobject nativeObject) {
    struct libusb_config_descriptor *config = (struct libusb_config_descriptor *)
            (*env)->GetDirectBufferAddress(env, nativeObject);

    return ((*env)->NewDirectByteBuffer(env, (void *) (config->extra), config->extra_length));
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_UsbConfiguration_nativeDestroy(JNIEnv *env, jclass type, jobject nativeObject) {
    struct libusb_config_descriptor *config = (struct libusb_config_descriptor *)
            (*env)->GetDirectBufferAddress(env, nativeObject);
    libusb_free_config_descriptor(config);
}

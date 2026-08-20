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

#include <malloc.h>
#include "common.h"

#define  LOG_TAG    "AsyncTransfer-Native"

JNIEXPORT jlong JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeAllocate(JNIEnv *env, jobject thiz,
                                                             jint iso_slots) {
    return (jlong)libusb_alloc_transfer(iso_slots);
}

JNIEXPORT jobject JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeGetIsoBuffer(JNIEnv *env, jobject thiz,
                                                                 jlong nativeObject, jint iso_slots) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) nativeObject;
    return (*env)->NewDirectByteBuffer(env, (void*)(&transfer->iso_packet_desc[0]),
                                       sizeof(struct libusb_iso_packet_descriptor)*iso_slots);
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeDestroy(JNIEnv *env, jobject instance,
                                                                    jlong nativeObject) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) nativeObject;
    struct transfer_callback_holder *holder = (struct transfer_callback_holder *) transfer->user_data;
    if (holder) {
        free(holder);
    }
    libusb_free_transfer(transfer);
}

JNIEXPORT jboolean JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeIsInFlight(JNIEnv *env, jobject thiz,
                                                               jlong native_object) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    struct transfer_callback_holder *holder = (struct transfer_callback_holder *) transfer->user_data;
    return holder != NULL && holder->ready != 0;
}

JNIEXPORT jboolean JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeReadyForCallback(JNIEnv *env, jobject thiz,
                                                         jlong native_object) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    struct transfer_callback_holder *holder = (struct transfer_callback_holder *) transfer->user_data;
    return holder != NULL && holder->ready == 2;
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeFly(JNIEnv *env, jobject thiz,
                                                               jlong native_object) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    struct transfer_callback_holder *holder = transfer->user_data;
    if (!holder) {
        transfer->user_data = holder = malloc(sizeof(struct transfer_callback_holder));
    }
    holder->ready = 1;
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeFillControlTransfer(JNIEnv *env,
                                                                        jobject thiz,
                                                                        jlong native_object,
                                                                        jint timeout) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    transfer->endpoint = 0;
    transfer->type = LIBUSB_TRANSFER_TYPE_CONTROL;
    transfer->timeout = timeout;
    transfer->num_iso_packets = 0;
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeSetupControlTransfer(JNIEnv *env, jobject thiz,
                                                                         jobject buffer,
                                                                         jint request_type,
                                                                         jint request, jint value,
                                                                         jint index, jint length,
                                                                         jint offset) {
    void* userData = (*env)->GetDirectBufferAddress(env, buffer) + offset;
    libusb_fill_control_setup(userData, (uint8_t) (0xFF & request_type),
                              (uint8_t) (0xFF & request),
                              (uint16_t) (0xFFFF & value),
                              (uint16_t) (0xFFFF & index),
                              (uint16_t) (0xFFFF & length));
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeFillBulkTransfer(JNIEnv *env, jobject thiz,
                                                                     jlong native_object,
                                                                     jint address, jint timeout) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    transfer->endpoint = address;
    transfer->type = LIBUSB_TRANSFER_TYPE_BULK;
    transfer->timeout = timeout;
    transfer->num_iso_packets = 0;
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeFillBulkStreamTransfer(JNIEnv *env, jobject thiz,
                                                                     jlong native_object,
                                                                     jint address, jint timeout,
                                                                     jint stream_id) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    transfer->endpoint = address;
    transfer->type = LIBUSB_TRANSFER_TYPE_BULK_STREAM;
    transfer->timeout = timeout;
    transfer->num_iso_packets = 0;
    libusb_transfer_set_stream_id(transfer, stream_id);
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeFillInterruptTransfer(JNIEnv *env, jobject thiz,
                                                                          jlong native_object,
                                                                          jint address,
                                                                          jint timeout) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    transfer->endpoint = address;
    transfer->type = LIBUSB_TRANSFER_TYPE_INTERRUPT;
    transfer->timeout = timeout;
    transfer->num_iso_packets = 0;
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeFillIsochronousTransfer(JNIEnv *env,
                                                                            jobject thiz,
                                                                            jlong native_object,
                                                                            jint address,
                                                                            jint timeout,
                                                                            jint num_packets) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    transfer->endpoint = address;
    transfer->type = LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
    transfer->timeout = timeout;
    transfer->num_iso_packets = num_packets;
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeSetFlags(JNIEnv *env, jobject thiz,
                                                             jlong native_object, jint flags,
                                                             jint mask) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    transfer->flags = (transfer->flags & ~mask) | (flags & mask);
}

JNIEXPORT void JNICALL
Java_com_jwoolston_libusb_AsyncTransfer_nativeCallback(JNIEnv *env, jobject thiz,
                                                       jlong native_object) {
    struct libusb_transfer *transfer = (struct libusb_transfer *) native_object;
    libusb_transfer_callback(transfer);
}
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

import androidx.annotation.NonNull;

import org.jetbrains.annotations.NotNull;

/**
 * Error codes. Most libusb functions return 0 on success or one of these codes on failure.
 *
 * @author Jared Woolston (Jared.Woolston@gmail.com)
 */
public enum LibusbError {

    /** Success (no error) */
    LIBUSB_SUCCESS(0),

    /** Input/output error */
    LIBUSB_ERROR_IO(-1),

    /** Invalid parameter */
    LIBUSB_ERROR_INVALID_PARAM(-2),

    /** Access denied (insufficient permissions) */
    LIBUSB_ERROR_ACCESS(-3),

    /** No such device (it may have been disconnected) */
    LIBUSB_ERROR_NO_DEVICE(-4),

    /** Entity not found */
    LIBUSB_ERROR_NOT_FOUND(-5),

    /** Resource busy */
    LIBUSB_ERROR_BUSY(-6),

    /** Operation timed out */
    LIBUSB_ERROR_TIMEOUT(-7),

    /** Overflow */
    LIBUSB_ERROR_OVERFLOW(-8),

    /** Pipe error */
    LIBUSB_ERROR_PIPE(-9),

    /** System call interrupted (perhaps due to signal) */
    LIBUSB_ERROR_INTERRUPTED(-10),

    /** Insufficient memory */
    LIBUSB_ERROR_NO_MEM(-11),

    /** Operation not supported or unimplemented on this platform */
    LIBUSB_ERROR_NOT_SUPPORTED(-12),

    /** Other error */
    LIBUSB_ERROR_OTHER(-99);

    public final int code;

    LibusbError(int code) {
        this.code = code;
    }

    @NonNull
    @Override
    public String toString() {
        return getDescriptionString(code);
    }

    @NotNull
    public static LibusbError fromNative(int code) {
        for (LibusbError error : values()) {
            if (error.code == code) {
                return error;
            }
        }
        throw new IllegalArgumentException("Bad error " + code);
    }

    private static native String getDescriptionString(int code);
}

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
 * Speed codes. Indicates the speed at which the device is operating.
 *
 * @author Jared Woolston (Jared.Woolston@gmail.com)
 */
public enum LibusbSpeed {

    /** The OS doesn't report or know the device speed */
    LIBUSB_SPEED_UNKNOWN (0),

    /** The device is operating at low speed (1.5MBit/s) */
    LIBUSB_SPEED_LOW (1),

    /** The device is operating at full speed (12MBit/s) */
    LIBUSB_SPEED_FULL (2),

    /** The device is operating at high speed (480MBit/s) */
    LIBUSB_SPEED_HIGH (3),

    /** The device is operating at super speed (5000MBit/s) */
    LIBUSB_SPEED_SUPER (4),

    /** The device is operating at super speed plus (10000MBit/s) */
    LIBUSB_SPEED_SUPER_PLUS (5),

    /** The device is operating at super speed plus x2 (20000MBit/s) */
    LIBUSB_SPEED_SUPER_PLUS_X2 (6);

    public final int code;

    LibusbSpeed(int code) {
        this.code = code;
    }

    @NotNull
    public static LibusbSpeed fromNative(int code) {
        for (LibusbSpeed error : values()) {
            if (error.code == code) {
                return error;
            }
        }
        throw new IllegalArgumentException("Bad speed " + code);
    }
}

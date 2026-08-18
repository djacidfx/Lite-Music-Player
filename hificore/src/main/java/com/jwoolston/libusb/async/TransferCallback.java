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

package com.jwoolston.libusb.async;

import androidx.annotation.NonNull;

import com.jwoolston.libusb.LibusbError;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author Jared Woolston (Jared.Woolston@gmail.com)
 */
public interface TransferCallback {

    void onTransferComplete(@NonNull AsyncTransfer transfer, int bytesTransferred) throws IOException;
    void onTransferFailed(@NonNull AsyncTransfer transfer, @NonNull LibusbError result, int bytesTransferred) throws IOException;
}

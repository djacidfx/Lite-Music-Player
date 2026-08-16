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
// Created by ideal on 4/10/2019.
//

#ifndef FREEIMAGE_LOGGING_H
#define FREEIMAGE_LOGGING_H

#include <jni.h>

#define LOGD(...) __uac_log_debug(LOG_TAG, __VA_ARGS__)
#define LOGI(...) __uac_log_info(LOG_TAG, __VA_ARGS__)
#define LOGW(...) __uac_log_warn(LOG_TAG, __VA_ARGS__)
#define LOGE(...) __uac_log_error(LOG_TAG, __VA_ARGS__)

void initializeUacLog(JNIEnv *env);

void __uac_log_debug(const char *tag, const char *fmt, ...);

void __uac_log_info(const char *tag, const char *fmt, ...);

void __uac_log_warn(const char *tag, const char *fmt, ...);

void __uac_log_error(const char *tag, const char *fmt, ...);

#endif //FREEIMAGE_LOGGING_H
#include <stdio.h>
#include "logging.h"
#include "stdbool.h"

#ifdef __ANDROID__
#include "android/log.h"
#endif

JavaVM *javaVM;
jobject uac_logClass;
jmethodID branchDebug;
jmethodID branchInfo;
jmethodID branchWarning;
jmethodID branchError;

#define LOG_TAG "logging-native"

void initializeUacLog(JNIEnv *env) {
    (*env)->GetJavaVM(env, &javaVM);
    jclass localClass = (*env)->FindClass(env, "androidx/media3/common/util/Log");
    uac_logClass = (jclass) (*env)->NewGlobalRef(env, localClass);
    branchDebug = (*env)->GetStaticMethodID(env, uac_logClass, "d", "(Ljava/lang/String;Ljava/lang/String;)V");
    branchInfo = (*env)->GetStaticMethodID(env, uac_logClass, "i", "(Ljava/lang/String;Ljava/lang/String;)V");
    branchWarning = (*env)->GetStaticMethodID(env, uac_logClass, "w", "(Ljava/lang/String;Ljava/lang/String;)V");
    branchError = (*env)->GetStaticMethodID(env, uac_logClass, "e", "(Ljava/lang/String;Ljava/lang/String;)V");
}

void uacLog(jmethodID method, const char *tag, const char *fmt, va_list args) {
    JNIEnv *jniEnv;
    // double check it's all ok
    int getEnvStat = (*javaVM)->GetEnv(javaVM, (void **) &jniEnv, JNI_VERSION_1_6);
    bool didAttach = false;
    if (getEnvStat == JNI_EDETACHED) {
        if ((*javaVM)->AttachCurrentThread(javaVM, &jniEnv, NULL) == 0) {
            didAttach = true;
        }
    }

    jstring tagString = (*jniEnv)->NewStringUTF(jniEnv, tag);
    char message[500];
    vsnprintf(&message[0], sizeof(message), fmt, args);
    jstring messageString = (*jniEnv)->NewStringUTF(jniEnv, message);

    (*jniEnv)->CallStaticVoidMethod(jniEnv, uac_logClass, method, tagString, messageString);
    if ((*jniEnv)->ExceptionOccurred(jniEnv)) {
#ifdef __ANDROID__
        __android_log_print(ANDROID_LOG_ERROR, "Native Log", "Native logging failed");
#else
        fprintf(stderr, "Native logging failed");
#endif
    }

    if (didAttach) {
        (*javaVM)->DetachCurrentThread(javaVM);
    }
}

void __uac_log_debug(const char *tag, const char *fmt, ...) {
    va_list localArgs;
    va_start(localArgs, fmt);
    uacLog(branchDebug, tag, fmt, localArgs);
    va_end(localArgs);
}

void __uac_log_info(const char *tag, const char *fmt, ...) {
    va_list localArgs;
    va_start(localArgs, fmt);
    uacLog(branchInfo, tag, fmt, localArgs);
    va_end(localArgs);
}

void __uac_log_warn(const char *tag, const char *fmt, ...) {
    va_list localArgs;
    va_start(localArgs, fmt);
    uacLog(branchWarning, tag, fmt, localArgs);
    va_end(localArgs);
}

void __uac_log_error(const char *tag, const char *fmt, ...) {
    va_list localArgs;
    va_start(localArgs, fmt);
    uacLog(branchError, tag, fmt, localArgs);
    va_end(localArgs);
}
#ifndef PRIVILEGE_ADB_LOGGING_H
#define PRIVILEGE_ADB_LOGGING_H

#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG "PrivilegeAdb"
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#endif

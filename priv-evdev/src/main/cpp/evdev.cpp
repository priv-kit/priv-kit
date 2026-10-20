#include <jni.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>

static void fail(JNIEnv* env, const char* operation, int error) {
    jclass type = env->FindClass("android/system/ErrnoException");
    if (!type) return;
    jmethodID constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;I)V");
    if (!constructor) return;
    jstring name = env->NewStringUTF(operation);
    if (!name) return;
    auto exception = static_cast<jthrowable>(env->NewObject(type, constructor, name, error));
    if (exception) env->Throw(exception);
}

static jintArray integers(JNIEnv* env, const jint* values, jsize count) {
    jintArray result = env->NewIntArray(count);
    if (result && count) env->SetIntArrayRegion(result, 0, count, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_priv_kit_evdev_internal_PrivilegeEvdevNative_open(JNIEnv* env, jobject, jstring path, jboolean writable) {
    const char* name = env->GetStringUTFChars(path, nullptr);
    if (!name) return -1;
    int fd = open(name, (writable ? O_RDWR : O_RDONLY) | O_CLOEXEC | O_NONBLOCK | O_NOFOLLOW);
    int error = errno;
    env->ReleaseStringUTFChars(path, name);
    if (fd < 0) { fail(env, "open", error); return -1; }
    struct stat status{};
    if (fstat(fd, &status) < 0) {
        error = errno;
    } else if (!S_ISCHR(status.st_mode)) {
        error = ENODEV;
    } else {
        int version = 0;
        if (ioctl(fd, EVIOCGVERSION, &version) >= 0) return fd;
        error = errno;
    }
    close(fd);
    fail(env, "open evdev", error);
    return -1;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_priv_kit_evdev_internal_PrivilegeEvdevNative_name(JNIEnv* env, jobject, jint fd) {
    char name[256]{};
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) { fail(env, "EVIOCGNAME", errno); return nullptr; }
    // Decode UTF-8 on the Kotlin side, including replacement of malformed kernel bytes.
    jsize size = static_cast<jsize>(strnlen(name, sizeof(name)));
    jbyteArray result = env->NewByteArray(size);
    if (result && size) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(name));
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_priv_kit_evdev_internal_PrivilegeEvdevNative_bits(JNIEnv* env, jobject, jint fd, jint type) {
    // KEY_MAX is the largest evdev code namespace supported here.
    uint8_t bits[(KEY_MAX + 8) / 8]{};
    if (ioctl(fd, EVIOCGBIT(type, sizeof(bits)), bits) < 0) { fail(env, "EVIOCGBIT", errno); return nullptr; }
    jint values[KEY_MAX + 1]{};
    jsize count = 0;
    for (int i = 0; i <= KEY_MAX; ++i) if (bits[i / 8] & (1u << (i % 8))) values[count++] = i;
    return integers(env, values, count);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_priv_kit_evdev_internal_PrivilegeEvdevNative_axis(JNIEnv* env, jobject, jint fd, jint axis) {
    input_absinfo info{};
    if (ioctl(fd, EVIOCGABS(axis), &info) < 0) { fail(env, "EVIOCGABS", errno); return nullptr; }
    jint values[] = {info.value, info.minimum, info.maximum, info.fuzz, info.flat, info.resolution};
    return integers(env, values, 6);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_priv_kit_evdev_internal_PrivilegeEvdevNative_slots(JNIEnv* env, jobject, jint fd) {
    input_absinfo info{};
    if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &info) < 0) { fail(env, "EVIOCGABS slots", errno); return nullptr; }
    if (info.minimum != 0 || info.maximum < 0 || info.maximum >= 256) {
        fail(env, "slot range", EOVERFLOW); return nullptr;
    }
    jint values[257]{};
    values[0] = ABS_MT_TRACKING_ID;
    const int count = info.maximum + 1;
    if (ioctl(fd, EVIOCGMTSLOTS((count + 1) * sizeof(jint)), values) < 0) {
        fail(env, "EVIOCGMTSLOTS", errno); return nullptr;
    }
    return integers(env, values + 1, count);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_priv_kit_evdev_internal_PrivilegeEvdevNative_write(JNIEnv* env, jobject, jint fd, jintArray packed) {
    const jsize size = env->GetArrayLength(packed);
    if (size < 3 || size > 768 || size % 3) { fail(env, "event batch", EINVAL); return nullptr; }
    jint values[768]{};
    env->GetIntArrayRegion(packed, 0, size, values);
    if (env->ExceptionCheck()) return nullptr;
    input_event events[256]{};
    for (int i = 0; i < size / 3; ++i) {
        events[i].type = static_cast<uint16_t>(values[i * 3]);
        events[i].code = static_cast<uint16_t>(values[i * 3 + 1]);
        events[i].value = values[i * 3 + 2];
    }
    // Allocate the reply before side effects; do not retry even EINTR.
    jintArray reply = env->NewIntArray(2);
    if (!reply) return nullptr;
    const ssize_t bytes = write(fd, events, (size / 3) * sizeof(input_event));
    const int error = bytes < 0 ? errno : 0;
    const jint result[] = {
        bytes < 0 || bytes % sizeof(input_event) != 0 ? -1 : static_cast<jint>(bytes / sizeof(input_event)),
        error,
    };
    env->SetIntArrayRegion(reply, 0, 2, result);
    return reply;
}

extern "C" JNIEXPORT void JNICALL
Java_priv_kit_evdev_internal_PrivilegeEvdevNative_close(JNIEnv*, jobject, jint fd) {
    // On Linux the fd is released even if close reports EINTR; retry could close another fd.
    close(fd);
}

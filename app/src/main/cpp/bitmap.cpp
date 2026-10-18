#include <jni.h>
#include <android/bitmap.h>
#include <fstream>
#include <stdexcept>
#include <string>

static void transfer(JNIEnv *env, jobject bitmap, jstring path, bool write) {
    void *pixels = nullptr;
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot inspect bitmap snapshot");
        const char *chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return;
        const std::string filename(chars);
        env->ReleaseStringUTFChars(path, chars);
        const auto size = static_cast<std::streamsize>(info.stride) * info.height;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot access bitmap pixels");
        if (write) {
            std::ofstream file(filename, std::ios::binary | std::ios::trunc);
            if (!file.write(static_cast<const char *>(pixels), size) || !file.flush())
                throw std::runtime_error("Cannot write bitmap snapshot");
        } else {
            std::ifstream file(filename, std::ios::binary | std::ios::ate);
            if (!file || file.tellg() != size)
                throw std::runtime_error("Missing or incomplete bitmap snapshot");
            file.seekg(0);
            if (!file.read(static_cast<char *>(pixels), size))
                throw std::runtime_error("Cannot read bitmap snapshot");
        }
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    }
    if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jasongzy_medialab_BitmapSnapshots_write(JNIEnv *env, jobject, jobject bitmap, jstring path) {
    transfer(env, bitmap, path, true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jasongzy_medialab_BitmapSnapshots_read(JNIEnv *env, jobject, jobject bitmap, jstring path) {
    transfer(env, bitmap, path, false);
}

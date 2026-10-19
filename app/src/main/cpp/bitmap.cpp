#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
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
Java_com_jasongzy_medialab_BitmapPixels_writeSnapshot(JNIEnv *env, jobject, jobject bitmap, jstring path) {
    transfer(env, bitmap, path, true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jasongzy_medialab_BitmapPixels_readSnapshot(JNIEnv *env, jobject, jobject bitmap, jstring path) {
    transfer(env, bitmap, path, false);
}

// Read and write premultiplied components in the bitmap's own color space.
static void row(JNIEnv *env, jobject bitmap, jint y, jfloatArray values, bool write) {
    void *pixels = nullptr;
    jfloat *channels = nullptr;
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 && info.format != ANDROID_BITMAP_FORMAT_RGBA_F16) ||
            y < 0 || static_cast<uint32_t>(y) >= info.height ||
            !values || static_cast<uint64_t>(env->GetArrayLength(values)) != static_cast<uint64_t>(info.width) * 4 ||
            (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL)
            throw std::runtime_error("Unsupported bitmap row");
        channels = env->GetFloatArrayElements(values, nullptr);
        if (!channels) return;
        if (write)
            for (size_t i = 0; i < static_cast<size_t>(info.width) * 4; ++i)
                if (!std::isfinite(channels[i])) throw std::runtime_error("Invalid pixel component");
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot access bitmap pixels");
        auto *bytes = static_cast<uint8_t *>(pixels) + static_cast<size_t>(y) * info.stride;
        const bool opaque = (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_OPAQUE;
        for (size_t i = 0; i < static_cast<size_t>(info.width) * 4; ++i) {
            if (write) {
                const float value = opaque && i % 4 == 3 ? 1.f : channels[i];
                if (info.format == ANDROID_BITMAP_FORMAT_RGBA_F16)
                    reinterpret_cast<__fp16 *>(bytes)[i] = static_cast<__fp16>(value);
                else bytes[i] = static_cast<uint8_t>(std::lround(std::clamp(value, 0.f, 1.f) * 255.f));
            } else {
                channels[i] = opaque && i % 4 == 3 ? 1.f :
                    info.format == ANDROID_BITMAP_FORMAT_RGBA_F16 ?
                        static_cast<float>(reinterpret_cast<const __fp16 *>(bytes)[i]) : bytes[i] / 255.f;
            }
        }
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), error.what());
    }
    if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
    if (channels) env->ReleaseFloatArrayElements(values, channels, write ? JNI_ABORT : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jasongzy_medialab_BitmapPixels_readRow(JNIEnv *env, jobject, jobject bitmap, jint y, jfloatArray values) {
    row(env, bitmap, y, values, false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jasongzy_medialab_BitmapPixels_writeRow(JNIEnv *env, jobject, jobject bitmap, jint y, jfloatArray values) {
    row(env, bitmap, y, values, true);
}

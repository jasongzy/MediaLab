#include <jni.h>
#include <android/bitmap.h>
#include <libraw/libraw.h>
#include <memory>
#include <string>
#include <vector>

struct RawCallbacks {
    JNIEnv *env;
    jobject decoder;
    jmethodID check;

    bool active() const {
        if (!env->ExceptionCheck()) env->CallVoidMethod(decoder, check);
        return !env->ExceptionCheck();
    }
};

static int progress(void *data, LibRaw_progress, int, int) {
    return static_cast<RawCallbacks *>(data)->active() ? 0 : 1;
}

static void memoryError(JNIEnv *env) {
    env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "RAW image exceeds the memory budget");
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_jasongzy_medialab_RawDecoder_decode(JNIEnv *env, jobject decoder, jstring path, jlong budget) {
    try {
        const auto cls = env->GetObjectClass(decoder);
        RawCallbacks callbacks{env, decoder, env->GetMethodID(cls, "checkActive", "()V")};
        if (!callbacks.active()) return nullptr;
        const char *chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return nullptr;
        const std::string filename(chars);
        env->ReleaseStringUTFChars(path, chars);
        auto raw = std::make_unique<LibRaw>(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
        raw->set_progress_handler(progress, &callbacks);
        raw->imgdata.rawparams.max_raw_memory_mb = static_cast<unsigned>(budget / (1024 * 1024));
        auto success = [&](int code) {
            if (!callbacks.active()) return false;
            if (code == LIBRAW_UNSUFFICIENT_MEMORY || code == LIBRAW_MEMPOOL_OVERFLOW || code == LIBRAW_TOO_BIG)
                memoryError(env);
            return code == LIBRAW_SUCCESS;
        };
        if (!success(raw->open_file(filename.c_str()))) return nullptr;
        const auto &size = raw->imgdata.sizes;
        // RAW mosaic, RGB processing and demosaicing scratch buffers coexist.
        if (budget <= 0 || uint64_t(size.raw_width) * size.raw_height * 20 + (16 << 20) > uint64_t(budget)) {
            memoryError(env);
            return nullptr;
        }
        auto &params = raw->imgdata.params;
        params.use_camera_wb = 1;
        params.use_auto_wb = 1;
        params.output_color = 1;
        params.output_bps = 16;
        params.gamm[0] = 1.0 / 2.4;
        params.gamm[1] = 12.92;
        if (!success(raw->unpack()) || !success(raw->dcraw_process())) return nullptr;
        int width, height, colors, bits;
        raw->get_mem_image_format(&width, &height, &colors, &bits);
        if (width <= 0 || height <= 0 || (colors != 1 && colors != 3) || bits != 16) return nullptr;
        if (uint64_t(width) * height * 20 > uint64_t(budget)) {
            memoryError(env);
            return nullptr;
        }
        std::vector<uint16_t> rgb(size_t(width) * height * colors);
        if (!success(raw->copy_mem_image(rgb.data(), width * colors * 2, 0))) return nullptr;
        raw.reset();
        const auto create = env->GetMethodID(cls, "createBitmap", "(II)Landroid/graphics/Bitmap;");
        jobject bitmap = env->CallObjectMethod(decoder, create, width, height);
        if (!bitmap || env->ExceptionCheck()) return nullptr;
        AndroidBitmapInfo info{};
        void *pixels = nullptr;
        const bool locked = AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_F16 &&
            AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS;
        if (locked) {
            for (int y = 0; y < height && callbacks.active(); ++y) {
                auto *row = reinterpret_cast<__fp16 *>(static_cast<uint8_t *>(pixels) + size_t(y) * info.stride);
                const auto *source = rgb.data() + size_t(y) * width * colors;
                for (int x = 0; x < width; ++x) {
                    for (int c = 0; c < 3; ++c)
                        row[x * 4 + c] = static_cast<__fp16>(source[x * colors + (colors == 1 ? 0 : c)] / 65535.f);
                    row[x * 4 + 3] = 1;
                }
            }
        }
        const auto error = env->ExceptionOccurred();
        if (error) env->ExceptionClear();
        if (locked) AndroidBitmap_unlockPixels(env, bitmap);
        if (!locked || error) {
            env->CallVoidMethod(bitmap, env->GetMethodID(env->GetObjectClass(bitmap), "recycle", "()V"));
            if (error) env->Throw(error);
            else env->ThrowNew(env->FindClass("java/io/IOException"), "Cannot access RAW bitmap");
            return nullptr;
        }
        return bitmap;
    } catch (const std::bad_alloc &) {
        if (!env->ExceptionCheck()) memoryError(env);
        return nullptr;
    }
}

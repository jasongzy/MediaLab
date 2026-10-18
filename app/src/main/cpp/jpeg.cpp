#include <jni.h>
#include <turbojpeg.h>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <vector>

static std::string string(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::runtime_error("Cannot read path");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jasongzy_medialab_JpegNative_crop(JNIEnv *env, jobject, jstring input,
    jstring output, jint x, jint y, jint width, jint height) {
    tjhandle handle = tj3Init(TJINIT_TRANSFORM);
    unsigned char *result = nullptr;
    try {
        if (!handle) throw std::runtime_error("Cannot initialize JPEG transform");
        std::ifstream file(string(env, input), std::ios::binary | std::ios::ate);
        auto length = file.tellg();
        if (!file || length <= 0 || length > 536870912)
            throw std::runtime_error("JPEG input exceeds the transform memory budget");
        std::vector<unsigned char> bytes(static_cast<size_t>(length));
        file.seekg(0);
        if (!file.read(reinterpret_cast<char *>(bytes.data()), length))
            throw std::runtime_error("Cannot read JPEG");
        // Keep ICC; edit history and embedded-image offsets belong to the source geometry.
        tj3Set(handle, TJPARAM_SAVEMARKERS, 4);
        if (tj3DecompressHeader(handle, bytes.data(), bytes.size()) < 0)
            throw std::runtime_error(tj3GetErrorStr(handle));
        int sub = tj3Get(handle, TJPARAM_SUBSAMP);
        int iw = tj3Get(handle, TJPARAM_JPEGWIDTH), ih = tj3Get(handle, TJPARAM_JPEGHEIGHT);
        if (sub < 0 || sub >= TJ_NUMSAMP || x < 0 || y < 0 || width <= 0 || height <= 0 ||
            width > iw - x || height > ih - y || x % tjMCUWidth[sub] || y % tjMCUHeight[sub])
            throw std::runtime_error("Invalid JPEG block-aligned crop");
        tjtransform transform{};
        transform.r = {x, y, width, height};
        transform.op = TJXOP_NONE;
        transform.options = TJXOPT_CROP;
        size_t size = 0;
        if (tj3Transform(handle, bytes.data(), bytes.size(), 1, &result, &size, &transform) < 0)
            throw std::runtime_error(tj3GetErrorStr(handle));
        std::ofstream target(string(env, output), std::ios::binary | std::ios::trunc);
        if (!target.write(reinterpret_cast<char *>(result), size) || !target.flush())
            throw std::runtime_error("Cannot write JPEG");
    } catch (const std::exception &error) {
        env->ThrowNew(env->FindClass("java/io/IOException"), error.what());
    }
    tj3Free(result);
    if (handle) tj3Destroy(handle);
}

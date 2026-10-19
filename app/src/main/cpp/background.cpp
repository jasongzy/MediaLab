#include <jni.h>
#include <MNN/Interpreter.hpp>
#include <memory>
#include <stdexcept>
#include <string>

static std::string pathString(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::bad_alloc();
    const auto release = [env, value](const char *text) { env->ReleaseStringUTFChars(value, text); };
    const std::unique_ptr<const char, decltype(release)> owned(chars, release);
    return std::string(chars);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jasongzy_medialab_MnnEngine_infer(
    JNIEnv *env, jobject, jstring path, jstring program, jfloatArray values, jobject ready) {
    try {
        constexpr int pixels = 1024 * 1024;
        if (!values || env->GetArrayLength(values) != pixels * 3)
            throw std::runtime_error("Invalid model input");
        MNN::BackendConfig backend;
        backend.precision = MNN::BackendConfig::Precision_Normal;
        backend.power = MNN::BackendConfig::Power_High;
        MNN::ScheduleConfig config;
        config.type = MNN_FORWARD_OPENCL;
        config.mode = MNN_GPU_TUNING_FAST | MNN_GPU_MEMORY_BUFFER;
        config.backendConfig = &backend;
        auto runtime = MNN::Interpreter::createRuntime({config});
        if (runtime.first.count(MNN_FORWARD_OPENCL) == 0) {
            env->ThrowNew(env->FindClass("java/lang/UnsupportedOperationException"), "OpenCL is unavailable");
            return nullptr;
        }
        std::unique_ptr<MNN::Interpreter> net(MNN::Interpreter::createFromFile(pathString(env, path).c_str()));
        if (!net) throw std::runtime_error("Cannot load model");
        net->setCacheFile(pathString(env, program).c_str());
        auto *session = net->createSession(config, runtime);
        if (!session) throw std::runtime_error("Cannot prepare model");
        int resize = -1;
        if (!net->getSessionInfo(session, MNN::Interpreter::RESIZE_STATUS, &resize) || resize != 0)
            throw std::runtime_error("Cannot allocate model tensors");
        auto *input = net->getSessionInput(session, nullptr);
        auto *output = net->getSessionOutput(session, nullptr);
        if (!input || !output || input->shape() != std::vector<int>({1, 3, 1024, 1024}) ||
            output->elementSize() != pixels || input->getType().code != halide_type_float ||
            output->getType().code != halide_type_float || input->getType().bits != 32 || output->getType().bits != 32)
            throw std::runtime_error("Unexpected model tensors");
        net->releaseModel();
        const auto run = env->GetMethodID(env->GetObjectClass(ready), "run", "()V");
        if (!run) return nullptr;
        env->CallVoidMethod(ready, run);
        if (env->ExceptionCheck()) return nullptr;
        MNN::Tensor hostInput(input, MNN::Tensor::CAFFE);
        if (!hostInput.host<float>()) throw std::bad_alloc();
        env->GetFloatArrayRegion(values, 0, pixels * 3, hostInput.host<float>());
        if (env->ExceptionCheck()) return nullptr;
        if (!input->copyFromHostTensor(&hostInput)) throw std::runtime_error("Cannot upload model input");
        const auto result = net->runSession(session);
        if (result == MNN::OUT_OF_MEMORY) throw std::bad_alloc();
        if (result != MNN::NO_ERROR) throw std::runtime_error("Model inference failed: " + std::to_string(result));
        output->wait(MNN::Tensor::MAP_TENSOR_READ, true);
        MNN::Tensor hostOutput(output, MNN::Tensor::CAFFE);
        if (!hostOutput.host<float>()) throw std::bad_alloc();
        if (!output->copyToHostTensor(&hostOutput)) throw std::runtime_error("Cannot read model output");
        if (net->updateCacheFile(session) != MNN::NO_ERROR)
            throw std::runtime_error("Cannot update compiled model");
        auto mask = env->NewFloatArray(pixels);
        if (mask) env->SetFloatArrayRegion(mask, 0, pixels, hostOutput.host<float>());
        return mask;
    } catch (const std::bad_alloc &) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Model allocation failed");
    } catch (const std::exception &error) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    }
    return nullptr;
}

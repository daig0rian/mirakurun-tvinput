#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "tsreadex/servicefilter.hpp"

#define LOG_TAG "TsReadexJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int TS_PACKET_SIZE = 188;

extern "C" {

// Creates a CServiceFilter and returns its pointer as jlong handle.
// programNumberOrIndex: -1 = first service in PAT (use for single-service streams from Mirakurun)
// audio1Mode: 1+8 = ensure audio1 stream present + split dual-mono into two mono PIDs
// captionMode: 1 = ensure caption stream present (for future ARIB subtitle use)
JNIEXPORT jlong JNICALL
Java_com_daig0rian_mirakurun_tvinput_TsReadexFilter_nativeCreate(
        JNIEnv *env, jclass clazz,
        jint programNumberOrIndex, jint audio1Mode, jint captionMode)
{
    auto *filter = new CServiceFilter();
    filter->SetProgramNumberOrIndex(programNumberOrIndex);
    filter->SetAudio1Mode(audio1Mode);
    // audio2Mode=0: no special handling — 0x0111 is added to the PMT only when tsreadex detects
    // dual-mono (m_isAudio1DualMono becomes true). Single-audio channels never declare 0x0111,
    // so ExoPlayer reports one audio group and no track-switch UI appears.
    filter->SetAudio2Mode(0);
    filter->SetCaptionMode(captionMode);
    LOGI("created filter: prog=%d audio1=%d caption=%d", programNumberOrIndex, audio1Mode, captionMode);
    return reinterpret_cast<jlong>(filter);
}

// Processes a buffer containing N complete TS packets (188*N bytes).
// Returns the filtered output as a byte array (may be larger or smaller than input).
JNIEXPORT jbyteArray JNICALL
Java_com_daig0rian_mirakurun_tvinput_TsReadexFilter_nativeProcessPackets(
        JNIEnv *env, jclass clazz,
        jlong handle, jbyteArray inputArray, jint inputLen)
{
    if (handle == 0) {
        LOGE("processPackets called with null handle");
        return env->NewByteArray(0);
    }

    auto *filter = reinterpret_cast<CServiceFilter *>(handle);

    jbyte *input = env->GetByteArrayElements(inputArray, nullptr);
    if (!input) return env->NewByteArray(0);

    filter->ClearPackets();

    int packetCount = inputLen / TS_PACKET_SIZE;
    for (int i = 0; i < packetCount; i++) {
        const uint8_t *packet = reinterpret_cast<const uint8_t *>(input) + i * TS_PACKET_SIZE;
        if (packet[0] != 0x47) {
            LOGE("bad sync byte at packet %d: 0x%02X", i, packet[0]);
            continue;
        }
        filter->AddPacket(packet);
    }

    env->ReleaseByteArrayElements(inputArray, input, JNI_ABORT);

    const auto &out = filter->GetPackets();
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (!out.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                                reinterpret_cast<const jbyte *>(out.data()));
    }
    return result;
}

// Destroys the CServiceFilter instance.
JNIEXPORT void JNICALL
Java_com_daig0rian_mirakurun_tvinput_TsReadexFilter_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle)
{
    if (handle == 0) return;
    delete reinterpret_cast<CServiceFilter *>(handle);
    LOGI("filter destroyed");
}

} // extern "C"

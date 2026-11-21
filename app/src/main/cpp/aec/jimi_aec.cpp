// JNI wrapper over WebRTC AEC3 (webrtc-audio-processing 2.1), built by tools/fetch_aec.sh (D-103).
// 16 kHz mono; each call takes one 80 ms chunk (8 frames of 10 ms) of playback reference + microphone.
#include <jni.h>
#include <webrtc/modules/audio_processing/include/audio_processing.h>

namespace {
constexpr int kFrame = 160;

struct Aec {
    rtc::scoped_refptr<webrtc::AudioProcessing> apm;
    webrtc::StreamConfig cfg{16000, 1};
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_alrasin_jimi_voice_EchoCanceller_nativeCreate(JNIEnv*, jclass, jboolean noiseSuppression) {
    auto* a = new Aec();
    a->apm = webrtc::AudioProcessingBuilder().Create();
    if (!a->apm) { delete a; return 0; }
    webrtc::AudioProcessing::Config c;
    c.echo_canceller.enabled = true;
    c.echo_canceller.mobile_mode = false;   // full AEC3; mobile mode (AECM) is far weaker
    c.high_pass_filter.enabled = true;
    c.noise_suppression.enabled = noiseSuppression;
    a->apm->ApplyConfig(c);
    return reinterpret_cast<jlong>(a);
}

// far and mic hold the same number of samples (a multiple of 160); out gets the cleaned microphone.
extern "C" JNIEXPORT void JNICALL
Java_com_alrasin_jimi_voice_EchoCanceller_nativeProcess(JNIEnv* env, jclass, jlong h,
                                                        jshortArray far, jshortArray mic, jshortArray out) {
    auto* a = reinterpret_cast<Aec*>(h);
    const jsize n = env->GetArrayLength(mic);
    if (env->GetArrayLength(far) < n || env->GetArrayLength(out) < n) return;
    jshort* f = env->GetShortArrayElements(far, nullptr);
    jshort* m = env->GetShortArrayElements(mic, nullptr);
    jshort* o = env->GetShortArrayElements(out, nullptr);
    int16_t scratch[kFrame];
    for (jsize i = 0; i + kFrame <= n; i += kFrame) {
        a->apm->ProcessReverseStream(f + i, a->cfg, a->cfg, scratch);
        a->apm->set_stream_delay_ms(0);   // AEC3 finds the real delay itself
        a->apm->ProcessStream(m + i, a->cfg, a->cfg, o + i);
    }
    env->ReleaseShortArrayElements(out, o, 0);
    env->ReleaseShortArrayElements(mic, m, JNI_ABORT);
    env->ReleaseShortArrayElements(far, f, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alrasin_jimi_voice_EchoCanceller_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Aec*>(h);
}

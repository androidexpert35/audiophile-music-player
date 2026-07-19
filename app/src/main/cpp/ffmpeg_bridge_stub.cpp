// ────────────────────────────────────────────────────────────────────────────
// ffmpeg_bridge_stub.cpp
//
// Fallback JNI implementation used when the vendored FFmpeg shared libraries
// have not yet been dropped into `app/src/main/cpp/prebuilt/<abi>/lib/`.
//
// The stub exposes **exactly** the same JNI symbols as the real bridge so
// the APK still builds and launches. Any call to `nativeOpen` throws an
// `FFmpegDecoderException` with a clear provisioning message; every other
// getter returns a neutral zero/empty value so the host Kotlin layer can log
// and move on without crashing.
//
// Replace this stub by dropping `libavformat.so`, `libavcodec.so`,
// `libswresample.so`, and `libavutil.so` into the per-ABI `prebuilt/` trees —
// CMake will automatically switch to the real bridge.
// ────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "AudiophileNative"
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static void throw_provisioning_error(JNIEnv *env) {
    jclass ex = env->FindClass(
        "com/androidexpert35/audiophilemusicplayer/data/playback/native_/FFmpegDecoderException");
    if (ex != nullptr) {
        env->ThrowNew(
            ex,
            "FFmpeg shared libraries have not been provisioned. "
            "Drop libavformat.so / libavcodec.so / libswresample.so / libavutil.so "
            "into app/src/main/cpp/prebuilt/<abi>/lib/ and rebuild. "
            "See app/src/main/cpp/prebuilt/README.md for the build recipe."
        );
        env->DeleteLocalRef(ex);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeOpen(
    JNIEnv *env, jclass, jstring, jboolean) {
    ALOGW("nativeOpen called but FFmpeg is not provisioned — returning error");
    throw_provisioning_error(env);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeClose(
    JNIEnv *, jclass, jlong) {}


extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeReadNextBuffer(
    JNIEnv *, jclass, jlong, jobject, jint) { return -1; }

extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeReadNextDsdBuffer(
    JNIEnv *, jclass, jlong, jbyteArray) { return -1; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeSeek(
    JNIEnv *, jclass, jlong, jlong) { return JNI_FALSE; }

extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetSampleRate(
    JNIEnv *, jclass, jlong) { return 0; }
extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetChannelCount(
    JNIEnv *, jclass, jlong) { return 0; }
extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetSourceBitDepth(
    JNIEnv *, jclass, jlong) { return 0; }
extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetAndroidPcmEncoding(
    JNIEnv *, jclass, jlong) { return 0; }
extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetBytesPerSample(
    JNIEnv *, jclass, jlong) { return 0; }
extern "C" JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetDurationUs(
    JNIEnv *, jclass, jlong) { return 0; }
extern "C" JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetBitrateBps(
    JNIEnv *, jclass, jlong) { return 0; }
extern "C" JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeIsDsd(
    JNIEnv *, jclass, jlong) { return JNI_FALSE; }
extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetDsdRate(
    JNIEnv *, jclass, jlong) { return 0; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeIsResampledDsd(
    JNIEnv *, jclass, jlong) { return JNI_FALSE; }

// Returns false in the stub — integrated libsoxr is not present in stub builds.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeIsUsingSoxrIntegrated(
    JNIEnv *, jclass, jlong) { return JNI_FALSE; }

// Returns the default -2 dB fallback — no AVDictionary to read in stub builds.
extern "C" JNIEXPORT jfloat JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetReplayGainDb(
    JNIEnv *, jclass, jlong) { return -2.0f; }

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetCodecName(
    JNIEnv *env, jclass, jlong) {
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetCodecProfileName(
    JNIEnv *env, jclass, jlong) {
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetContainerName(
    JNIEnv *env, jclass, jlong) {
    return env->NewStringUTF("");
}


// ── SueBridge stubs ──────────────────────────────────────────────────────────
// libavfilter is not available in the stub build.  nativeCreate returns 0L so
// SueStage.isActive is false and the pipeline bypasses SUE without crashing.

extern "C" JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeCreate(
    JNIEnv *, jclass,
    jboolean /*isForce48kResampleOnly*/,
    jint /*codecTier*/,
    jint /*bitrateKbps*/,
    jint /*sampleRateHz*/,
    jint /*targetSampleRateHz*/,
    jint /*channelCount*/,
    jint /*inputEncoding*/,
    jboolean /*downstreamHqResamplerActive*/,
    jint /*specialFlags*/,
    jboolean /*isSueEnabled*/,
    jboolean /*isHiResEnabled*/,
    jboolean /*isLosslessSource*/,
    jfloat /*replayGainDb*/) {
    ALOGW("SueBridge.nativeCreate: FFmpeg not provisioned — SUE stage inactive");
    return 0L;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeConsumeLastInitError(
    JNIEnv *env, jclass) {
    return env->NewStringUTF("FFmpeg/libavfilter is not provisioned in this build.");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeProcessBytes(
    JNIEnv *, jclass, jlong, jobject, jint, jint, jobject, jint) { return -1; }

extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeFlushBytes(
    JNIEnv *, jclass, jlong, jobject, jint) { return 0; }

extern "C" JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeReset(
    JNIEnv *, jclass, jlong) {}

extern "C" JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_engine_audiophile_SueBridge_nativeDestroy(
    JNIEnv *, jclass, jlong) {}


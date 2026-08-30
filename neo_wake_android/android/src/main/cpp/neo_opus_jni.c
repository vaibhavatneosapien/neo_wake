#include <jni.h>
#include <stdint.h>

#include "opus_bridge.h"

/// JNI glue over the shared `opus_bridge.h`/`.c` — the SAME bridge file
/// `neo_wake_ios` compiles (copied verbatim, see this directory's
/// third_party/opus/VENDORING.md for why nothing here reimplements opus
/// API wrapping a second time). This file is the only Android-specific
/// piece: JNI method names + `jlong`-as-opaque-pointer marshalling, called
/// from `NeoOpusDecoder.kt`'s `external fun`s.

JNIEXPORT jlong JNICALL
Java_xyz_neosapien_neo_1wake_NeoOpusDecoder_nativeCreate(JNIEnv *env, jobject thiz,
                                                          jint sample_rate, jint channels) {
    (void)env;
    (void)thiz;
    NeoOpusDecoderHandle *handle = neo_opus_decoder_create((int32_t)sample_rate, (int)channels);
    return (jlong)(intptr_t)handle;
}

JNIEXPORT jint JNICALL
Java_xyz_neosapien_neo_1wake_NeoOpusDecoder_nativeDecode(JNIEnv *env, jobject thiz, jlong handle,
                                                          jbyteArray payload, jint payload_len,
                                                          jshortArray pcm_out, jint frame_size) {
    (void)thiz;
    if (handle == 0) return -1;

    jbyte *payloadBytes = (*env)->GetByteArrayElements(env, payload, NULL);
    jshort *pcmBytes = (*env)->GetShortArrayElements(env, pcm_out, NULL);
    if (payloadBytes == NULL || pcmBytes == NULL) {
        if (payloadBytes != NULL) (*env)->ReleaseByteArrayElements(env, payload, payloadBytes, JNI_ABORT);
        if (pcmBytes != NULL) (*env)->ReleaseShortArrayElements(env, pcm_out, pcmBytes, JNI_ABORT);
        return -1;
    }

    int result = neo_opus_decode(
        (NeoOpusDecoderHandle *)(intptr_t)handle,
        (const uint8_t *)payloadBytes,
        (int32_t)payload_len,
        (int16_t *)pcmBytes,
        (int)frame_size
    );

    (*env)->ReleaseByteArrayElements(env, payload, payloadBytes, JNI_ABORT);
    // Commit (not abort) the decoded PCM back to the Java-side array.
    (*env)->ReleaseShortArrayElements(env, pcm_out, pcmBytes, 0);

    return (jint)result;
}

JNIEXPORT void JNICALL
Java_xyz_neosapien_neo_1wake_NeoOpusDecoder_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    neo_opus_decoder_destroy((NeoOpusDecoderHandle *)(intptr_t)handle);
}

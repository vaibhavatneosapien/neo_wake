#include "opus_bridge.h"

#include <stdlib.h>

#include "opus.h"

struct NeoOpusDecoderHandle {
    OpusDecoder *decoder;
};

NeoOpusDecoderHandle *neo_opus_decoder_create(int32_t sample_rate, int channels) {
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create((opus_int32)sample_rate, channels, &error);
    if (error != OPUS_OK || decoder == NULL) {
        if (decoder != NULL) {
            opus_decoder_destroy(decoder);
        }
        return NULL;
    }

    NeoOpusDecoderHandle *handle = (NeoOpusDecoderHandle *)malloc(sizeof(NeoOpusDecoderHandle));
    if (handle == NULL) {
        opus_decoder_destroy(decoder);
        return NULL;
    }
    handle->decoder = decoder;
    return handle;
}

int neo_opus_decode(
    NeoOpusDecoderHandle *handle,
    const uint8_t *data,
    int32_t len,
    int16_t *pcm_out,
    int frame_size
) {
    if (handle == NULL || handle->decoder == NULL) {
        return OPUS_INVALID_STATE;
    }
    return opus_decode(
        handle->decoder,
        data,
        (opus_int32)len,
        (opus_int16 *)pcm_out,
        frame_size,
        /*decode_fec=*/0
    );
}

void neo_opus_decoder_destroy(NeoOpusDecoderHandle *handle) {
    if (handle == NULL) {
        return;
    }
    if (handle->decoder != NULL) {
        opus_decoder_destroy(handle->decoder);
    }
    free(handle);
}

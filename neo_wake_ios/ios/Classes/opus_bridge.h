#ifndef NEO_OPUS_BRIDGE_H
#define NEO_OPUS_BRIDGE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Opaque handle over one libopus `OpusDecoder`. KTD3's whole native codec
/// surface is this file: create, decode, destroy — nothing else reaches
/// libopus. Swift never sees `OpusDecoder`/`opus.h` directly (see
/// NeoOpusDecoder.swift), so a header layout change in the vendored source
/// stays contained to this one bridge.
typedef struct NeoOpusDecoderHandle NeoOpusDecoderHandle;

/// Creates a mono/stereo Opus decoder. `sampleRate` must be one of the rates
/// libopus accepts (8000/12000/16000/24000/48000 — see opus.h); this plugin
/// always uses 16000/1 (KTD3's pendant audio contract). Returns NULL on any
/// failure — the caller (NeoOpusDecoder.swift) treats a NULL handle the same
/// as any other decode failure, no error code surfaced past this point.
NeoOpusDecoderHandle *_Nullable neo_opus_decoder_create(int32_t sample_rate, int channels);

/// Decodes one Opus packet. Returns the number of decoded samples per
/// channel (== `frame_size` on success), or a negative libopus error code
/// (see opus_defines.h `OPUS_*` — e.g. OPUS_INVALID_PACKET) on failure. Never
/// pass a NULL `data`/`pcm_out` — this bridge does not support opus's PLC
/// mode (NULL `data` means "conceal a lost packet" in the underlying API);
/// the caller always has real bytes or does not call this at all.
int neo_opus_decode(
    NeoOpusDecoderHandle *_Nonnull handle,
    const uint8_t *_Nonnull data,
    int32_t len,
    int16_t *_Nonnull pcm_out,
    int frame_size
);

void neo_opus_decoder_destroy(NeoOpusDecoderHandle *_Nullable handle);

#ifdef __cplusplus
}
#endif

#endif /* NEO_OPUS_BRIDGE_H */

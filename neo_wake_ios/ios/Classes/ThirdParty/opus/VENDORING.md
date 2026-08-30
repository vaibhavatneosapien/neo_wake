# Vendored libopus 1.4

Source: https://downloads.xiph.org/releases/opus/opus-1.4.tar.gz (upstream
Xiph.Org release, BSD-licensed — see `COPYING`).

## Why vendored source, not a CocoaPods pod

The only CocoaPods trunk pod named `libopus` is version 1.1, last published in
2014 — unmaintained for over a decade with unpatched decoder-side CVEs from
the years since. A newer prebuilt pod (`libopus-static`, 1.3.1) exists but its
header/module layout could not be verified without an actual `pod install`
against a real host app (this plugin has no `example/` app yet — see
`ios/Tests/NeoWakeSessionsTests.swift`'s own note on the same gap). Vendoring
known-good upstream C source, compiled directly by this pod's own
`source_files`, sidesteps both problems and keeps exactly one pinned opus
version in lockstep with the Android side (`neo_wake_android/android/src/main/cpp/third_party/opus`,
same 1.4 source, same trim) — decode is spec-compliant (RFC 6716) regardless
of build toolchain, so the two platforms decode identically even though nothing
here is literally shared across them (a federated plugin's iOS/Android halves
are independent packages).

## What's trimmed from the full 1.4 release

Kept: `celt/*.{c,h}` (top-level only — no `arm/`, `mips/`, `x86/`, `tests/`),
`silk/*.{c,h}` (top-level only) plus `silk/float/*.{c,h}` (the standard
non-fixed-point SILK variant — no `silk/fixed/`, `silk/arm/`, `silk/mips/`,
`silk/x86/`, `silk/tests/`), `include/*.h`, and from the top-level `src/`:
`opus.c`, `opus_decoder.c`, `opus_encoder.c`, `repacketizer.c`, `analysis.c`,
`mlp.c`, `mlp_data.c` plus their private headers.

This is the STANDARD non-fixed-point ("float") opus build — SILK's common
source list (`silk_sources.mk`'s `SILK_SOURCES`) always mixes decode- and
encode-only files together (e.g. `enc_API.c` needs `silk/float/*.h`), so a
hand-trimmed decode-only file list is not something upstream's own build
system offers; encoder code ships as dead weight (never called — this plugin
only decodes) rather than as an unverified custom trim. Cut: no SIMD variants
(`arm/`, `mips/`, `x86/` — perf-only, not correctness), no fixed-point SILK,
no `opus_custom*`/`opus_projection*`/`opus_multistream*` (ambisonics/custom
modes this plugin never uses), no `opus_demo.c`/`opus_compare.c`/
`repacketizer_demo.c`/`celt/opus_custom_demo.c` (CLI tools with their own
`main()`).

## Build recipe (podspec `pod_target_xcconfig`/`compiler_flags`)

`config.h` here is a minimal hand-written set (`HAVE_CONFIG_H=1` and the
usual POSIX `HAVE_*` defines), not autotools-generated — opus's own configure
script cannot run inside a CocoaPods build, and this exact minimal set is a
long-standing known-working recipe for cross-compiled/embedded opus builds
(no `OPUS_FIXED_POINT` — this is the float build).

## Verification performed in this environment

Compiled this EXACT file set (celt + silk + silk/float + the src/ list above)
with this EXACT `config.h`, using the host machine's `clang` (not a
cross-compile — Xcode/iOS toolchain specifics are unverified), and ran a real
encode→decode round trip of a 440 Hz test tone plus a garbage-payload
rejection check. Both passed: decoded RMS landed within tolerance of the
input tone's RMS (proving the DSP path actually ran, not just linked), and a
garbage packet was correctly rejected (`opus_decode` returned
`OPUS_INVALID_PACKET`). This is real evidence the source list + config.h
compile and behave correctly — it does NOT prove the iOS ARM64
cross-compile links inside an actual CocoaPods build, which needs a real
`pod install` + Xcode build this environment cannot run (see the U3 task's
verification notes).

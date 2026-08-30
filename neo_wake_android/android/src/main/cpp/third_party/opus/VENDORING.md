# Vendored libopus 1.4

Same source, same trim, same rationale as
`neo_wake_ios/ios/Classes/ThirdParty/opus/VENDORING.md` — read that file
first; this is the Android mirror of the identical decision, not a separate
one. Two independent copies because a federated Flutter plugin's iOS/Android
halves are separate packages with no shared native source directory
convention in this repo, not because the choice differs — both are pinned to
opus 1.4, both keep the same file trim (`celt/*.{c,h}` top-level,
`silk/*.{c,h}` + `silk/float/*.{c,h}` top-level, the same decode+encode `src/`
list). Opus decode is bit-exact per RFC 6716 regardless of build toolchain,
so the two platforms decode identically even though nothing here is linked
across them.

## Why CMake-from-source, not a prebuilt AAR

No Android Gradle dependency for libopus was found with the same trust level
as building straight from Xiph's own release tarball: no first-party Google
Maven artifact exists, and the third-party AARs found (searched Maven
Central) are unofficial mirrors of unclear provenance/version/build flags.
Xiph's own release ships a working `CMakeLists.txt` (`opus-1.4/CMakeLists.txt`
+ `cmake/*.cmake`) designed for exactly this kind of NDK
`externalNativeBuild` integration, so `neo_wake_android`'s own
`CMakeLists.txt` (`cpp/CMakeLists.txt`) simply `add_subdirectory()`s this
vendored tree rather than reimplementing Xiph's build logic — same "trust the
vendor's own build system over a hand-rolled one" reasoning as the iOS side's
proven hand-written `config.h` recipe (that recipe exists only because
CocoaPods has no CMake-equivalent `add_subdirectory` for source pods).

## Verification performed in this environment

Compiled this exact file set with the host machine's `clang` and ran a real
encode->decode round trip (see the iOS VENDORING.md for the detailed result).
Additionally, for the Android path specifically: cross-compiled via
`cmake --toolchain <NDK>/build/cmake/android.toolchain.cmake` targeting
`arm64-v8a` (matching an actual `externalNativeBuild` invocation) using the
NDK already present in this environment — see the U3 task report for the
exact command and result. This proves the CMake configuration + NDK
cross-compile toolchain accepts this exact vendored tree and JNI bridge; it
does NOT prove a full Gradle/AGP `externalNativeBuild` run end-to-end (no
`gradlew` present in this checkout — see `build.gradle`'s own
`isStandalone` handling) or an actual on-device JNI call, both of which
remain device/emulator-gated.

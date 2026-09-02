#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint neo_wake_ios.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'neo_wake_ios'
  s.version          = '0.0.1'
  s.summary          = 'iOS implementation of the neo_wake plugin.'
  s.description      = <<-DESC
iOS implementation of the neo_wake federated Flutter plugin — native ONNX
wake-word detection.
                       DESC
  s.homepage         = 'https://neosapien.xyz'
  s.license          = { :type => 'Proprietary' }
  s.author           = { 'Neo Sapien' => 'engineering@neosapien.xyz' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  # Only the C bridge header is public (Swift imports it through the module
  # umbrella). Everything else — crucially the vendored libopus headers — is
  # PRIVATE, so the generated `neo_wake_ios-umbrella.h` does NOT #import opus's
  # internal headers. Without this, the umbrella pulls in both fixed-point
  # variants (DIV32 macro redefined) and the ARM-NE10 optimized modes header
  # (NE10_types.h not found — NE10 isn't vendored), breaking the module build.
  # The opus .c still compiles fine via HEADER_SEARCH_PATHS below; those headers
  # simply don't need to be in the module's public surface.
  s.public_header_files = 'Classes/opus_bridge.h'
  # Vendored libopus (KTD3) ships its own docs/licence text alongside the C
  # source under the same Classes/** glob — not compilable input.
  s.exclude_files    = 'Classes/ThirdParty/opus/VENDORING.md', 'Classes/ThirdParty/opus/COPYING'
  # The three ONNX models ship as a resource bundle. At runtime U2 copies the
  # bundled model to a temp path for createSession (KTD7). Lookup key
  # 'neo_wake_ios' -> neo_wake_ios.bundle in the host app.
  s.resource_bundles = { 'neo_wake_ios' => ['Resources/**/*'] }
  s.dependency 'Flutter'

  # Cross-plugin attach (U8, KTD9): neo_wake registers its "wake" listener
  # directly on neo_ble's `BleEventSinks.shared` and hands finished command
  # clips / ambient-suppression signals to `NeoAudioUploader.shared` — see
  # NeoWakeAttach.swift. Direction is neo_wake -> neo_ble ONLY; neo_ble_ios
  # must never depend back on this pod (kept acyclic). NOT verified by a real
  # `pod install` in this bounded task (no device build here) — this is a
  # parse-level podspec change; a real device/CI build is what confirms it
  # links and resolves (neo_ble_ios is already an app-level sibling dependency
  # today, so no version drift is introduced, just a new edge to it).
  s.dependency 'neo_ble_ios'

  # onnxruntime-objc ships static-only, so the app ios/Podfile pre_install
  # must add neo_wake_ios to its static-linkage list under use_frameworks!
  # (plan KTD6 / U2). Pinned to 1.23.0: the last ORT release before the
  # KleidiAI 1.24.x conv memory regression (microsoft/onnxruntime#29538) that
  # flutter_onnxruntime's own podspec calls out — keep this pin in lockstep
  # with flutter_onnxruntime's.
  s.dependency 'onnxruntime-objc', '1.23.0'

  s.platform = :ios, '15.0'

  # Flutter.framework does not contain an i386 slice. Header search paths
  # match flutter_onnxruntime's own podspec for a static onnxruntime-objc pod,
  # plus the vendored libopus (KTD3, ios/Classes/ThirdParty/opus/VENDORING.md)
  # — 'config.h' at the vendor root, 'include/' for opus.h/opus_types.h, and
  # 'celt'/'silk'/'silk/float' because those .c files #include local headers
  # from their own directory (mirrors the -I flags this exact file set was
  # verified against; see VENDORING.md's "Verification performed" section).
  # ORT ships no iOS-simulator slice (see NeoWakeSessionsTests.swift's own
  # note) and neither does a from-source libopus target here — this whole
  # plugin is device-only, matching flutter_opus's existing constraint.
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
    'HEADER_SEARCH_PATHS' => '"${PODS_ROOT}/onnxruntime-objc/objectivec" "${PODS_ROOT}/onnxruntime-objc/objectivec/include" ' \
      '"${PODS_TARGET_SRCROOT}/Classes/ThirdParty/opus" "${PODS_TARGET_SRCROOT}/Classes/ThirdParty/opus/include" ' \
      '"${PODS_TARGET_SRCROOT}/Classes/ThirdParty/opus/celt" "${PODS_TARGET_SRCROOT}/Classes/ThirdParty/opus/silk" ' \
      '"${PODS_TARGET_SRCROOT}/Classes/ThirdParty/opus/silk/float"',
    # -DHAVE_CONFIG_H=1 selects the vendored config.h (not opus's own
    # autotools output, which nothing here runs) — see VENDORING.md. -w
    # silences upstream's own warnings (not this plugin's code); harmless on
    # the Swift files in the same target, which ignore C flags.
    'OTHER_CFLAGS' => '-DHAVE_CONFIG_H=1 -DOPUS_BUILD=1 -w'
  }
  s.swift_version = '5.0'
end

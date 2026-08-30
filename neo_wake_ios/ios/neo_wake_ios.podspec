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
  # The three ONNX models ship as a resource bundle. At runtime U2 copies the
  # bundled model to a temp path for createSession (KTD7). Lookup key
  # 'neo_wake_ios' -> neo_wake_ios.bundle in the host app.
  s.resource_bundles = { 'neo_wake_ios' => ['Resources/**/*'] }
  s.dependency 'Flutter'

  # ONNX Runtime lands in U2. onnxruntime-objc ships static-only, so the app
  # ios/Podfile pre_install must add neo_wake to its static-linkage list under
  # use_frameworks! (see plan KTD6 / U2):
  #   s.dependency 'onnxruntime-objc', '1.23.0'

  s.platform = :ios, '15.0'

  # Flutter.framework does not contain an i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end

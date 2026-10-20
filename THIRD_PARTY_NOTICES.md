# 第三方组件

项目原创代码与示例数据使用根目录 MIT 许可。第三方组件受各自许可约束，不因本项目开源而改变。

主要组件：AndroidX（Compose、Room、Lifecycle、Navigation、DataStore、WorkManager、CameraX）、Kotlin / kotlinx.coroutines、Google ML Kit Text Recognition，以及测试所用的 JUnit / AndroidX Test。确切依赖版本以 `app/build.gradle.kts` 为准；Gradle Wrapper 及其他构建工具也保留上游许可。

发布二进制前应检查直接和传递依赖的许可及要求，按需随包提供其通知。此列表不是完整的二进制许可清单。

- AndroidX：https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt
- Kotlin：https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt
- kotlinx.coroutines：https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt
- ML Kit：https://developers.google.com/ml-kit/terms
- Gradle：https://github.com/gradle/gradle/blob/master/LICENSE
- JUnit：https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt

## 离线语音

以下文件随源码分发，按各自许可提供，不适用根目录 MIT 许可：

- sherpa-onnx 1.12.40（`app/libs/sherpa-onnx-1.12.40.aar`），Apache License 2.0，含 ONNX Runtime 等组件的各自许可：https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE
- espeak-ng 音素数据（`app/src/main/assets/tts/espeak-ng-data/`），GNU GPL v3 或更高版本：https://github.com/espeak-ng/espeak-ng/blob/master/COPYING 。分发包含这些数据的 APK 时，需要同时满足 GPL-3.0 的源码提供与通知要求。
- Piper 语音模型美式 Kristin、英式 Cori（`app/src/main/assets/tts/piper/`），由 Piper / sherpa-onnx 转换的 int8 medium 模型，训练录音来自 LibriVox（公有领域）。模型来源与说明：https://github.com/rhasspy/piper 、https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models

在线发音调用火山引擎语音合成，需用户自备账号与 Key，受火山引擎服务条款约束。

## 算法

复习实现参考 FSRS-5 的算法设计；本项目实现了自己的分项、两等级排期和两参数校准，不是上游完整优化器。背景：https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm

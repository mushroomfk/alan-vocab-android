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

复习实现参考 FSRS-5 的算法设计；本项目实现了自己的两等级排期和两参数校准，不是上游完整优化器。背景：https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm

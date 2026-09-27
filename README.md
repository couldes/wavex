# Wavex（波形）

Android AI 助手客户端，Kotlin + Jetpack Compose 构建。

- 最低支持 Android 版本 / 语言、依赖版本见 `gradle/libs.versions.toml`
- 应用内名称：波形 Wavex
- 包名：`com.wavex.agent`（applicationId 同）

## 构建

```bash
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK（需配置签名）
```

签名密钥与密码存放于 `keystore/`、`keystore.properties`（已加入 `.gitignore`，不入库）。

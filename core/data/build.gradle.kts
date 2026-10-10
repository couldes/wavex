plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.wavex.agent.core.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        // JVM 单测触碰 android.util.Log / android.os.Environment 等桩类（搬移的 SessionStore
        // 失败路径、目录解析探测），桩方法返回默认值而非抛 "not mocked"
        //（与 app 模块 unitTests.isReturnDefaultValues 一致）。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.org.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

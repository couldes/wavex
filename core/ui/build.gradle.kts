plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wavex.agent.core.ui"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        // JVM 单测中 android.jar 方法（LruCache/MathRenderer 缓存、Uri.parse）返回默认值而非抛 not-mocked；
        // （与 app 模块 unitTests.isReturnDefaultValues 一致）
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation("androidx.compose.material:material-icons-extended")
    // LaTeX 数学公式离线渲染（JLatexMath 的 Android Canvas 移植）。
    // 仅用其传递依赖 ru.noties:jlatexmath-android 的渲染能力，
    // Markwon 自身的 Markdown 解析不用（本项目有自研 MarkdownText）
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

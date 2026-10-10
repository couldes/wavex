plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wavex.agent.feature.usage"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        // JVM 单测中 android.jar 方法返回默认值而非抛 not-mocked
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(platform(libs.androidx.compose.bom))
    // 统计页需要图表/表格组件栈
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.foundation)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

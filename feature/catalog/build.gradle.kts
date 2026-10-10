plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wavex.agent.feature.catalog"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        // JVM 单测中 android.jar 方法返回默认值而非抛 not-mocked
        //（与 app/core:data/core:ui 模块一致）
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(platform(libs.androidx.compose.bom))
    // 设置/模型页需要完整组件栈（Material3/图标/foundation）与 activity-compose（权限/SAF launcher）
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.foundation)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

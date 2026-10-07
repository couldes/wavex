import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 正式版签名（可选）：keystore.properties 不存在时各变体回退默认签名，
// 不影响他人克隆后直接构建
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.wavex.agent"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.wavex.agent"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "0.15.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 正式签名（可选）：keystore.properties 不存在时不创建，
    // release/debug 各自回退默认签名，不影响克隆后直接构建
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            // debug 变体也用正式签名：与 release 同签名，可互相覆盖安装，
            // 切换变体时应用数据（对话/服务商配置）不丢
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        release {
            // 开启 R8：裁剪未用代码 —— material-icons-extended 携带数千个未用图标类，
            // 只有 R8 能裁掉（资源收缩只管 res/，对 Kotlin 类无效），
            // release 包体积因此显著小于 debug
            optimization {
                enable = true
            }
            // 优先用正式签名（keystore.properties）；未配置时回退 debug 签名
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // src/production 附加源集：createAppContainer 工厂（debug/release 共用；
    // 为 main 的 ModelService 注入缝隙提供默认实现，不影响正常构建）
    sourceSets {
        getByName("debug").kotlin.directories.add("src/production/java")
        getByName("release").kotlin.directories.add("src/production/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // JVM 单测中 android.jar 方法（如 Uri.parse）返回默认值而非抛 not-mocked；
        // 引擎测试的假 loader 不触碰 uri，仅构造 ChatAttachment 需要
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // LaTeX 数学公式离线渲染（JLatexMath 的 Android Canvas 移植）。
    // 仅用其传递依赖 ru.noties:jlatexmath-android 的渲染能力，
    // Markwon 自身的 Markdown 解析不用（本项目有自研 MarkdownText）
    implementation("io.noties.markwon:ext-latex:4.6.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // SVG 解码：模型返回的内联 SVG 以 .svg 附件落盘，缩略图/查看器渲染必需
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation(libs.androidx.compose.material3)
    // material-icons-extended 体积较大（几 MB），只用其中少量图标；
    // shrinkResources 会去除未引用资源，release 包不会携带全部图标
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
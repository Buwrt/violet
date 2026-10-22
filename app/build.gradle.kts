plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.violet.box"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.violet.box"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 代码收缩 + 资源收缩：APK 13.1MB -> 3.4MB
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 离线重打包：无原作者签名密钥，改用 debug 签名以便安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    packaging {
        resources {
            // commons-codec 传递依赖带入的语音匹配数据，项目未使用
            excludes += "org/apache/commons/codec/language/**"
            // kotlinx-coroutines 调试探针，release 无用
            excludes += "DebugProbesKt.bin"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.activity.compose)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // YunX 用到 Material3 Expressive（1.5.0-alpha18 才有，BOM 里的稳定版不含）
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.backdrop)
    implementation(libs.capsule)
    implementation(libs.kotlinx.coroutines.android)

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("commons-io:commons-io:2.18.0")

    // Shizuku：以 shell 权限执行传感器限制命令（安全页摇一摇广告防护）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // ===== 云析 YunX（AGPL-3.0）=====
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.compose.material.icons.extended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

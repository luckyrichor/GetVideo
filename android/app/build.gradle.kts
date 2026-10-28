import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 固定发布证书：先找 ~/.android-keys/getvideo.properties，再找项目根的 keystore.properties。
// 找不到就**直接构建失败**——绝不能悄悄用调试证书签一个 release 包出去，
// 否则装到手机上又会撞上「签名不一致，无法覆盖安装」。
// 新机器/新 Agent 环境先把密钥装回来：
//   ./scripts/setup-release-key.sh          # 从服务器备份拉取
// 或手动：scp tx:~/.app-keys/getvideo* ~/.android-keys/ && chmod 600 ~/.android-keys/*
val releaseSigning: Properties? = sequenceOf(
    File(System.getProperty("user.home"), ".android-keys/getvideo.properties"),
    rootProject.file("keystore.properties"),
).mapNotNull { file ->
    file.takeIf { it.isFile }?.let { props -> Properties().apply { props.inputStream().use { load(it) } } }
}.firstOrNull { props ->
    props.getProperty("storeFile")?.let { File(it).isFile } == true
}

// 只在"真的要产出 release 包"时才强制要密钥。
// 配置阶段会跑在任何任务之前，所以不能无条件 throw——否则 CI 里的 assembleDebug / testDebugUnitTest
// （CI 上按设计没有密钥）也会被一起打断。IDE 同步时 taskNames 为空，同样放行。
val releaseTaskRequested: Boolean = gradle.startParameter.taskNames.any { task ->
    val name = task.substringAfterLast(':')
    name.contains("assembleRelease", ignoreCase = true) ||
        name.contains("bundleRelease", ignoreCase = true) ||
        name.equals("build", ignoreCase = true) ||
        name.equals("assemble", ignoreCase = true)
}

if (releaseSigning == null && releaseTaskRequested && providers.gradleProperty("allowDebugSignRelease").orNull != "true") {
    throw GradleException(
        """
        找不到固定的发布密钥，已中止 release 构建。

        请把项目密钥安装到本机后再构建：
            ./scripts/setup-release-key.sh
        或手工执行：
            mkdir -p ~/.android-keys && chmod 700 ~/.android-keys
            scp tx:~/.app-keys/getvideo-release.jks tx:~/.app-keys/getvideo.properties ~/.android-keys/
            chmod 600 ~/.android-keys/*

        详见 docs/android-release-signing.md。
        确实只是本机临时调试、不打算发布时，才加 -PallowDebugSignRelease=true 绕过。
        """.trimIndent()
    )
}

android {
    namespace = "com.getvideo.app"
    compileSdk = 34

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = File(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.getvideo.app"
        minSdk = 26          // Media3 和后台播放在 8.0 以下坑太多
        testInstrumentationRunner = providers.gradleProperty("getvideoTestRunner").getOrElse("com.getvideo.app.BatchDeleteInstrumentation")
        targetSdk = 34
        versionCode = 9
        versionName = "0.3.2"
        manifestPlaceholders["appLabel"] = "@string/app_name"

    }

    // 预览版可与正式版并存：包名加 .preview，桌面名称带「新版」，方便在保留旧数据的前提下试新版本
    val previewBuild = providers.gradleProperty("getvideoPreview").orNull == "true"

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (previewBuild) {
                applicationIdSuffix = ".preview"
                manifestPlaceholders["appLabel"] = "GetVideo 新版"
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseSigning != null) "release" else "debug")
            if (previewBuild) {
                applicationIdSuffix = ".preview"
                manifestPlaceholders["appLabel"] = "GetVideo 新版"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // 本地库
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 网络
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // 播放
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-transformer:1.3.1")

    // 后台同步
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // 图片（封面、二维码）
    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}

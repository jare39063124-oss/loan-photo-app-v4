import java.util.Properties
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.X509Certificate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// 读取 local.properties（包含 OpenRouter API key，不明文提交到 git）
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        load(FileInputStream(localPropsFile))
    }
}

/** SHA-256 哈希，返回小写 hex 字符串。空输入返回空串。 */
fun sha256(input: String): String {
    if (input.isEmpty()) return ""
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/** 从 keystore 读取签名证书的 SHA-256（小写 hex）。keystore 不存在或密码空时返回空串（兼容无 keystore 的 debug 构建）。 */
fun signingCertSha256(keystoreFile: File, storePass: String, alias: String): String {
    if (!keystoreFile.exists() || storePass.isEmpty()) return ""
    return try {
        val ks = KeyStore.getInstance("JKS")
        FileInputStream(keystoreFile).use { fis ->
            ks.load(fis, storePass.toCharArray())
        }
        val cert = ks.getCertificate(alias) ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        digest.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }
}

android {
    namespace = "com.banktool.loanphoto"
    compileSdk = 35

    val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD", "")
    val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD", "")
    val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS", "loanphoto")
    val releaseKeystoreFile = rootProject.file("loanphoto-release.jks")
    val expectedSigningHash = signingCertSha256(releaseKeystoreFile, releaseStorePassword, releaseKeyAlias)

    defaultConfig {
        applicationId = "com.banktool.loanphoto"
        minSdk = 26  // Apache POI 5.x requires API 26 (MethodHandle); all target devices are API 30+
        targetSdk = 35
        versionCode = 19
        versionName = "4.1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 注入 OpenRouter API key（不明文出现在源代码中，从 local.properties 读取）
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${localProperties.getProperty("openrouter.api.key", "")}\"")
        buildConfigField("String", "OPENROUTER_API_URL", "\"https://openrouter.ai/api/v1\"")
        buildConfigField("String", "OPENROUTER_MODEL", "\"nvidia/nemotron-3-ultra-550b-a55b:free\"")
        buildConfigField("String", "EXPECTED_SIGNING_HASH", "\"$expectedSigningHash\"")
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            // 用 release keystore 签名：debug 默认用 debug 签名，会与 BuildConfig.EXPECTED_SIGNING_HASH
            // （release 证书哈希）不匹配而被 SecurityChecker 拦在 LockScreen。release 签名后即可正常启动。
            signingConfig = signingConfigs.getByName("release")
        }
        // plain: 紧急可用普通版 —— 关闭 R8/混淆（不抗逆向），用 release keystore 签名
        // 使 SecurityChecker 签名校验通过、不被 LockScreen 拦截；Apache POI 无 R8 干扰正常工作
        create("plain") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            matchingFallbacks += listOf("release")
        }
    }

    // 双版本构建：trial（体验版，带授权/到期限制） / full（完整版）
    // LICENSED_DEVICE_ID_HASH：授权设备 Android ID 的 SHA-256（小写 hex），不明文存储
    flavorDimensions += "license"
    productFlavors {
        create("trial") {
            dimension = "license"
            buildConfigField("boolean", "IS_TRIAL", "true")
            buildConfigField("String", "EXPIRY_DATE", "\"2026-12-31\"")
            buildConfigField("String", "LICENSED_DEVICE_ID_HASH", "\"${sha256(localProperties.getProperty("LICENSED_DEVICE_ID", ""))}\"")
        }
        create("full") {
            dimension = "license"
            buildConfigField("boolean", "IS_TRIAL", "false")
            buildConfigField("String", "EXPIRY_DATE", "\"2099-12-31\"")
            buildConfigField("String", "LICENSED_DEVICE_ID_HASH", "\"${sha256("")}\"")
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

    // Apache POI 需要排除部分冲突资源
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Coil
    implementation(libs.coil.compose)

    // Retrofit + OkHttp + Moshi
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // Apache POI（Android 兼容性需要 aalto-xml 替代 javax.xml.stream）
    implementation(libs.poi.main)
    implementation(libs.poi.ooxml)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Location
    implementation(libs.play.services.location)

    // Timber
    implementation(libs.timber)

    // ExifInterface
    implementation(libs.androidx.exifinterface)

    // DocumentFile (SAF)
    implementation(libs.androidx.documentfile)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

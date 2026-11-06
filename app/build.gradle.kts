import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * release 签名配置。
 *
 * 为什么这么写：私钥和密码都放在 `keys/`（已在 .gitignore 里），工程里不出现任何明文口令。
 * 文件不存在时（别人克隆你的仓库）不会报错，只是打出来的 release 包没有签名 —— 这是刻意的：
 * 开源仓库里不能有「因为缺一个文件就整个构建失败」的硬依赖。
 */
val keyProps = Properties().apply {
    val f = rootProject.file("keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keyProps.getProperty("storeFile") != null

android {
    namespace = "com.privacyscope.local"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.privacyscope.local"
        minSdk = 24
        targetSdk = 36
        versionCode = 26
        versionName = "0.9.14"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        localeFilters += listOf("zh", "en")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.flyfishxu:kadb-android:2.1.1")

    // 单元测试跑在 JVM 上：只测 core/Pure.kt 这类不碰 Android API 的纯逻辑，
    // 不用真机、不用 adb，`gradle test` 一条命令就能验。
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}





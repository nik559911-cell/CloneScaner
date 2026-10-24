import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.skaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.skaner"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.7.2"
    }

    // Постоянная подпись: без неё каждая сборка в CI подписывается новым ключом
    // и обновление поверх старой версии невозможно. Репозиторий приватный, приложение — для себя и коллег.
    val signing = Properties().apply { file("signing.properties").inputStream().use { load(it) } }
    signingConfigs {
        create("skaner") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    // Отдельный APK на архитектуру: OpenCV — основная часть веса, ставить обе незачем.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("skaner")
        }
        release {
            signingConfig = signingConfigs.getByName("skaner")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Имя файла с версией: app-arm64-v8a-release_0.6.0.apk
    applicationVariants.all {
        val version = versionName
        outputs.all {
            val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = out.outputFileName.removeSuffix(".apk") + "_" + version + ".apk"
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
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.exifinterface)
    implementation(libs.opencv)
    implementation(libs.reorderable)
}

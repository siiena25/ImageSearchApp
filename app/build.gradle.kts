plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "uz.imagesearch.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.imagesearch.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        // ONNX-модели уже сжатые — не пытаться компрессить ещё раз
        jniLibs.useLegacyPackaging = false
        resources.excludes += "**/*.onnx"
    }
    androidResources {
        noCompress += listOf("onnx", "bin")
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:results"))
    implementation(project(":core:ui"))
    implementation(project(":core:catalog"))
    implementation(project(":core:ml"))
    implementation(project(":core:retrieval"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)
}

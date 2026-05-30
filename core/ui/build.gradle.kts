plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "uz.imagesearch.core.ui"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform(libs.compose.bom))
    api(libs.compose.material3)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.material.icons)
}


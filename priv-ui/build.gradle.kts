plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "priv.kit.ui"

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":priv-runtime"))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.runtime)
    api(libs.androidx.compose.ui)
}

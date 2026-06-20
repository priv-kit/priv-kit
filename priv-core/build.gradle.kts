plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.core"

    buildFeatures {
        aidl = true
    }
}

dependencies {
    testImplementation(libs.junit)
}

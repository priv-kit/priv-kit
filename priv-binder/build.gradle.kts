plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.binder"

    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":priv-core"))
    testImplementation(libs.junit)
}

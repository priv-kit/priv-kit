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
    compileOnly(project(":hidden-api"))
    testImplementation(libs.junit)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.remap)
}

android {
    namespace = "priv.kit.external.shizuku"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":priv-core"))
    remapApi(project(":hidden-api"))
}

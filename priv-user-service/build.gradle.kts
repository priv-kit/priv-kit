plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.remap)
}

android {
    namespace = "priv.kit.userservice"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":priv-core"))
    api(project(":priv-binder"))
    compileOnly(project(":hidden-api"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

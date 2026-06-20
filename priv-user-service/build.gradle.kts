plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.userservice"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":priv-core"))
    api(project(":priv-binder"))
    compileOnly(project(":hidden-api"))
    testImplementation(libs.junit)
}

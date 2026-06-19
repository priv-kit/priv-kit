plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.server"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":priv-core"))
    api(project(":priv-binder"))
    api(project(":priv-user-service"))
    testImplementation(libs.junit)
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.runtime"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
    implementation(project(":priv-adb"))
    implementation(project(":priv-root"))
    implementation(project(":priv-delegate"))
    runtimeOnly(project(":priv-server"))
    testImplementation(libs.junit)
}

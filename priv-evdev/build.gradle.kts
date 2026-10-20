plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.remap)
}

android {
    namespace = "priv.kit.evdev"
    buildFeatures.aidl = true
    defaultConfig {
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=none"
    }
    externalNativeBuild.cmake.path = file("src/main/cpp/CMakeLists.txt")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":priv-shared"))
    remapApi(project(":hidden-api"))
    compileOnly(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

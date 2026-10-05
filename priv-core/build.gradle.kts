plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.remap)
}

android {
    namespace = "priv.kit.core"

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        aidl = true
    }
}

configurations.named("testCompileOnly") {
    extendsFrom(configurations.named("compileOnly").get())
}

dependencies {
    implementation(project(":priv-adb-crypto"))
    implementation(project(":priv-shared"))
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.core)
    testImplementation(libs.kotlinx.serialization.json)
    compileOnly(project(":hidden-api"))
    compileOnly(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

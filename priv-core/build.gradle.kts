plugins {
    alias(libs.plugins.android.library)
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

dependencies {
    implementation(project(":priv-adb-crypto"))
    implementation(project(":priv-shared"))
    api(libs.kotlinx.coroutines.core)
    compileOnly(project(":hidden-api"))
    compileOnly(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

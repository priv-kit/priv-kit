plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.remap)
}

android {
    namespace = "priv.kit"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

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
    compileOnly(project(":hidden-api"))
    compileOnly(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

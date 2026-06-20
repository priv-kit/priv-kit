plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.adb"

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    api(project(":priv-core"))
    compileOnly(project(":hidden-api"))
    implementation(project(":priv-bc"))
    implementation(libs.boringssl)
    implementation(libs.libcxx)
    testImplementation(libs.junit)
}

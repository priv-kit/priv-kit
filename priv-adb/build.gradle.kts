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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    api(project(":priv-core"))
    compileOnly(project(":hidden-api"))
    implementation(project(":priv-adb-crypto"))
    testImplementation(libs.junit)
}

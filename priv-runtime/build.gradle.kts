plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.runtime"

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
    api(project(":priv-adb"))
    runtimeOnly(project(":priv-server"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

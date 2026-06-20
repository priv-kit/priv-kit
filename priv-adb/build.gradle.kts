plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.adb"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":priv-core"))
    compileOnly(project(":hidden-api"))
    implementation(libs.boringssl)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.libcxx)
    testImplementation(libs.junit)
}

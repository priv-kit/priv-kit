import com.android.build.api.variant.ResValue

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "priv.kit.sample"

    defaultConfig {
        applicationId = "priv.kit.sample"
        versionCode = 1
        versionName = project.version.toString()
    }

    flavorDimensions += "nativePackaging"

    productFlavors {
        create("legacy") {
            dimension = "nativePackaging"
            isDefault = true
        }

        create("api29") {
            dimension = "nativePackaging"
            applicationIdSuffix = ".api29"
            minSdk = 29
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        resValues = true
    }
}

androidComponents.onVariants { variant ->
    val nativePackagingFlavor =
        variant.productFlavors.single { it.first == "nativePackaging" }.second
    val baseAppLabel = when (nativePackagingFlavor) {
        "legacy" -> "Priv"
        "api29" -> "PrivQ"
        else -> error("Unknown native packaging flavor: $nativePackagingFlavor")
    }
    val appLabel = if (variant.buildType == "debug") "$baseAppLabel-Dev" else baseAppLabel
    variant.resValues.put(
        variant.makeResValueKey("string", "app_name"),
        ResValue(appLabel),
    )
    if (nativePackagingFlavor == "legacy") {
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
        variant.packaging.jniLibs.useLegacyPackagingFromBundle.set(true)
    }
}

dependencies {
    compileOnly(project(":hidden-api"))
    implementation(project(":priv-core"))
    implementation(project(":priv-ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.hiddenapibypass)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}

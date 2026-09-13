import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "priv.kit.ui"
        androidResources.enable = true
        withHostTest { isIncludeAndroidResources = true }
        localDependencySelection { selectBuildTypeFrom.set(listOf("debug", "release")) }
    }
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.multiplatform.foundation)
            api(libs.compose.multiplatform.material3)
            api(libs.compose.multiplatform.runtime)
            api(libs.compose.multiplatform.ui)
            implementation(libs.compose.multiplatform.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(compose.desktop.currentOs)
        }
        androidMain.dependencies {
            api(project(":priv-core"))
            implementation(project(":priv-shared"))
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            api(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            api(libs.androidx.lifecycle.service)
            compileOnly(libs.androidx.annotation)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.robolectric)
        }
    }
}

compose.resources {
    packageOfResClass = "priv.kit.ui.resources"
}

// The same XML strings serve Compose resources and Android's synchronous notification text.
androidComponents {
    onVariants { variant ->
        variant.sources.res?.addStaticSourceDirectory("src/commonMain/composeResources")
    }
}

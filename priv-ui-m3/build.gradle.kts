import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

val skipAndroid = providers.environmentVariable("PRIV_KIT_SKIP_ANDROID").isPresent
if (!skipAndroid) {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
}

kotlin {
    targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
        namespace = "priv.kit.ui.m3"
        androidResources.enable = true
        withHostTest { isIncludeAndroidResources = true }
        localDependencySelection { selectBuildTypeFrom.set(listOf("debug", "release")) }
    }
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    compilerOptions { optIn.add("priv.kit.ui.ExperimentalPrivilegeUiApi") }

    sourceSets {
        commonMain.dependencies {
            api(project(":priv-ui"))
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.compose.multiplatform.material3)
            implementation(libs.compose.multiplatform.resources)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(compose.desktop.currentOs)
        }
        if (!skipAndroid) {
            getByName("androidMain").dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
            }
            getByName("androidHostTest").dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
            }
        }
    }
}


compose.resources {
    packageOfResClass = "priv.kit.ui.m3.resources"
    publicResClass = true
}

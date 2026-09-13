import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm("desktop")
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("priv-playground")
        useEsModules()
        browser()
        binaries.executable()
        generateTypeScriptDefinitions()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":priv-ui"))
            implementation(libs.compose.multiplatform.resources)
        }
        getByName("desktopMain").dependencies { implementation(compose.desktop.currentOs) }
        getByName("desktopTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.compose.multiplatform.ui.test)
        }
    }
}

compose.desktop {
    application { mainClass = "priv.kit.playground.MainKt" }
}

compose.resources { packageOfResClass = "priv.kit.playground.resources" }

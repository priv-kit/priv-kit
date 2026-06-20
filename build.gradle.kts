plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.remap) apply false
}

allprojects {
    group = "io.github.priv-kit"
    version = "0.1.0-SNAPSHOT"
}

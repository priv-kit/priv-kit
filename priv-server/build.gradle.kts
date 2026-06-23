plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.remap)
}

android {
    namespace = "priv.kit.server"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":priv-core"))
    compileOnly(project(":hidden-api"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

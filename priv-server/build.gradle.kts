plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.server"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":priv-core"))
    api(project(":priv-binder"))
    api(project(":priv-user-service"))
    compileOnly(project(":hidden-api"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

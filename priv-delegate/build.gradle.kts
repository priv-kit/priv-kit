plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.delegate"
}

dependencies {
    api(project(":priv-core"))
    testImplementation(libs.junit)
}

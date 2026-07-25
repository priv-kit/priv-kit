plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.shared"
}

dependencies {
    compileOnly(project(":hidden-api"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

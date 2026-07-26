plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.shared"
}

dependencies {
    compileOnly(project(":hidden-api"))
    compileOnly(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

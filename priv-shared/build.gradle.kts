plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.shared"
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

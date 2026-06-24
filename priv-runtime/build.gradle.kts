plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.runtime"
}

dependencies {
    api(project(":priv-core"))
    implementation(project(":priv-adb"))
    implementation(project(":priv-root"))
    runtimeOnly(project(":priv-server"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

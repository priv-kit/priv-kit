plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.runtime"
}

dependencies {
    api(project(":priv-core"))
    api(project(":priv-binder"))
    api(project(":priv-user-service"))
    implementation(project(":priv-adb"))
    implementation(project(":priv-root"))
    implementation(project(":priv-delegate"))
    runtimeOnly(project(":priv-server"))
    testImplementation(libs.junit)
}

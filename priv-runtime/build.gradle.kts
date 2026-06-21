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
    api(project(":priv-delegate"))
    implementation(project(":priv-adb"))
    implementation(project(":priv-root"))
    runtimeOnly(project(":priv-server"))
    testImplementation(libs.junit)
}

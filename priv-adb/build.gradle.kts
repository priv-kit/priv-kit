plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.adb"
}

dependencies {
    api(project(":priv-core"))
    compileOnly(project(":hidden-api"))
    implementation(project(":priv-adb-crypto"))
    testImplementation(libs.junit)
}

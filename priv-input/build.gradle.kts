plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.remap)
}

android {
    namespace = "priv.kit.input"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":priv-shared"))
    remapApi(project(":hidden-api"))
    compileOnly(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

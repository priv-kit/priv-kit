plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.hidden.api"
}

dependencies {
    compileOnly(libs.remap.annotation)
    annotationProcessor(libs.remap.processor)
}

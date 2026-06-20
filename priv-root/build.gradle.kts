plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "priv.kit.root"
}

dependencies {
    api(project(":priv-core"))
}

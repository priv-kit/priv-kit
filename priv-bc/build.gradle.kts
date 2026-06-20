plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.bouncycastle.bcpkix)
    testImplementation(libs.junit)
}

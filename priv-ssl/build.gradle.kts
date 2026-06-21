plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
}

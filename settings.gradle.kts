pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Kotlin/Wasm adds repositories for its Node.js and Binaryen build tools.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "priv-kit"

include(
    ":priv-shared",
    ":priv-core",
    ":priv-adb-crypto",
    ":priv-ui",
    ":priv-playground",
    ":priv-sample",
    ":hidden-api",
)

pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeVersionByRegex(".*", ".*", ".*-SNAPSHOT") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Kotlin/Wasm adds repositories for its Node.js and Binaryen build tools.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeVersionByRegex(".*", ".*", ".*-SNAPSHOT") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "priv-kit"

include(":priv-ui", ":priv-playground")

if (!providers.environmentVariable("PRIV_KIT_SKIP_ANDROID").isPresent) {
    include(
        ":priv-shared",
        ":priv-core",
        ":priv-input",
        ":priv-evdev",
        ":priv-adb-crypto",
        ":priv-sample",
        ":hidden-api",
    )
}

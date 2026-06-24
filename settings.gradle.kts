pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "priv-kit"

include(
    ":priv-core",
    ":priv-runtime",
    ":priv-server",
    ":priv-adb-crypto",
    ":priv-adb",
    ":priv-ui",
    ":priv-sample",
    ":hidden-api",
)

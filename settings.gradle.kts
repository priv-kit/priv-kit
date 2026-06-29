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
    ":priv-runtime",
    ":priv-adb-crypto",
    ":priv-ui",
    ":priv-sample",
    ":hidden-api",
)

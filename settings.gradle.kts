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
    ":priv-bc",
    ":priv-ssl",
    ":priv-adb",
    ":priv-ui",
    ":priv-sample",
    ":hidden-api",
)

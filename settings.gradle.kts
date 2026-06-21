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
    ":priv-binder",
    ":priv-user-service",
    ":priv-bc",
    ":priv-ssl",
    ":priv-adb",
    ":priv-root",
    ":priv-delegate",
    ":priv-ui",
    ":priv-sample",
    ":hidden-api",
)

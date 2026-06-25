import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryPlugin
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.remap) apply false
}

private object Cfg {
    const val compileSdk = 37
    const val buildToolsVersion = "37.0.0"
    const val ndkVersion = "30.0.14904198"
    const val cmakeVersion = "4.1.2"
    const val minSdk = 26
    val javaTargetVersion = JavaVersion.VERSION_11
    val kotlinJvmTarget = JvmTarget.fromTarget(javaTargetVersion.majorVersion)
    val kotlinLanguageVersion = KotlinVersion.KOTLIN_2_2
}

private val unpublishedModuleNames = setOf(
    "hidden-api",
    "priv-sample",
)

allprojects {
    group = "io.github.priv-kit"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    if (name !in unpublishedModuleNames) {
        pluginManager.apply("com.vanniktech.maven.publish")
    }

    fun configureExplicitApi() {
        if (name.endsWith("-sample")) {
            return
        }
        extensions.configure(KotlinBaseExtension::class.java) {
            explicitApi()
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(Cfg.kotlinJvmTarget)
            languageVersion.set(Cfg.kotlinLanguageVersion)
            apiVersion.set(Cfg.kotlinLanguageVersion)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        configureExplicitApi()

        extensions.getByType(JavaPluginExtension::class.java).apply {
            sourceCompatibility = Cfg.javaTargetVersion
            targetCompatibility = Cfg.javaTargetVersion
        }
    }

    plugins.withType<AppPlugin> {
        configureExplicitApi()

        extensions.getByType(ApplicationExtension::class.java).apply {
            compileSdk = Cfg.compileSdk
            buildToolsVersion = Cfg.buildToolsVersion
            ndkVersion = Cfg.ndkVersion

            defaultConfig {
                minSdk = Cfg.minSdk
                targetSdk = Cfg.compileSdk
            }

            compileOptions {
                sourceCompatibility = Cfg.javaTargetVersion
                targetCompatibility = Cfg.javaTargetVersion
            }

            externalNativeBuild {
                cmake {
                    version = Cfg.cmakeVersion
                }
            }
        }
    }

    plugins.withType<LibraryPlugin> {
        configureExplicitApi()

        extensions.getByType(LibraryExtension::class.java).apply {
            compileSdk = Cfg.compileSdk
            buildToolsVersion = Cfg.buildToolsVersion
            ndkVersion = Cfg.ndkVersion

            defaultConfig {
                minSdk = Cfg.minSdk
            }

            compileOptions {
                sourceCompatibility = Cfg.javaTargetVersion
                targetCompatibility = Cfg.javaTargetVersion
            }

            externalNativeBuild {
                cmake {
                    version = Cfg.cmakeVersion
                }
            }
        }
    }

    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        configure<MavenPublishBaseExtension> {
            coordinates(project.group.toString(), project.name, project.version.toString())

            if (properties.contains("signing.keyId")) {
                publishToMavenCentral()
                signAllPublications()
            }

            val repoUrl = "https://github.com/priv-kit/priv-kit"
            pom {
                name.set("Priv Kit")
                description.set("Self-managed privileged runtime for Android apps.")
                url.set(repoUrl)
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("lisonge")
                        email.set("i@songe.li")
                        url.set("https://github.com/lisonge")
                    }
                }
                scm {
                    url.set(repoUrl)
                }
            }
        }
    }
}

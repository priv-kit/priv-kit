import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
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
}

allprojects {
    group = "io.github.priv-kit"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(Cfg.kotlinJvmTarget)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.getByType(JavaPluginExtension::class.java).apply {
            sourceCompatibility = Cfg.javaTargetVersion
            targetCompatibility = Cfg.javaTargetVersion
        }
    }

    plugins.withType<AppPlugin> {
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
}

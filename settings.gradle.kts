import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val localProperties = Properties().apply {
    val propertiesFile = file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackagesCoreUI"
            url = uri("https://maven.pkg.github.com/androidexpert35/CoreUI")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: localProperties.getProperty("gpr.user")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: localProperties.getProperty("gpr.key")
            }
        }
    }
}

rootProject.name = "Audiophile Music Player"
include(":app")

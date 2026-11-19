pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url = uri(providers.gradleProperty("fipeMavenRepository")
                        .getOrElse(settingsDir.parentFile.resolve("build/maven-repository").absolutePath))
                }
            }
            filter { includeModule("io.github.fipe-kt", "fipe-state") }
        }
        mavenCentral()
    }
}

rootProject.name = "fipe-state-consumer"

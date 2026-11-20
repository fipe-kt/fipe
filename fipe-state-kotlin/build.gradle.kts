import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    `java-library`
    `maven-publish`
}

group = "io.github.fipe-kt"
version = "1.0.0-beta.1"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.withType<Jar>().configureEach {
    from("../LICENSE") { into("META-INF") }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("fipeState") {
            artifactId = "fipe-state"
            from(components["java"])
            pom {
                name.set("Fipe State for Kotlin")
                description.set("Task, stage, workflow, and session lifetimes for Kotlin.")
                url.set("https://github.com/fipe-kt/fipe")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                scm {
                    url.set("https://github.com/fipe-kt/fipe")
                    connection.set("scm:git:https://github.com/fipe-kt/fipe.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(providers.gradleProperty("fipeMavenRepository")
                .getOrElse(layout.buildDirectory.dir("maven-repository").get().asFile.absolutePath))
        }
    }
}

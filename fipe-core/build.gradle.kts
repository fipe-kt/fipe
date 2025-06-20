import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}

android {
    namespace = "fipe.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                name = findProperty("POM_NAME")?.toString()
                description = findProperty("POM_DESCRIPTION")?.toString()
                url = findProperty("POM_URL")?.toString()
                licenses {
                    license {
                        name = findProperty("POM_LICENSE_NAME")?.toString()
                        url = findProperty("POM_LICENSE_URL")?.toString()
                    }
                }
                developers {
                    developer {
                        id = findProperty("POM_DEVELOPER_ID")?.toString()
                        name = findProperty("POM_DEVELOPER_NAME")?.toString()
                    }
                }
                scm {
                    url = findProperty("POM_URL") as String
                    connection = "scm:git:${findProperty("POM_URL") as String}.git"
                    developerConnection = "scm:git:${findProperty("POM_URL") as String}.git"
                }
            }
        }
    }
}
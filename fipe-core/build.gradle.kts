import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.maven.publish)
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
        publishLibraryVariants("release")
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

group = findProperty("POM_GROUP_ID").toString()
version = findProperty("POM_VERSION").toString()

mavenPublishing {
    coordinates(
        groupId = findProperty("POM_GROUP_ID").toString(),
        artifactId = "fipe-core",
        version = findProperty("POM_VERSION").toString()
    )
    pom {
        name.set(findProperty("POM_NAME").toString())
        description.set(findProperty("POM_DESCRIPTION").toString())
        inceptionYear.set(findProperty("POM_INCEPTION_YEAR").toString())
        url.set(findProperty("POM_URL").toString())

        licenses {
            license {
                name.set(findProperty("POM_LICENSE_NAME").toString())
                url.set(findProperty("POM_LICENSE_URL").toString())
            }
        }
        developers {
            developer {
                id.set(findProperty("POM_DEVELOPER_ID").toString())
                name.set(findProperty("POM_DEVELOPER_NAME").toString())
                email.set(findProperty("POM_DEVELOPER_EMAIL").toString())
            }
        }
        scm {
            connection.set(findProperty("POM_SCM_CONNECTION").toString())
            developerConnection.set(findProperty("POM_SCM_DEVELOPER_CONNECTION").toString())
            url.set(findProperty("POM_SCM_URL").toString())
        }
    }

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
}

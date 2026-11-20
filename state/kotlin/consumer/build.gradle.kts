import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
}

dependencies {
    implementation("io.github.fipe-kt:fipe-state:1.0.0-beta.2-SNAPSHOT")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        allWarningsAsErrors.set(true)
    }
}

val checks = listOf("TaskChecks", "StageChecks", "WorkflowChecks", "ProductionChecks", "SecurityChecks")
val verifyInstalled = tasks.register("verifyInstalled")
val cases = checks.map { name ->
    tasks.register<JavaExec>("run$name") {
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("fipe.state.checks.${name}Kt")
    }
}
cases.zipWithNext().forEach { (earlier, later) ->
    later.configure { mustRunAfter(earlier) }
}
verifyInstalled.configure { dependsOn(cases) }

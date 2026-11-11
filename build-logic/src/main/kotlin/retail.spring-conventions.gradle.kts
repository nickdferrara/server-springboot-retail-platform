// Spring Boot application: dependency versions come from the Spring Boot and Spring Modulith platforms.

import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("retail.kotlin-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(platform(libs.findLibrary("spring-boot-bom").get()))
    "implementation"(platform(libs.findLibrary("spring-modulith-bom").get()))
    "developmentOnly"(platform(libs.findLibrary("spring-boot-bom").get()))
    "implementation"(libs.findLibrary("kotlin-reflect").get())
}

// One artifact with a stable name: the executable jar.
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("retail-platform.jar")
}

tasks.named<Jar>("jar") {
    enabled = false
}

// Run from the repository root so relative paths such as docker/docker-compose.yml resolve.
tasks.named<BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}

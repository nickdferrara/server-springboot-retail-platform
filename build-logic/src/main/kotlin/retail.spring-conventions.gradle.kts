// Spring Boot application: dependency versions come from the Spring Boot and Spring Modulith platforms.

plugins {
    id("retail.kotlin-conventions")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(platform(libs.findLibrary("spring-boot-bom").get()))
    "implementation"(platform(libs.findLibrary("spring-modulith-bom").get()))
    "implementation"(libs.findLibrary("kotlin-reflect").get())
}

// Kotlin on the JVM: one toolchain for every module, warnings fail the build.

plugins {
    kotlin("jvm")
}

val libs = the<VersionCatalogsExtension>().named("libs")

kotlin {
    jvmToolchain(libs.findVersion("java").get().requiredVersion.toInt())
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    "implementation"(platform(libs.findLibrary("kotlin-bom").get()))
}

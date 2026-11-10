// Static analysis: Detekt and ktlint run as part of `check` and fail on any finding.

import dev.detekt.gradle.Detekt

plugins {
    id("dev.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = the<VersionCatalogsExtension>().named("libs")

detekt {
    toolVersion = libs.findVersion("detekt").get().requiredVersion
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
    ignoreFailures = false
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
}

ktlint {
    version.set(libs.findVersion("ktlint").get().requiredVersion)
    ignoreFailures.set(false)
}

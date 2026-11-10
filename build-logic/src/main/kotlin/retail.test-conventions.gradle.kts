// Testing: JUnit Platform, Kotest assertions, MockK, and the Kover coverage gate.

plugins {
    id("retail.kotlin-conventions")
    id("org.jetbrains.kotlinx.kover")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("kotest-assertions-core").get())
    "testImplementation"(libs.findLibrary("mockk").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

kover {
    reports {
        // The coverage gate applies to business logic: the domain and application layers of every module.
        filters {
            includes {
                classes("com.retailplatform.*.domain.*", "com.retailplatform.*.application.*")
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

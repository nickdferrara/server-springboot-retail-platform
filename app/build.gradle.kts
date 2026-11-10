plugins {
    id("retail.spring-conventions")
    id("retail.quality-conventions")
    id("retail.test-conventions")
}

group = "com.retailplatform"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.modulith.starter.core)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.modulith.starter.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

springBoot {
    mainClass = "com.retailplatform.RetailApplicationKt"
}

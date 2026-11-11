package com.retailplatform

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RetailApplicationTest(
    @Autowired private val environment: Environment,
) {
    @Test
    fun `application context starts with the local profile by default`() {
        environment.matchesProfiles("local") shouldBe true
    }

    @Test
    fun `virtual threads are enabled`() {
        environment.getProperty("spring.threads.virtual.enabled") shouldBe "true"
    }
}

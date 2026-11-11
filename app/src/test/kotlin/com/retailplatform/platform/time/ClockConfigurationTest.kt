package com.retailplatform.platform.time

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset

class ClockConfigurationTest {
    @Test
    fun `the clock runs in the company time zone`() {
        val chicago = ZoneId.of("America/Chicago")

        ClockConfiguration().clock(CompanyProperties(timeZone = chicago)).zone shouldBe chicago
    }

    @Test
    fun `the company time zone defaults to UTC`() {
        CompanyProperties().timeZone shouldBe ZoneOffset.UTC
    }
}

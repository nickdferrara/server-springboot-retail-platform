package com.retailplatform.pricing.domain

import com.retailplatform.shared.StoreId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock

class PriceZoneCodeTest {
    @Test
    fun `accepts upper-case codes`() {
        PriceZoneCode.of("ZONE_2").value shouldBe "ZONE_2"
        PriceZoneCode.of("DEFAULT") shouldBe PriceZoneCode.DEFAULT
    }

    @Test
    fun `rejects codes that are empty, lower-case, too long, or start with a digit`() {
        listOf("", "zone", "2ZONE", "Z".repeat(21)).forEach { text ->
            shouldThrow<IllegalArgumentException> { PriceZoneCode.of(text) }
        }
    }

    @Test
    fun `a new store starts in the default zone`() {
        val storeId = StoreId.generate(Clock.systemUTC())

        StorePriceZone.forNewStore(storeId) shouldBe StorePriceZone(storeId, PriceZoneCode.DEFAULT)
    }
}

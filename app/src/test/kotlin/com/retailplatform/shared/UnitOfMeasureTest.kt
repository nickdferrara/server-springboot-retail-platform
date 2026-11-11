package com.retailplatform.shared

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UnitOfMeasureTest {
    @Test
    fun `accepts short upper-case codes`() {
        UnitOfMeasure.of("EA") shouldBe UnitOfMeasure.EACH
        UnitOfMeasure.of("PK12").code shouldBe "PK12"
        UnitOfMeasure.GALLON.toString() shouldBe "GAL"
    }

    @Test
    fun `rejects codes that are empty, lower-case, too long, or start with a digit`() {
        listOf("", "ea", "ELEVENCHARS", "1EA", "E A").forEach { code ->
            shouldThrow<IllegalArgumentException> { UnitOfMeasure.of(code) }
        }
    }
}

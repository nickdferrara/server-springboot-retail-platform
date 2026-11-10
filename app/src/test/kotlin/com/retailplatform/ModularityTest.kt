package com.retailplatform

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {
    private val modules = ApplicationModules.of(RetailApplication::class.java)

    @Test
    fun `module boundaries are respected`() {
        modules.verify()
    }

    @Test
    fun `every module in the product requirements is declared`() {
        modules.map { it.identifier.toString() } shouldContainExactlyInAnyOrder
            listOf(
                "shared",
                "platform",
                "catalog",
                "locations",
                "vendors",
                "pricing",
                "ordering",
                "fulfillment",
                "purchasing",
                "receiving",
                "inventory",
                "ledger",
                "payables",
                "cash",
                "storesales",
                "fuel",
                "lottery",
                "salestax",
                "posmaintenance",
                "production",
                "quality",
            )
    }
}

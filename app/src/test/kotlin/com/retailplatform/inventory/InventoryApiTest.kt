package com.retailplatform.inventory

import com.retailplatform.TestcontainersConfiguration
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration

/** End to end across catalog, locations, and inventory: an item and a warehouse set up over REST hold stock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class InventoryApiTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `stock is counted in, written off, and explained by the ledger`() {
        val warehouseId = createWarehouse()
        val itemId = createItem()

        // Inventory learns about the item from its event, shortly after it is created.
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            count(itemId, warehouseId, counted = 48, unitCost = "1.25").andExpect {
                status { isCreated() }
                jsonPath("$.reason") { value("COUNT") }
                jsonPath("$.onHandAfter.amount") { value(48.0) }
            }
        }
        count(itemId, warehouseId, counted = 48).andExpect { status { isNoContent() } }
        adjust(itemId, warehouseId, quantity = -8, reason = "SHRINK").andExpect {
            status { isCreated() }
            jsonPath("$.type") { value("ADJUSTMENT") }
            jsonPath("$.unitCost.amount") { value(1.25) }
            jsonPath("$.extendedCost.amount") { value(-10.0) }
            jsonPath("$.warehouseId") { value(warehouseId) }
        }

        mockMvc.get("/api/v1/inventory/on-hand/$itemId?warehouseId=$warehouseId").andExpect {
            status { isOk() }
            jsonPath("$.onHand.amount") { value(40.0) }
            jsonPath("$.onHand.unit") { value("EA") }
            jsonPath("$.averageCost.amount") { value(1.25) }
            jsonPath("$.value.amount") { value(50.0) }
        }
        mockMvc.get("/api/v1/inventory/on-hand?warehouseId=$warehouseId").andExpect {
            jsonPath("$.total") { value(1) }
            jsonPath("$.items[0].itemId") { value(itemId) }
        }
        mockMvc.get("/api/v1/inventory/movements?itemId=$itemId&warehouseId=$warehouseId").andExpect {
            jsonPath("$.total") { value(2) }
            jsonPath("$.items[0].quantity.amount") { value(-8.0) }
            jsonPath("$.items[1].quantity.amount") { value(48.0) }
        }
    }

    @Test
    fun `invalid stock changes are refused before or by the business rules`() {
        val warehouseId = createWarehouse()
        val itemId = createItem()
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            count(itemId, warehouseId, counted = 5, unitCost = "1.00").andExpect { status { isCreated() } }
        }

        adjust(itemId, warehouseId, quantity = -6, reason = "DAMAGE").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("inventory.adjustment-exceeds-on-hand") }
        }
        adjust(itemId, warehouseId, quantity = 1, reason = "SHRINK").andExpect {
            jsonPath("$.code") { value("inventory.adjustment-must-reduce-stock") }
        }
        adjust(itemId, itemId, quantity = -1, reason = "SHRINK").andExpect {
            jsonPath("$.code") { value("inventory.unknown-location") }
        }
        post(
            "/api/v1/inventory/adjustments",
            """{"itemId": "$itemId", "quantity": {"amount": -1, "unit": "EA"}, "reason": "SHRINK"}""",
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid-value") }
        }
        post("/api/v1/inventory/adjustments", """{"itemId": "$itemId", "warehouseId": "$warehouseId"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation-failed") }
        }
        mockMvc.get("/api/v1/inventory/on-hand?warehouseId=$warehouseId&storeId=$warehouseId").andExpect {
            status { isBadRequest() }
        }
    }

    private fun count(
        itemId: String,
        warehouseId: String,
        counted: Int,
        unitCost: String? = null,
    ): ResultActionsDsl {
        val cost = unitCost?.let { """, "unitCost": {"amount": $it, "currency": "USD"}""" }.orEmpty()
        return post(
            "/api/v1/inventory/counts",
            """
            {"itemId": "$itemId", "warehouseId": "$warehouseId",
             "counted": {"amount": $counted, "unit": "EA"}$cost}
            """.trimIndent(),
        )
    }

    private fun adjust(
        itemId: String,
        warehouseId: String,
        quantity: Int,
        reason: String,
    ): ResultActionsDsl =
        post(
            "/api/v1/inventory/adjustments",
            """
            {"itemId": "$itemId", "warehouseId": "$warehouseId",
             "quantity": {"amount": $quantity, "unit": "EA"}, "reason": "$reason"}
            """.trimIndent(),
        )

    private fun createWarehouse(): String =
        created(
            post(
                "/api/v1/warehouses",
                """{"number": "${System.nanoTime() % 1_000_000}", "name": "Stock DC", "timeZone": "UTC"}""",
            ),
        )

    private fun createItem(): String {
        post("/api/v1/departments", """{"code": "INV", "name": "Inventory test"}""")
        post("/api/v1/departments/INV/categories", """{"code": "INVCAT", "name": "Inventory test"}""")
        return created(
            post(
                "/api/v1/items",
                """
                {"number": "${System.nanoTime() % 1_000_000_000}",
                 "details": {"description": "Stocked item", "categoryCode": "INVCAT", "baseUnit": "EA"}}
                """.trimIndent(),
            ),
        )
    }

    private fun created(result: ResultActionsDsl): String =
        result
            .andExpect { status { isCreated() } }
            .andReturn()
            .response
            .getHeader("Location")
            .orEmpty()
            .substringAfterLast('/')

    private fun post(
        path: String,
        body: String,
    ): ResultActionsDsl =
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
}

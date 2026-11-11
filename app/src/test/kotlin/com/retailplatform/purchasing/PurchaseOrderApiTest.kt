package com.retailplatform.purchasing

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
import org.springframework.test.web.servlet.put
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/**
 * End to end across vendors, locations, catalog, and purchasing: an item assigned to a vendor over REST can be
 * ordered from that vendor for a warehouse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PurchaseOrderApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: Clock,
) {
    private val today get() = LocalDate.now(clock)

    @Test
    fun `a purchase order is drafted from catalog costs, revised, approved, sent, and cancelled`() {
        val vendorId = createVendor()
        val warehouseId = createWarehouse()
        val itemId = createItem(vendorId, caseCost = "18.00")
        put(
            "/api/v1/purchasing/approval-threshold",
            """{"amount": {"amount": 500.00, "currency": "USD"}, "effectiveFrom": "$today"}""",
        ).andExpect { status { isOk() } }
        mockMvc.get("/api/v1/purchasing/approval-threshold").andExpect {
            jsonPath("$.amount.amount") { value(500.0) }
        }

        // Purchasing learns the vendor's cost from the catalog's event, shortly after it is set.
        var order = ""
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            order = created(post("/api/v1/purchase-orders", orderJson(vendorId, warehouseId, itemId, cases = 10)))
        }
        mockMvc.get(order).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DRAFT") }
            jsonPath("$.buyer") { value("local-developer") }
            jsonPath("$.total.amount") { value(180.0) }
            jsonPath("$.lines[0].vendorSku") { value("SKU-1") }
            jsonPath("$.lines[0].casePack") { value(12) }
        }

        put(order, termsJson(warehouseId, itemId, cases = 20, caseCost = "17.50")).andExpect {
            status { isOk() }
            jsonPath("$.total.amount") { value(350.0) }
            jsonPath("$.lines[0].extendedCost.amount") { value(350.0) }
        }
        post("$order/send", "").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("purchasing.order-state-forbids-action") }
        }
        post("$order/approve", "").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("APPROVED") }
            jsonPath("$.approvedBy") { value("local-developer") }
        }
        post("$order/send", "").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SENT") }
            jsonPath("$.sentOn") { value(today.toString()) }
        }
        post("$order/cancel", """{"reason": "Vendor out of stock"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("CANCELLED") }
            jsonPath("$.cancelReason") { value("Vendor out of stock") }
        }
        mockMvc.get("/api/v1/purchase-orders?size=200").andExpect {
            jsonPath("$.items[?(@.id == '${order.substringAfterLast('/')}')].status") { value("CANCELLED") }
        }
    }

    @Test
    fun `an order over the threshold cannot be approved by the buyer who created it`() {
        val vendorId = createVendor()
        val warehouseId = createWarehouse()
        val itemId = createItem(vendorId, caseCost = "18.00")
        var order = ""
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            order = created(post("/api/v1/purchase-orders", orderJson(vendorId, warehouseId, itemId, cases = 5000)))
        }

        post("$order/approve", "").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("purchasing.approval-by-creator") }
        }
    }

    @Test
    fun `invalid orders are refused before or by the business rules`() {
        val vendorId = createVendor()
        val warehouseId = createWarehouse()
        val itemId = createItem(vendorId, caseCost = "18.00")

        post(
            "/api/v1/purchase-orders",
            """
            {"vendorId": "$vendorId",
             "terms": {"warehouseId": "$warehouseId", "expectedOn": "$today", "lines": []}}
            """.trimIndent(),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation-failed") }
        }
        post("/api/v1/purchase-orders", orderJson("not-a-uuid", warehouseId, itemId, cases = 1)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid-value") }
        }
        post("/api/v1/purchase-orders", orderJson(vendorId, vendorId, itemId, cases = 1)).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("purchasing.unknown-warehouse") }
        }
        post("/api/v1/purchase-orders", orderJson(vendorId, warehouseId, warehouseId, cases = 1)).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("purchasing.item-not-supplied-by-vendor") }
        }
        mockMvc.get("/api/v1/purchase-orders/$vendorId").andExpect { status { isNotFound() } }
    }

    private fun orderJson(
        vendorId: String,
        warehouseId: String,
        itemId: String,
        cases: Int,
    ) = """{"vendorId": "$vendorId", "terms": ${termsJson(warehouseId, itemId, cases)}}"""

    private fun termsJson(
        warehouseId: String,
        itemId: String,
        cases: Int,
        caseCost: String? = null,
    ): String {
        val cost = caseCost?.let { """, "caseCost": {"amount": $it, "currency": "USD"}""" }.orEmpty()
        return """
            {"warehouseId": "$warehouseId", "expectedOn": "${today.plusDays(3)}",
             "lines": [{"itemId": "$itemId", "cases": $cases$cost}]}
            """.trimIndent()
    }

    private fun createVendor(): String =
        created(
            post(
                "/api/v1/vendors",
                """
                {"number": "${unique(1_000_000_000)}", "name": "Snack supplier", "paymentTerms": {"netDays": 30},
                 "remitTo": {"line1": "1 Mill Road", "city": "Springfield", "region": "IL", "postalCode": "62701",
                             "countryCode": "US"}}
                """.trimIndent(),
            ),
        ).substringAfterLast('/')

    private fun createWarehouse(): String =
        created(
            post(
                "/api/v1/warehouses",
                """{"number": "${unique(1_000_000)}", "name": "Purchasing DC", "timeZone": "UTC"}""",
            ),
        ).substringAfterLast('/')

    private fun createItem(
        vendorId: String,
        caseCost: String,
    ): String {
        post("/api/v1/departments", """{"code": "PUR", "name": "Purchasing test"}""")
        post("/api/v1/departments/PUR/categories", """{"code": "PURCAT", "name": "Purchasing test"}""")
        val itemId =
            created(
                post(
                    "/api/v1/items",
                    """
                    {"number": "${unique(1_000_000_000)}",
                     "details": {"description": "Ordered item", "categoryCode": "PURCAT", "baseUnit": "EA"}}
                    """.trimIndent(),
                ),
            ).substringAfterLast('/')
        put(
            "/api/v1/items/$itemId/vendors/$vendorId",
            """{"vendorSku": "SKU-1", "casePack": 12, "listCost": {"amount": $caseCost, "currency": "USD"}}""",
        ).andExpect { status { isOk() } }
        return itemId
    }

    private fun unique(below: Long): String = (System.nanoTime() % below).toString()

    private fun created(result: ResultActionsDsl): String =
        result
            .andExpect { status { isCreated() } }
            .andReturn()
            .response
            .getHeader("Location")
            .orEmpty()

    private fun post(
        path: String,
        body: String,
    ): ResultActionsDsl =
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun put(
        path: String,
        body: String,
    ): ResultActionsDsl =
        mockMvc.put(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
}

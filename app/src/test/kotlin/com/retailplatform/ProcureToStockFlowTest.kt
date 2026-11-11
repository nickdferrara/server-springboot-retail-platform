package com.retailplatform

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
 * End to end across purchasing, receiving, and inventory: a purchase order sent to a vendor is received at the
 * warehouse dock in two deliveries, and the goods arrive in the warehouse's stock at purchase order cost.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ProcureToStockFlowTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: Clock,
) {
    private val today get() = LocalDate.now(clock)
    private val patience = Duration.ofSeconds(15)

    @Test
    fun `a sent purchase order is received at the dock and lands in warehouse stock`() {
        val vendorId = createVendor()
        val warehouseId = createWarehouse()
        val itemId = createItem(vendorId)
        put(
            "/api/v1/purchasing/approval-threshold",
            """{"amount": {"amount": 500.00, "currency": "USD"}, "effectiveFrom": "$today"}""",
        )

        val order = sendOrder(vendorId, warehouseId, itemId)
        val orderId = order.substringAfterLast('/')

        // The dock learns of the order from PurchaseOrderSent.
        await().atMost(patience).untilAsserted {
            mockMvc.get("/api/v1/expected-deliveries/$orderId").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("OPEN") }
                jsonPath("$.lines[0].casesOutstanding") { value(10) }
            }
        }
        mockMvc.get("/api/v1/expected-deliveries?warehouseId=$warehouseId").andExpect {
            jsonPath("$.total") { value(1) }
        }

        val first = created(receive(orderId, casesReceived = 4, casesDamaged = 1))
        mockMvc.get(first).andExpect {
            status { isOk() }
            jsonPath("$.receivedBy") { value("local-developer") }
            jsonPath("$.lines[0].casesDamaged") { value(1) }
            jsonPath("$.lines[0].extendedCost.amount") { value(72.0) }
        }
        receive(orderId, casesReceived = 6).andExpect { status { isCreated() } }
        receive(orderId, casesReceived = 1).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("receiving.delivery-not-open") }
        }
        mockMvc.get("/api/v1/expected-deliveries/$orderId").andExpect { jsonPath("$.status") { value("COMPLETE") } }
        mockMvc.get("/api/v1/receipts?purchaseOrderId=$orderId").andExpect { jsonPath("$.length()") { value(2) } }

        // Inventory takes the goods in from GoodsReceived: 10 cases of 12 at 18.00 a case is 120 units at 1.50.
        await().atMost(patience).untilAsserted {
            mockMvc.get("/api/v1/inventory/on-hand/$itemId?warehouseId=$warehouseId").andExpect {
                status { isOk() }
                jsonPath("$.onHand.amount") { value(120.0) }
                jsonPath("$.averageCost.amount") { value(1.5) }
                jsonPath("$.value.amount") { value(180.0) }
            }
        }
        mockMvc.get("/api/v1/inventory/movements?itemId=$itemId&warehouseId=$warehouseId").andExpect {
            jsonPath("$.total") { value(2) }
            jsonPath("$.items[0].type") { value("RECEIPT") }
            jsonPath("$.items[1].quantity.amount") { value(48.0) }
        }
    }

    private fun sendOrder(
        vendorId: String,
        warehouseId: String,
        itemId: String,
    ): String {
        var order = ""
        await().atMost(patience).untilAsserted {
            order =
                created(
                    post(
                        "/api/v1/purchase-orders",
                        """
                        {"vendorId": "$vendorId",
                         "terms": {"warehouseId": "$warehouseId", "expectedOn": "${today.plusDays(2)}",
                                   "lines": [{"itemId": "$itemId", "cases": 10}]}}
                        """.trimIndent(),
                    ),
                )
        }
        post("$order/approve", "").andExpect { status { isOk() } }
        post("$order/send", "").andExpect { status { isOk() } }
        return order
    }

    private fun receive(
        orderId: String,
        casesReceived: Int,
        casesDamaged: Int = 0,
    ): ResultActionsDsl =
        post(
            "/api/v1/receipts",
            """
            {"purchaseOrderId": "$orderId",
             "lines": [{"lineNumber": 1, "casesReceived": $casesReceived, "casesDamaged": $casesDamaged}]}
            """.trimIndent(),
        )

    private fun createVendor(): String =
        created(
            post(
                "/api/v1/vendors",
                """
                {"number": "${System.nanoTime() % 1_000_000_000}", "name": "Snack supplier",
                 "paymentTerms": {"netDays": 30},
                 "remitTo": {"line1": "1 Mill Road", "city": "Springfield", "region": "IL", "postalCode": "62701",
                             "countryCode": "US"}}
                """.trimIndent(),
            ),
        ).substringAfterLast('/')

    private fun createWarehouse(): String =
        created(
            post(
                "/api/v1/warehouses",
                """{"number": "${System.nanoTime() % 1_000_000}", "name": "Receiving DC", "timeZone": "UTC"}""",
            ),
        ).substringAfterLast('/')

    private fun createItem(vendorId: String): String {
        post("/api/v1/departments", """{"code": "FLOW", "name": "Flow test"}""")
        post("/api/v1/departments/FLOW/categories", """{"code": "FLOWCAT", "name": "Flow test"}""")
        val itemId =
            created(
                post(
                    "/api/v1/items",
                    """
                    {"number": "${System.nanoTime() % 1_000_000_000}",
                     "details": {"description": "Received item", "categoryCode": "FLOWCAT", "baseUnit": "EA"}}
                    """.trimIndent(),
                ),
            ).substringAfterLast('/')
        put(
            "/api/v1/items/$itemId/vendors/$vendorId",
            """{"vendorSku": "SKU-1", "casePack": 12, "listCost": {"amount": 18.00, "currency": "USD"}}""",
        ).andExpect { status { isOk() } }
        return itemId
    }

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

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
 * End to end across receiving, inventory, payables, and the ledger: a buyer agrees a cost with a vendor for one
 * store, the store manager checks in the driver's ticket with what was delivered and what was taken back, and
 * the store's stock, the accrual, the vendor credit, and a balanced journal entry follow.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DirectStoreDeliveryFlowTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: Clock,
) {
    private val today get() = LocalDate.now(clock)
    private val patience = Duration.ofSeconds(15)

    @Test
    fun `a vendor's delivery to a store is stocked, accrued, credited, and posted at the store's agreed cost`() {
        val vendorId = createVendor()
        val storeId = createStore()
        val itemId = createItem()
        // Inventory learns the item from ItemCreated before anything can be stocked.
        await().atMost(patience).untilAsserted {
            mockMvc.get("/api/v1/inventory/on-hand/$itemId?storeId=$storeId").andExpect { status { isOk() } }
        }

        // Without an agreed cost the vendor may not deliver the item to this store.
        deliver(vendorId, storeId, itemId, "T-5001").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("receiving.no-agreed-cost") }
        }
        agree(vendorId, itemId, storeId, effectiveFrom = today.minusDays(1)).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("receiving.agreed-cost-effective-in-past") }
        }
        agree(vendorId, itemId, storeId, effectiveFrom = today).andExpect { status { isNoContent() } }
        mockMvc.get("/api/v1/direct-deliveries/costs?vendorId=$vendorId&storeId=$storeId").andExpect {
            status { isOk() }
            jsonPath("$[0].unitCost.amount") { value(1.25) }
        }

        val delivery = created(deliver(vendorId, storeId, itemId, "T-5001"))
        mockMvc.get(delivery).andExpect {
            status { isOk() }
            jsonPath("$.receivedBy") { value("local-developer") }
            jsonPath("$.deliveredCost.amount") { value(25.0) }
            jsonPath("$.returnedCost.amount") { value(3.75) }
            jsonPath("$.lines[0].unitsReturned") { value(3) }
        }
        deliver(vendorId, storeId, itemId, "T-5001").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("receiving.delivery-ticket-already-received") }
        }
        mockMvc.get("/api/v1/direct-deliveries?storeId=$storeId").andExpect { jsonPath("$.total") { value(1) } }

        stockedAccruedAndPosted(vendorId, storeId, itemId)
        invoiced(vendorId, storeId, itemId)
    }

    private fun stockedAccruedAndPosted(
        vendorId: String,
        storeId: String,
        itemId: String,
    ) {
        // 20 units in and 3 out leaves 17 on the shelf at 1.25.
        await().atMost(patience).untilAsserted {
            mockMvc.get("/api/v1/inventory/on-hand/$itemId?storeId=$storeId").andExpect {
                jsonPath("$.onHand.amount") { value(17.0) }
                jsonPath("$.averageCost.amount") { value(1.25) }
            }
        }
        await().atMost(patience).untilAsserted {
            mockMvc.get("/api/v1/payables/direct-delivery-accruals?vendorId=$vendorId").andExpect {
                status { isOk() }
                jsonPath("$.total") { value(1) }
                jsonPath("$.items[0].amount.amount") { value(25.0) }
                jsonPath("$.items[0].ticketNumber") { value("T-5001") }
            }
            mockMvc.get("/api/v1/payables/vendor-credits?vendorId=$vendorId").andExpect {
                jsonPath("$.total") { value(1) }
                jsonPath("$.items[0].amount.amount") { value(3.75) }
            }
        }
        await().atMost(patience).untilAsserted {
            mockMvc.get("/api/v1/ledger/journal-entries?size=200").andExpect {
                jsonPath("$.items[?(@.sourceDocument =~ /Receipt .*/ && @.amount.amount == 25.0)].journal") {
                    value("INVENTORY")
                }
            }
        }
        mockMvc.get("/api/v1/ledger/trial-balance?asOf=$today").andExpect { jsonPath("$.balanced") { value(true) } }
    }

    /**
     * The vendor bills the ticket: 20 units at 1.26 against an agreed 1.25 is within tolerance, so the invoice
     * matches, the accrual is relieved, and a voucher for 25.20 is released. A payment run then picks up the
     * voucher and deducts the 3.75 credit for what the driver took back.
     */
    private fun invoiced(
        vendorId: String,
        storeId: String,
        itemId: String,
    ) {
        bill(vendorId, storeId, itemId, "T-9999", "DSD-${System.nanoTime()}").andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("EXCEPTION") }
            jsonPath("$.exceptions[0].reason") { value("TICKET_NOT_RECEIVED") }
        }
        mockMvc.get("/api/v1/payables/direct-delivery-invoices?exceptionsOnly=true").andExpect {
            jsonPath("$.items[0].ticketNumber") { value("T-9999") }
        }

        val invoice = created(bill(vendorId, storeId, itemId, "T-5001", "DSD-${System.nanoTime()}"))
        mockMvc.get(invoice).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("MATCHED") }
            jsonPath("$.amount.amount") { value(25.2) }
        }
        post("$invoice/override", """{"reason": "Already matched"}""").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("payables.invoice-already-matched") }
        }
        mockMvc.get("/api/v1/payables/direct-delivery-accruals?vendorId=$vendorId").andExpect {
            jsonPath("$.items[0].status") { value("RELIEVED") }
        }
        mockMvc.get("/api/v1/payables/vouchers?vendorId=$vendorId").andExpect {
            jsonPath("$.total") { value(1) }
            jsonPath("$.items[0].amount.amount") { value(25.2) }
        }
        await().atMost(patience).untilAsserted {
            mockMvc.get("/api/v1/ledger/journal-entries?size=200").andExpect {
                jsonPath("$.items[?(@.journal == 'PURCHASES' && @.amount.amount == 25.2)].sourceModule") {
                    value("payables")
                }
            }
        }

        val run =
            created(
                post(
                    "/api/v1/payables/payment-runs",
                    """{"paymentDate": "$today", "payThrough": "${today.plusDays(400)}"}""",
                ),
            )
        mockMvc.get(run).andExpect {
            jsonPath("$.lines[?(@.vendorId == '$vendorId')].amount.amount") { value(25.2) }
            jsonPath("$.credits[?(@.vendorId == '$vendorId')].amount.amount") { value(3.75) }
        }
        mockMvc.get("/api/v1/payables/vendor-credits?vendorId=$vendorId").andExpect {
            jsonPath("$.items[0].status") { value("SCHEDULED") }
        }
        // With sign-in off the proposer cannot approve the run, so it is cancelled and the credit is open again.
        post("$run/cancel", "").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/payables/vendor-credits?vendorId=$vendorId").andExpect {
            jsonPath("$.items[0].status") { value("OPEN") }
        }
    }

    private fun bill(
        vendorId: String,
        storeId: String,
        itemId: String,
        ticket: String,
        number: String,
    ): ResultActionsDsl =
        post(
            "/api/v1/payables/direct-delivery-invoices",
            """
            {"vendorId": "$vendorId", "storeId": "$storeId", "ticketNumber": "$ticket",
             "vendorInvoiceNumber": "$number", "invoiceDate": "$today",
             "lines": [{"itemId": "$itemId", "units": 20, "unitCost": {"amount": 1.26, "currency": "USD"}}]}
            """.trimIndent(),
        )

    private fun agree(
        vendorId: String,
        itemId: String,
        storeId: String,
        effectiveFrom: LocalDate,
    ): ResultActionsDsl =
        put(
            "/api/v1/direct-deliveries/costs",
            """
            {"vendorId": "$vendorId", "itemId": "$itemId", "storeIds": ["$storeId"],
             "unitCost": {"amount": 1.25, "currency": "USD"}, "effectiveFrom": "$effectiveFrom"}
            """.trimIndent(),
        )

    private fun deliver(
        vendorId: String,
        storeId: String,
        itemId: String,
        ticket: String,
    ): ResultActionsDsl =
        post(
            "/api/v1/direct-deliveries",
            """
            {"vendorId": "$vendorId", "storeId": "$storeId", "ticketNumber": "$ticket",
             "lines": [{"itemId": "$itemId", "unitsDelivered": 20, "unitsReturned": 3}]}
            """.trimIndent(),
        )

    private fun createVendor(): String =
        created(
            post(
                "/api/v1/vendors",
                """
                {"number": "${System.nanoTime() % 1_000_000_000}", "name": "Beverage distributor",
                 "paymentTerms": {"netDays": 30},
                 "remitTo": {"line1": "9 Canal Street", "city": "Springfield", "region": "IL", "postalCode": "62701",
                             "countryCode": "US"}}
                """.trimIndent(),
            ),
        ).substringAfterLast('/')

    private fun createStore(): String =
        created(
            post(
                "/api/v1/stores",
                """
                {"number": "${100_000 + System.nanoTime() % 900_000}", "name": "Canal Street", "timeZone": "America/Chicago",
                 "openedOn": "$today"}
                """.trimIndent(),
            ),
        ).substringAfterLast('/')

    private fun createItem(): String {
        post("/api/v1/departments", """{"code": "DSD", "name": "Direct delivery"}""")
        post("/api/v1/departments/DSD/categories", """{"code": "DSDCAT", "name": "Direct delivery"}""")
        return created(
            post(
                "/api/v1/items",
                """
                {"number": "${System.nanoTime() % 1_000_000_000}",
                 "details": {"description": "Cola 20 oz", "categoryCode": "DSDCAT", "baseUnit": "EA"}}
                """.trimIndent(),
            ),
        ).substringAfterLast('/')
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

package com.retailplatform.pricing

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

/** End to end across catalog, locations, and pricing: an item and a store set up over REST can be priced. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PricingApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: Clock,
) {
    @Test
    fun `an item is priced by zone, overridden for one store, and resolved by date`() {
        val today = LocalDate.now(clock)
        val itemId = createItem()
        val storeId = createStore()

        // Pricing learns about the item and the store from their events, shortly after they are created.
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            schedule(itemId, """"zoneCode": "DEFAULT"""", "3.00", today).andExpect { status { isCreated() } }
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            schedule(itemId, """"storeId": "$storeId"""", "2.75", today.plusDays(2)).andExpect {
                status { isCreated() }
                jsonPath("$.announcedOn") { doesNotExist() }
            }
        }

        effective(itemId, storeId, today).andExpect {
            status { isOk() }
            jsonPath("$.price.amount") { value(3.0) }
            jsonPath("$.source.zoneCode") { value("DEFAULT") }
        }
        effective(itemId, storeId, today.plusDays(2)).andExpect {
            jsonPath("$.price.amount") { value(2.75) }
            jsonPath("$.source.storeId") { value(storeId) }
        }
        mockMvc.get("/api/v1/retail-prices?itemId=$itemId").andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].announcedOn") { value(today.toString()) }
        }

        schedule(itemId, """"zoneCode": "DEFAULT"""", "3.10", today).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("pricing.price-already-scheduled") }
        }
        schedule(itemId, """"zoneCode": "DEFAULT"""", "3.10", today.minusDays(1)).andExpect {
            jsonPath("$.code") { value("pricing.price-effective-in-past") }
        }
        schedule(itemId, """"zoneCode": "DEFAULT", "storeId": "$storeId"""", "3.10", today.plusDays(1)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid-value") }
        }
    }

    @Test
    fun `price zones are created and stores are moved between them`() {
        val storeId = createStore()
        post(
            "/api/v1/price-zones",
            """{"code": "RURAL", "name": "Rural stores"}""",
        ).andExpect { status { isCreated() } }
        mockMvc.get("/api/v1/price-zones").andExpect {
            jsonPath("$[?(@.code == 'DEFAULT')].name") { value("Default") }
            jsonPath("$[?(@.code == 'RURAL')].name") { value("Rural stores") }
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            mockMvc.put("/api/v1/price-zones/RURAL/stores/$storeId").andExpect {
                status { isOk() }
                jsonPath("$.zoneCode") { value("RURAL") }
            }
        }
        mockMvc.put("/api/v1/price-zones/NOWHERE/stores/$storeId").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("pricing.unknown-price-zone") }
        }
        post("/api/v1/price-zones", """{"code": "bad code", "name": ""}""").andExpect { status { isBadRequest() } }
    }

    private fun schedule(
        itemId: String,
        scope: String,
        amount: String,
        effectiveFrom: LocalDate,
    ): ResultActionsDsl =
        post(
            "/api/v1/retail-prices",
            """
            {"itemId": "$itemId", $scope, "price": {"amount": $amount, "currency": "USD"},
             "effectiveFrom": "$effectiveFrom"}
            """.trimIndent(),
        )

    private fun effective(
        itemId: String,
        storeId: String,
        date: LocalDate,
    ): ResultActionsDsl = mockMvc.get("/api/v1/retail-prices/effective?itemId=$itemId&storeId=$storeId&date=$date")

    private fun createItem(): String {
        post("/api/v1/departments", """{"code": "PRC", "name": "Pricing test"}""")
        post("/api/v1/departments/PRC/categories", """{"code": "PRCCAT", "name": "Pricing test"}""")
        val number = (System.nanoTime() % 1_000_000_000).toString()
        return created(
            post(
                "/api/v1/items",
                """
                {"number": "$number",
                 "details": {"description": "Priced item", "categoryCode": "PRCCAT", "baseUnit": "EA"}}
                """.trimIndent(),
            ),
        )
    }

    private fun createStore(): String {
        val number = (System.nanoTime() % 1_000_000).toString()
        return created(
            post(
                "/api/v1/stores",
                """{"number": "$number", "name": "Priced store", "timeZone": "UTC", "openedOn": "2026-01-01"}""",
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

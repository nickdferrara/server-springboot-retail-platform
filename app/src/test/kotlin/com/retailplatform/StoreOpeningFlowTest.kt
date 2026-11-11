package com.retailplatform

import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.pricing.application.StorePriceZoneRepository
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.StoreId
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration

/** End to end: a store opened over REST ends up with a price zone, through a recorded event. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class StoreOpeningFlowTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val assignments: StorePriceZoneRepository,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val json: JsonMapper,
    @Autowired private val clock: Clock,
) {
    @Test
    fun `opening a store over REST gives it a price zone in the pricing module`() {
        val location =
            mockMvc
                .post("/api/v1/stores") {
                    contentType = MediaType.APPLICATION_JSON
                    content = storeJson(number = "0401", name = "Orchard Square")
                }.andExpect {
                    status { isCreated() }
                    jsonPath("$.number") { value("0401") }
                    jsonPath("$.name") { value("Orchard Square") }
                }.andReturn()
                .response
                .getHeader("Location")
                .orEmpty()
        val storeId = StoreId.parse(location.substringAfterLast('/'))

        mockMvc.get(location).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(storeId.toString()) }
            jsonPath("$.timeZone") { value("America/Chicago") }
            jsonPath("$.openedOn") { value("2026-12-01") }
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assignments.findByStore(storeId)?.zone shouldBe PriceZoneCode.DEFAULT
            incompletePublications() shouldBe 0
        }
    }

    @Test
    fun `a duplicate store number is refused with the rule's code`() {
        val body = storeJson(number = "0402", name = "Quarry Road")
        mockMvc.post("/api/v1/stores") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

        mockMvc
            .post("/api/v1/stores") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isUnprocessableContent() }
                jsonPath("$.code") { value("locations.store-number-in-use") }
            }
    }

    @Test
    fun `invalid input never reaches the use case`() {
        mockMvc
            .post("/api/v1/stores") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"number": "12AB", "name": "", "timeZone": "America/Chicago"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation-failed") }
            }

        mockMvc
            .post("/api/v1/stores") {
                contentType = MediaType.APPLICATION_JSON
                content = storeJson(number = "0403", name = "Nowhere", timeZone = "Mars/Olympus")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid-value") }
            }

        mockMvc.get("/api/v1/stores/not-a-uuid").andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/stores/${StoreId.generate(clock)}").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("not-found") }
        }
    }

    @Test
    fun `StoreOpened survives the JSON round trip the event registry relies on`() {
        val event =
            StoreOpened(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = BusinessDate.parse("2026-12-01"),
                storeId = StoreId.generate(clock),
                storeNumber = "0404",
                storeName = "Ferry Street",
                timeZone = "America/Chicago",
            )

        json.readValue(json.writeValueAsString(event), StoreOpened::class.java) shouldBe event
    }

    private fun incompletePublications(): Int =
        jdbc
            .sql("SELECT count(*) FROM platform.event_publication WHERE completion_date IS NULL")
            .query(Int::class.java)
            .single()

    private fun storeJson(
        number: String,
        name: String,
        timeZone: String = "America/Chicago",
    ) = """{"number": "$number", "name": "$name", "timeZone": "$timeZone", "openedOn": "2026-12-01"}"""
}

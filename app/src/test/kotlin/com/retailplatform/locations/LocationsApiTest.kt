package com.retailplatform.locations

import com.retailplatform.TestcontainersConfiguration
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class LocationsApiTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `a warehouse, a store, and a route between them are maintained over REST`() {
        val warehouse =
            create("/api/v1/warehouses", """{"number": "0951", "name": "Central DC", "timeZone": "America/Chicago"}""")
        val store =
            create(
                "/api/v1/stores",
                """{"number": "0651", "name": "Elm Street", "timeZone": "America/Chicago", "openedOn": "2026-12-01"}""",
            )
        val route =
            create(
                "/api/v1/routes",
                """
                {"code": "R0951", "name": "Elm loop", "warehouseId": "${warehouse.substringAfterLast('/')}",
                 "stops": [{"storeId": "${store.substringAfterLast('/')}", "deliveryDay": "TUESDAY",
                            "cutoffDaysBefore": 1, "cutoffTime": "14:00:00"}]}
                """.trimIndent(),
            )

        put(warehouse, """{"name": "Central distribution", "timeZone": "America/Chicago"}""").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Central distribution") }
        }
        mockMvc.get("/api/v1/warehouses?size=200").andExpect {
            jsonPath("$.items[?(@.number == '0951')].name") { value("Central distribution") }
        }
        mockMvc.get(route).andExpect {
            status { isOk() }
            jsonPath("$.stops[0].deliveryDay") { value("TUESDAY") }
            jsonPath("$.stops[0].cutoffTime") { value("14:00:00") }
        }

        closeStore(store).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("locations.store-still-on-route") }
        }
        put("$route/schedule", """{"stops": []}""").andExpect {
            status { isOk() }
            jsonPath("$.stops.length()") { value(0) }
        }
        mockMvc.get("/api/v1/routes?size=200").andExpect {
            jsonPath("$.items[?(@.code == 'R0951')].name") { value("Elm loop") }
        }

        put(store, """{"name": "Elm Street West", "timeZone": "America/Chicago"}""").andExpect {
            jsonPath("$.name") { value("Elm Street West") }
        }
        closeStore(store).andExpect {
            status { isOk() }
            jsonPath("$.open") { value(false) }
            jsonPath("$.closedOn") { value("2027-01-31") }
        }
        mockMvc.get("/api/v1/stores?size=200").andExpect {
            jsonPath("$.items[?(@.number == '0651')].open") { value(false) }
        }
    }

    @Test
    fun `invalid route input is refused`() {
        mockMvc
            .post("/api/v1/routes") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"code": "bad code", "name": "", "warehouseId": ""}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation-failed") }
            }

        val stop =
            """
            {"storeId": "019a6f3e-0000-7000-8000-000000000001", "deliveryDay": "MONDAY",
             "cutoffDaysBefore": 1, "cutoffTime": "09:00:00"}
            """.trimIndent()
        mockMvc
            .post("/api/v1/routes") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"code": "R0952", "name": "Twice", "warehouseId": "019a6f3e-0000-7000-8000-000000000002",
                     "stops": [$stop, $stop]}
                    """.trimIndent()
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid-value") }
            }

        put(
            "/api/v1/stores/019a6f3e-0000-7000-8000-000000000001",
            """{"name": "Nowhere", "timeZone": "Mars/Olympus"}""",
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid-value") }
        }
    }

    private fun put(
        path: String,
        body: String,
    ): ResultActionsDsl =
        mockMvc.put(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun closeStore(store: String): ResultActionsDsl =
        mockMvc.post("$store/close") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"lastTradingDay": "2027-01-31"}"""
        }

    private fun create(
        path: String,
        body: String,
    ): String =
        mockMvc
            .post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isCreated() } }
            .andReturn()
            .response
            .getHeader("Location")
            .orEmpty()
}

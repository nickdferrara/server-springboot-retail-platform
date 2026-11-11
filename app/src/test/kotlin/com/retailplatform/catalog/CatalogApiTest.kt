package com.retailplatform.catalog

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
class CatalogApiTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `an item is set up, changed, given a vendor, and discontinued over REST`() {
        post("/api/v1/departments", """{"code": "BEV", "name": "Beverages"}""").andExpect { status { isCreated() } }
        post("/api/v1/departments/BEV/categories", """{"code": "SODA", "name": "Soft drinks"}""").andExpect {
            status { isCreated() }
            jsonPath("$.departmentCode") { value("BEV") }
        }
        post(
            "/api/v1/units-of-measure",
            """{"code": "PK12", "name": "Twelve pack"}""",
        ).andExpect { status { isCreated() } }
        mockMvc.get("/api/v1/departments").andExpect { jsonPath("$[?(@.code == 'BEV')].name") { value("Beverages") } }
        mockMvc.get("/api/v1/departments/BEV/categories").andExpect { jsonPath("$[0].code") { value("SODA") } }
        mockMvc
            .get(
                "/api/v1/units-of-measure",
            ).andExpect { jsonPath("$[?(@.code == 'PK12')].name") { value("Twelve pack") } }

        val item =
            post("/api/v1/items", itemJson(number = "400001", description = "Cola 12oz can", upc = "049000006346"))
                .andExpect {
                    status { isCreated() }
                    jsonPath("$.departmentCode") { value("BEV") }
                    jsonPath("$.packs[0].unit") { value("PK12") }
                }.andReturn()
                .response
                .getHeader("Location")
                .orEmpty()

        put(item, detailsJson(description = "Cola 12oz can, classic", upc = "049000006346")).andExpect {
            status { isOk() }
            jsonPath("$.description") { value("Cola 12oz can, classic") }
        }

        val vendorId = createVendor("4001")
        put(
            "$item/vendors/$vendorId",
            """{"vendorSku": "COLA-12", "casePack": 24, "listCost": {"amount": 9.6, "currency": "USD"}}""",
        ).andExpect {
            status { isOk() }
            jsonPath("$.vendors[0].vendorSku") { value("COLA-12") }
            jsonPath("$.vendors[0].listCost.amount") { value(9.6) }
        }

        post("$item/discontinue", """{"lastDay": "2026-12-31"}""").andExpect {
            status { isOk() }
            jsonPath("$.discontinuedOn") { value("2026-12-31") }
        }
        put(item, detailsJson(description = "Too late", upc = "049000006346")).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("catalog.item-discontinued") }
        }
        mockMvc.get("/api/v1/items?size=200").andExpect {
            jsonPath("$.items[?(@.number == '400001')].description") { value("Cola 12oz can, classic") }
        }
    }

    @Test
    fun `invalid item input is refused`() {
        post("/api/v1/items", """{"number": "ABC", "details": {"description": "", "upcs": ["123"]}}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation-failed") }
        }
        post("/api/v1/items", itemJson(number = "400002", description = "Bad check digit", upc = "049000006347"))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid-value") }
            }
        mockMvc.get("/api/v1/departments/nope/categories").andExpect { status { isBadRequest() } }
        mockMvc.get("/api/v1/departments/NOPE/categories").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("catalog.unknown-department") }
        }
    }

    private fun itemJson(
        number: String,
        description: String,
        upc: String,
    ) = """{"number": "$number", "details": ${detailsJson(description, upc)}}"""

    private fun detailsJson(
        description: String,
        upc: String,
    ) = """
        {"description": "$description", "categoryCode": "SODA", "baseUnit": "EA",
         "packs": [{"unit": "PK12", "baseUnits": 12}], "upcs": ["$upc"]}
        """.trimIndent()

    private fun createVendor(number: String): String =
        post(
            "/api/v1/vendors",
            """
            {"number": "$number", "name": "Bottler $number", "paymentTerms": {"netDays": 30},
             "remitTo": {"line1": "1 Supply Way", "city": "Springfield", "region": "IL",
                         "postalCode": "62701", "countryCode": "US"}}
            """.trimIndent(),
        ).andReturn().response.getHeader("Location").orEmpty().substringAfterLast('/')

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

package com.retailplatform.vendors

import com.retailplatform.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class VendorApiTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `a vendor is created, read, updated, and listed over REST`() {
        val location =
            mockMvc
                .post("/api/v1/vendors") {
                    contentType = MediaType.APPLICATION_JSON
                    content = vendorJson(number = "2001", name = "Acme Snacks")
                }.andExpect {
                    status { isCreated() }
                    jsonPath("$.number") { value("2001") }
                    jsonPath("$.paymentTerms.netDays") { value(30) }
                    jsonPath("$.contacts[0].email") { value("dana@example.com") }
                }.andReturn()
                .response
                .getHeader("Location")
                .orEmpty()

        mockMvc
            .put("$location/payment-terms") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"netDays": 30, "discountPercent": 2, "discountDays": 10}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.paymentTerms.discountDays") { value(10) }
            }

        mockMvc
            .put(location) {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"name": "Acme Foods", "contacts": [],
                     "remitTo": {"line1": "9 Dock Road", "city": "Peoria", "region": "IL",
                                 "postalCode": "61602", "countryCode": "US"}}
                    """.trimIndent()
            }.andExpect {
                status { isOk() }
                jsonPath("$.name") { value("Acme Foods") }
                jsonPath("$.remitTo.city") { value("Peoria") }
                jsonPath("$.contacts.length()") { value(0) }
            }

        mockMvc.get(location).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Acme Foods") }
            jsonPath("$.paymentTerms.discountPercent") { value(2.0) }
        }

        mockMvc.get("/api/v1/vendors?size=200").andExpect {
            status { isOk() }
            jsonPath("$.items[?(@.number == '2001')].name") { value("Acme Foods") }
            jsonPath("$.size") { value(200) }
        }
    }

    @Test
    fun `invalid vendor input is refused before it reaches a use case`() {
        mockMvc
            .post("/api/v1/vendors") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"number": "ABC", "name": "", "paymentTerms": {"netDays": -1}}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation-failed") }
            }

        mockMvc
            .post("/api/v1/vendors") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    vendorJson(number = "2002", name = "Half Terms", terms = """{"netDays": 30, "discountDays": 10}""")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid-value") }
            }

        mockMvc.get("/api/v1/vendors?size=500").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid-value") }
        }
    }

    @Test
    fun `a duplicate vendor number is refused with the rule's code`() {
        repeat(2) { attempt ->
            mockMvc
                .post("/api/v1/vendors") {
                    contentType = MediaType.APPLICATION_JSON
                    content = vendorJson(number = "2003", name = "Twice")
                }.andExpect {
                    status { if (attempt == 0) isCreated() else isUnprocessableContent() }
                }
        }
    }

    private fun vendorJson(
        number: String,
        name: String,
        terms: String = """{"netDays": 30}""",
    ) = """
        {"number": "$number", "name": "$name", "paymentTerms": $terms,
         "remitTo": {"line1": "1 Supply Way", "city": "Springfield", "region": "IL",
                     "postalCode": "62701", "countryCode": "US"},
         "contacts": [{"name": "Dana Lee", "email": "dana@example.com"}]}
        """.trimIndent()
}

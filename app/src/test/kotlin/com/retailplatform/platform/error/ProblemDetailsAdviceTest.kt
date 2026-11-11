package com.retailplatform.platform.error

import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.ResourceNotFound
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(ProblemDetailsAdviceTest.ProbeController::class)
@Import(ProblemDetailsAdviceTest.ProbeController::class)
class ProblemDetailsAdviceTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `a field that fails validation is reported by name`() {
        mockMvc
            .post("/probe") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": ""}"""
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.type") { value("urn:retail-platform:problem:validation-failed") }
                jsonPath("$.code") { value("validation-failed") }
                jsonPath("$.errors[0].field") { value("name") }
            }
    }

    @Test
    fun `a body that cannot be parsed is a malformed request`() {
        mockMvc
            .post("/probe") {
                contentType = MediaType.APPLICATION_JSON
                content = "{not json"
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("malformed-request") }
            }
    }

    @Test
    fun `a value object that refuses its input is a bad request`() {
        expectProblem("invalid-value", 400, "invalid-value")
    }

    @Test
    fun `a business rule violation carries the rule's own code`() {
        mockMvc.get("/probe/business-rule").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.type") { value("urn:retail-platform:problem:business-rule-violation") }
            jsonPath("$.code") { value("probe.rule-broken") }
            jsonPath("$.detail") { value("The probe rule was broken") }
        }
    }

    @Test
    fun `a missing resource is not found`() {
        expectProblem("not-found", 404, "not-found")
    }

    @Test
    fun `a stale write and a violated constraint are conflicts`() {
        expectProblem("stale", 409, "conflict")
        expectProblem("duplicate", 409, "conflict")
    }

    @Test
    fun `an unexpected failure does not leak its message`() {
        mockMvc.get("/probe/unexpected").andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("internal-error") }
            jsonPath("$.detail") { value("An unexpected error occurred.") }
        }
    }

    @Test
    fun `errors raised by the framework have the same shape`() {
        mockMvc.post("/probe/not-found").andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.type") { value("urn:retail-platform:problem:method-not-allowed") }
            jsonPath("$.code") { value("method-not-allowed") }
        }
    }

    private fun expectProblem(
        failure: String,
        expectedStatus: Int,
        expectedCode: String,
    ) {
        mockMvc.get("/probe/$failure").andExpect {
            status { isEqualTo(expectedStatus) }
            jsonPath("$.type") { value("urn:retail-platform:problem:$expectedCode") }
            jsonPath("$.code") { value(expectedCode) }
        }
    }

    data class ProbeRequest(
        @field:NotBlank val name: String,
    )

    class ProbeRuleBroken : BusinessRuleViolation("probe.rule-broken", "The probe rule was broken")

    @RestController
    class ProbeController {
        @PostMapping("/probe")
        fun create(
            @Valid @RequestBody request: ProbeRequest,
        ): String = request.name

        @GetMapping("/probe/{failure}")
        fun fail(
            @PathVariable failure: String,
        ): String =
            when (failure) {
                "invalid-value" -> throw IllegalArgumentException("Not a unit of measure code: 'x'")
                "business-rule" -> throw ProbeRuleBroken()
                "not-found" -> throw ResourceNotFound("Probe", "42")
                "stale" -> throw OptimisticLockingFailureException("stale")
                "duplicate" -> throw DuplicateKeyException("duplicate")
                else -> error("secret internal detail")
            }
    }
}

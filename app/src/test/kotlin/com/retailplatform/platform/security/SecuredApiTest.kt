package com.retailplatform.platform.security

import com.retailplatform.TestcontainersConfiguration
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Runs with sign-in on (any profile other than `local`) and real signed bearer tokens. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("secured")
@Import(TestcontainersConfiguration::class, TestTokens::class)
class SecuredApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: TestTokens,
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `a request without a token is unauthenticated`() {
        mockMvc.get("/api/v1/stores/019a6f3e-0000-7000-8000-000000000001").andExpect {
            status { isUnauthorized() }
            header { exists(HttpHeaders.WWW_AUTHENTICATE) }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("unauthenticated") }
        }
    }

    @Test
    fun `an expired or forged token is unauthenticated`() {
        val expired =
            tokens.accessToken(
                "user-1",
                Roles.MASTER_DATA_EDITOR,
                expiresAt = Instant.now().minus(1, ChronoUnit.HOURS),
            )

        openStore("0501", expired).andExpect { status { isUnauthorized() } }
        openStore("0501", "not.a.token").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a user without the role is forbidden`() {
        openStore("0502", tokens.accessToken("user-2", Roles.STORE_ORDERER)).andExpect {
            status { isForbidden() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("forbidden") }
        }
    }

    @Test
    fun `a user with the role is let through and recorded as the author`() {
        val objectId = "3f2b7c1e-user-with-role"

        openStore("0503", tokens.accessToken(objectId, Roles.BUYER, Roles.MASTER_DATA_EDITOR)).andExpect {
            status { isCreated() }
        }

        jdbc
            .sql("SELECT created_by FROM locations.store WHERE number = '0503'")
            .query(String::class.java)
            .single() shouldBe objectId
    }

    @Test
    fun `any signed-in user may read`() {
        mockMvc
            .get("/api/v1/stores/019a6f3e-0000-7000-8000-000000000001") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken("user-3")}")
            }.andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("not-found") }
            }
    }

    @Test
    fun `health probes need no token`() {
        mockMvc.get("/actuator/health/liveness").andExpect { status { isOk() } }
        mockMvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
    }

    private fun openStore(
        number: String,
        token: String,
    ): ResultActionsDsl =
        mockMvc.post("/api/v1/stores") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"number": "$number", "name": "Secured", "timeZone": "America/Chicago", "openedOn": "2026-12-01"}"""
        }
}

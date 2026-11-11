package com.retailplatform.platform.security

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt

class EntraJwtAuthenticationConverterTest {
    private val converter = EntraJwtAuthenticationConverter()

    @Test
    fun `the user is the object ID and each role becomes an authority`() {
        val authentication =
            converter.convert(token { it.claim("oid", "object-1").claim("roles", listOf("Buyer", "Buyer.Approver")) })

        authentication.name shouldBe "object-1"
        authentication.authorities.map { it.authority } shouldContainExactly listOf("Buyer", "Buyer.Approver")
    }

    @Test
    fun `a token without roles or object ID is a user with no authorities, named by its subject`() {
        val authentication = converter.convert(token { it })

        authentication.name shouldBe "subject-1"
        authentication.authorities.shouldBeEmpty()
    }

    private fun token(claims: (Jwt.Builder) -> Jwt.Builder): Jwt =
        claims(Jwt.withTokenValue("token").header("alg", "none").subject("subject-1")).build()
}

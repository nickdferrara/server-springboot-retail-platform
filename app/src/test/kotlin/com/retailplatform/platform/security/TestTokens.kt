package com.retailplatform.platform.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Stands in for Microsoft Entra ID in tests: signs access tokens with a key pair generated for the test run, and
 * gives the application the matching public key to validate them with.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestTokens {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val encoder =
        NimbusJwtEncoder(
            ImmutableJWKSet<SecurityContext>(
                JWKSet(RSAKey.Builder(publicKey).privateKey(keyPair.private as RSAPrivateKey).build()),
            ),
        )

    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(publicKey).build()

    /** A signed access token for the user with object ID [objectId] holding [roles]. */
    fun accessToken(
        objectId: String,
        vararg roles: String,
        expiresAt: Instant = Instant.now().plus(1, ChronoUnit.HOURS),
    ): String {
        val claims =
            JwtClaimsSet
                .builder()
                .subject("subject-of-$objectId")
                .claim("oid", objectId)
                .claim("roles", roles.toList())
                .issuedAt(expiresAt.minus(2, ChronoUnit.HOURS))
                .expiresAt(expiresAt)
                .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    private companion object {
        const val KEY_SIZE = 2048
    }
}

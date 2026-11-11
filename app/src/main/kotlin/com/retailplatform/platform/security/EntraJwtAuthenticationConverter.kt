package com.retailplatform.platform.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Turns a validated Microsoft Entra ID access token into an authenticated user.
 *
 * The user's name is the `oid` claim, the object ID that stays the same when a person's email changes. Each entry in
 * the `roles` claim becomes an authority with the same text, for example `Buyer.Approver`.
 */
class EntraJwtAuthenticationConverter : Converter<Jwt, AbstractAuthenticationToken> {
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val roles = jwt.getClaimAsStringList(ROLES_CLAIM).orEmpty()
        val user =
            jwt.getClaimAsString(OBJECT_ID_CLAIM)
                ?: jwt.subject
                ?: throw InvalidBearerTokenException("The token does not identify a user")
        return JwtAuthenticationToken(jwt, roles.map(::SimpleGrantedAuthority), user)
    }

    private companion object {
        const val ROLES_CLAIM = "roles"
        const val OBJECT_ID_CLAIM = "oid"
    }
}

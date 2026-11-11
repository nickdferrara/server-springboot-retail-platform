package com.retailplatform.platform.security

import com.retailplatform.platform.persistence.AuditingConfiguration
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * Who is making the current request. Use cases that record or compare the acting user, such as an approval that
 * its creator may not give, take this in their constructor.
 */
@Component
class CurrentUser {
    /** The signed-in user's name, or the system user for work no person started. */
    val name: String get() = authentication()?.name ?: AuditingConfiguration.SYSTEM_USER

    fun hasRole(role: String): Boolean = authentication()?.authorities.orEmpty().any { it.authority == role }

    private fun authentication(): Authentication? =
        SecurityContextHolder.getContext().authentication?.takeIf { it.isAuthenticated }
}

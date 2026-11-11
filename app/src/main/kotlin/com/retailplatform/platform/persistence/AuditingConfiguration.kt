package com.retailplatform.platform.persistence

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.util.Optional

/** Fills in [AuditMetadata] on save, taking the time from the injected clock. */
@Configuration(proxyBeanMethods = false)
@EnableJdbcAuditing(dateTimeProviderRef = "auditDateTimeProvider", auditorAwareRef = "auditorAware")
class AuditingConfiguration {
    @Bean
    fun auditDateTimeProvider(clock: Clock): DateTimeProvider = DateTimeProvider { Optional.of(clock.instant()) }

    /** The signed-in user, or the system user for work no person started, such as an event listener. */
    @Bean
    fun auditorAware(): AuditorAware<String> =
        AuditorAware {
            val authentication = SecurityContextHolder.getContext().authentication
            val user = authentication?.takeIf { it.isAuthenticated }?.name
            Optional.of(user ?: SYSTEM_USER)
        }

    companion object {
        const val SYSTEM_USER = "system"
    }
}

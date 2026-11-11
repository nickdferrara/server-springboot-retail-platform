package com.retailplatform.platform.persistence

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing
import java.time.Clock
import java.util.Optional

/** Fills in [AuditMetadata] on save, taking the time from the injected clock. */
@Configuration(proxyBeanMethods = false)
@EnableJdbcAuditing(dateTimeProviderRef = "auditDateTimeProvider", auditorAwareRef = "auditorAware")
class AuditingConfiguration {
    @Bean
    fun auditDateTimeProvider(clock: Clock): DateTimeProvider = DateTimeProvider { Optional.of(clock.instant()) }

    /** Until sign-in is in place every change is attributed to the system user. */
    @Bean
    fun auditorAware(): AuditorAware<String> = AuditorAware { Optional.of(SYSTEM_USER) }

    companion object {
        const val SYSTEM_USER = "system"
    }
}

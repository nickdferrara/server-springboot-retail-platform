package com.retailplatform.architecture.fixture.domain

import com.retailplatform.architecture.fixture.application.WellBehavedUseCase
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.Money
import jakarta.validation.constraints.NotBlank
import org.springframework.util.StringUtils

// Deliberately wrong (and one right) domain classes used by ArchitectureRulesTest.

class WellBehavedDomain(
    val price: Money,
)

class DomainUsingSpring {
    fun hasText(text: String): Boolean = StringUtils.hasText(text)
}

class DomainUsingValidation(
    @field:NotBlank val name: String,
)

class DomainUsingPlatform(
    val audit: AuditMetadata,
)

class DomainCallingUseCase(
    val useCase: WellBehavedUseCase,
)

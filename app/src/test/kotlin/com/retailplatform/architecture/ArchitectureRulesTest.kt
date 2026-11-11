package com.retailplatform.architecture

import com.retailplatform.architecture.fixture.adapter.`in`.InboundAdapterCallingOutboundAdapter
import com.retailplatform.architecture.fixture.application.UseCaseCallingAdapter
import com.retailplatform.architecture.fixture.application.WellBehavedUseCase
import com.retailplatform.architecture.fixture.domain.DomainCallingUseCase
import com.retailplatform.architecture.fixture.domain.DomainUsingPlatform
import com.retailplatform.architecture.fixture.domain.DomainUsingSpring
import com.retailplatform.architecture.fixture.domain.DomainUsingValidation
import com.retailplatform.architecture.fixture.domain.WellBehavedDomain
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchRule
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/** Proves each rule catches the violation it exists for, using deliberately wrong classes under `fixture`. */
class ArchitectureRulesTest {
    @Test
    fun `a domain class may not use Spring or Jakarta types`() {
        ArchitectureRules.domainIsPlainKotlin.violatedBy(DomainUsingSpring::class) shouldBe true
        ArchitectureRules.domainIsPlainKotlin.violatedBy(DomainUsingValidation::class) shouldBe true
    }

    @Test
    fun `a domain class may not use the platform module`() {
        ArchitectureRules.domainDoesNotDependOnInfrastructure.violatedBy(DomainUsingPlatform::class) shouldBe true
    }

    @Test
    fun `a domain class may not call a use case`() {
        ArchitectureRules.domainDoesNotDependOutward.violatedBy(DomainCallingUseCase::class) shouldBe true
    }

    @Test
    fun `a use case may not call an adapter`() {
        ArchitectureRules.applicationDoesNotDependOnAdapters.violatedBy(UseCaseCallingAdapter::class) shouldBe true
    }

    @Test
    fun `an inbound adapter may not call an outbound adapter`() {
        ArchitectureRules.inboundAndOutboundAdaptersAreIndependent
            .violatedBy(InboundAdapterCallingOutboundAdapter::class) shouldBe true
    }

    @Test
    fun `a field may not be injected`() {
        ArchitectureRules.noFieldInjection.violatedBy(UseCaseCallingAdapter::class) shouldBe true
    }

    @Test
    fun `classes that depend inward pass every rule`() {
        ArchitectureRules.all.forEach { rule ->
            rule.violatedBy(WellBehavedDomain::class, WellBehavedUseCase::class) shouldBe false
        }
    }

    private fun ArchRule.violatedBy(vararg classes: KClass<*>): Boolean =
        evaluate(ClassFileImporter().importClasses(classes.map { it.java })).hasViolation()
}

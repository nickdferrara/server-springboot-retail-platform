package com.retailplatform.architecture

import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields

/**
 * The hexagonal layout every module follows: `domain` at the centre, `application` around it, `adapter` outside.
 * Dependencies point inward only, and the domain is plain Kotlin.
 */
object ArchitectureRules {
    private const val DOMAIN = "..domain.."
    private const val APPLICATION = "..application.."
    private const val ADAPTER = "..adapter.."

    val domainIsPlainKotlin: ArchRule =
        noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "javax.persistence..",
                "javax.validation..",
                "java.sql..",
                "javax.sql..",
                "org.flywaydb..",
            ).because("domain code is plain Kotlin: no Spring, Jakarta, or persistence types")
            .allowEmptyShould(true)

    val domainDoesNotDependOnInfrastructure: ArchRule =
        noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.retailplatform.platform..")
            .because("the domain may use the shared kernel but not the platform infrastructure")
            .allowEmptyShould(true)

    val domainDoesNotDependOutward: ArchRule =
        noClasses()
            .that()
            .resideInAPackage(DOMAIN)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(APPLICATION, ADAPTER)
            .because("dependencies point inward: the domain knows nothing about use cases or adapters")
            .allowEmptyShould(true)

    val applicationDoesNotDependOnAdapters: ArchRule =
        noClasses()
            .that()
            .resideInAPackage(APPLICATION)
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ADAPTER)
            .because("dependencies point inward: use cases reach the outside world through ports")
            .allowEmptyShould(true)

    val inboundAndOutboundAdaptersAreIndependent: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..adapter.in..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter.out..")
            .because("an inbound adapter calls a use case, never an outbound adapter directly")
            .allowEmptyShould(true)

    val noFieldInjection: ArchRule =
        noFields()
            .should()
            .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .orShould()
            .beAnnotatedWith("jakarta.inject.Inject")
            .because("dependencies are injected through constructors")
            .allowEmptyShould(true)

    val all: List<ArchRule> =
        listOf(
            domainIsPlainKotlin,
            domainDoesNotDependOnInfrastructure,
            domainDoesNotDependOutward,
            applicationDoesNotDependOnAdapters,
            inboundAndOutboundAdaptersAreIndependent,
            noFieldInjection,
        )
}

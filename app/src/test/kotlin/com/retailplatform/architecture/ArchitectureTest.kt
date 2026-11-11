package com.retailplatform.architecture

import com.retailplatform.RetailApplication
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test

/** Checks the production code against [ArchitectureRules]. */
class ArchitectureTest {
    private val productionClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackagesOf(RetailApplication::class.java)

    @Test
    fun `production classes are found`() {
        productionClasses.size shouldBeGreaterThan 0
    }

    @Test
    fun `the production code follows the architecture rules`() {
        ArchitectureRules.all.forEach { rule -> rule.check(productionClasses) }
    }
}

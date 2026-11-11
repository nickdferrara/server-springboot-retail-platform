package com.retailplatform.catalog

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.catalog.api.events.ItemChanged
import com.retailplatform.catalog.api.events.ItemCreated
import com.retailplatform.catalog.api.events.ItemDiscontinued
import com.retailplatform.catalog.api.events.VendorCostChanged
import com.retailplatform.catalog.application.ChangeItem
import com.retailplatform.catalog.application.ChangeItemCommand
import com.retailplatform.catalog.application.CreateCategory
import com.retailplatform.catalog.application.CreateDepartment
import com.retailplatform.catalog.application.CreateItem
import com.retailplatform.catalog.application.CreateItemCommand
import com.retailplatform.catalog.application.DefineUnitOfMeasure
import com.retailplatform.catalog.application.DiscontinueItem
import com.retailplatform.catalog.application.DiscontinueItemCommand
import com.retailplatform.catalog.application.ItemDetailsInput
import com.retailplatform.catalog.application.ItemQueries
import com.retailplatform.catalog.application.ReferenceDataQueries
import com.retailplatform.catalog.application.SetVendorItem
import com.retailplatform.catalog.application.SetVendorItemCommand
import com.retailplatform.catalog.domain.CatalogName
import com.retailplatform.catalog.domain.Category
import com.retailplatform.catalog.domain.CategoryCode
import com.retailplatform.catalog.domain.CategoryCodeAlreadyInUse
import com.retailplatform.catalog.domain.Department
import com.retailplatform.catalog.domain.DepartmentCode
import com.retailplatform.catalog.domain.DepartmentCodeAlreadyInUse
import com.retailplatform.catalog.domain.ItemDescription
import com.retailplatform.catalog.domain.ItemNumber
import com.retailplatform.catalog.domain.ItemNumberAlreadyInUse
import com.retailplatform.catalog.domain.ItemUnits
import com.retailplatform.catalog.domain.Pack
import com.retailplatform.catalog.domain.UnitOfMeasureAlreadyDefined
import com.retailplatform.catalog.domain.UnknownCategory
import com.retailplatform.catalog.domain.UnknownDepartment
import com.retailplatform.catalog.domain.UnknownUnitOfMeasure
import com.retailplatform.catalog.domain.UnknownVendor
import com.retailplatform.catalog.domain.Upc
import com.retailplatform.catalog.domain.UpcAlreadyAssigned
import com.retailplatform.catalog.domain.VendorItem
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.UnitOfMeasure
import com.retailplatform.shared.VendorId
import com.retailplatform.vendors.application.CreateVendor
import com.retailplatform.vendors.application.CreateVendorCommand
import com.retailplatform.vendors.domain.PaymentTerms
import com.retailplatform.vendors.domain.PostalAddress
import com.retailplatform.vendors.domain.VendorName
import com.retailplatform.vendors.domain.VendorNumber
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.modulith.test.Scenario
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.Clock

/** Runs with the vendors module too, because assigning a vendor to an item asks vendors whether it exists. */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration::class)
@WithMockUser(authorities = [Roles.MASTER_DATA_EDITOR])
class CatalogModuleTest(
    @Autowired private val createDepartment: CreateDepartment,
    @Autowired private val createCategory: CreateCategory,
    @Autowired private val defineUnitOfMeasure: DefineUnitOfMeasure,
    @Autowired private val referenceData: ReferenceDataQueries,
    @Autowired private val createItem: CreateItem,
    @Autowired private val changeItem: ChangeItem,
    @Autowired private val discontinueItem: DiscontinueItem,
    @Autowired private val setVendorItem: SetVendorItem,
    @Autowired private val items: ItemQueries,
    @Autowired private val createVendor: CreateVendor,
    @Autowired private val clock: Clock,
) {
    private val grocery = DepartmentCode.of("GROC")
    private val salty = CategoryCode.of("SALTY")
    private val candy = CategoryCode.of("CANDY")

    @BeforeEach
    fun hierarchy() {
        if (referenceData.departments().none { it.code == grocery }) {
            createDepartment.handle(Department(grocery, CatalogName.of("Grocery")))
            createCategory.handle(Category(salty, grocery, CatalogName.of("Salty snacks")))
            createCategory.handle(Category(candy, grocery, CatalogName.of("Candy")))
        }
    }

    @Test
    fun `the hierarchy and units are maintained`() {
        referenceData.categories(grocery).map { it.code } shouldBe listOf(candy, salty)
        referenceData.unitsOfMeasure().map { it.unit } shouldContainAll
            listOf(UnitOfMeasure.EACH, UnitOfMeasure.CASE, UnitOfMeasure.POUND, UnitOfMeasure.GALLON)

        defineUnitOfMeasure.handle(UnitOfMeasure.of("PK6"), CatalogName.of("Six pack"))
        referenceData.unitsOfMeasure().map { it.unit } shouldContain UnitOfMeasure.of("PK6")

        shouldThrow<UnitOfMeasureAlreadyDefined> {
            defineUnitOfMeasure.handle(
                UnitOfMeasure.EACH,
                CatalogName.of("Each"),
            )
        }
        shouldThrow<DepartmentCodeAlreadyInUse> {
            createDepartment.handle(
                Department(grocery, CatalogName.of("Again")),
            )
        }
        shouldThrow<CategoryCodeAlreadyInUse> {
            createCategory.handle(
                Category(salty, grocery, CatalogName.of("Again")),
            )
        }
        shouldThrow<UnknownDepartment> {
            createCategory.handle(
                Category(CategoryCode.of("ORPHAN"), DepartmentCode.of("NOPE"), CatalogName.of("Orphan")),
            )
        }
        shouldThrow<UnknownDepartment> { referenceData.categories(DepartmentCode.of("NOPE")) }
    }

    @Test
    fun `creating an item saves it with barcodes and packs and publishes ItemCreated`(scenario: Scenario) {
        val input = details("Potato chips 8oz", upcs = setOf("036000291452"), packs = mapOf("CS" to 24))

        scenario
            .stimulate(Runnable { createItem.handle(CreateItemCommand(ItemNumber.of("300001"), input)) })
            .andWaitForEventOfType(ItemCreated::class.java)
            .matching { it.item.itemNumber == "300001" }
            .toArriveAndVerify { event ->
                event.item.departmentCode shouldBe "GROC"
                val saved = items.find(event.itemId)
                saved.details.upcs shouldBe input.upcs
                saved.details.units shouldBe input.units
                saved.version shouldBe 1
                items.list(PageQuery.of(size = 200)).items.map { it.id } shouldContain event.itemId
            }
    }

    @Test
    fun `changing an item publishes ItemChanged`(scenario: Scenario) {
        val itemId = createItem.handle(CreateItemCommand(ItemNumber.of("300002"), details("Pretzels")))
        val changed = details("Pretzel twists", category = candy, upcs = setOf("012345678905"))

        scenario
            .stimulate(Runnable { changeItem.handle(ChangeItemCommand(itemId, changed)) })
            .andWaitForEventOfType(ItemChanged::class.java)
            .matching { it.itemId == itemId }
            .toArriveAndVerify { event ->
                event.item.categoryCode shouldBe "CANDY"
                event.item.upcs shouldBe listOf("012345678905")
                items
                    .find(itemId)
                    .details.description.value shouldBe "Pretzel twists"
            }
    }

    @Test
    fun `discontinuing an item publishes ItemDiscontinued`(scenario: Scenario) {
        val itemId = createItem.handle(CreateItemCommand(ItemNumber.of("300003"), details("Seasonal mix")))
        val lastDay = BusinessDate.parse("2026-12-31")

        scenario
            .stimulate(Runnable { discontinueItem.handle(DiscontinueItemCommand(itemId, lastDay)) })
            .andWaitForEventOfType(ItemDiscontinued::class.java)
            .matching { it.itemId == itemId }
            .toArriveAndVerify { event ->
                event.businessDate shouldBe lastDay
                items.find(itemId).discontinuedOn shouldBe lastDay
            }
    }

    @Test
    fun `assigning a vendor that exists publishes VendorCostChanged`(scenario: Scenario) {
        val itemId = createItem.handle(CreateItemCommand(ItemNumber.of("300004"), details("Corn chips")))
        val vendorId = vendor("3004")
        val vendorItem = VendorItem.of(vendorId, "CC-10", 12, Money.of("14.40", "USD"))

        scenario
            .stimulate(Runnable { setVendorItem.handle(SetVendorItemCommand(itemId, vendorItem)) })
            .andWaitForEventOfType(VendorCostChanged::class.java)
            .matching { it.itemId == itemId }
            .toArriveAndVerify { event ->
                event.newCost shouldBe BigDecimal("14.4000")
                event.previousCost shouldBe null
                items.find(itemId).vendorItems shouldBe listOf(vendorItem)
            }

        shouldThrow<UnknownVendor> {
            setVendorItem.handle(
                SetVendorItemCommand(itemId, VendorItem.of(VendorId.generate(clock), "X", 1, Money.of("1", "USD"))),
            )
        }
    }

    @Test
    fun `an item needs a free number, a known category, defined units, and barcodes no other item has`() {
        val itemId =
            createItem.handle(
                CreateItemCommand(ItemNumber.of("300005"), details("Original", upcs = setOf("036000241457"))),
            )

        shouldThrow<ItemNumberAlreadyInUse> {
            createItem.handle(
                CreateItemCommand(ItemNumber.of("300005"), details("Copy")),
            )
        }
        shouldThrow<UnknownCategory> {
            createItem.handle(
                CreateItemCommand(ItemNumber.of("300006"), details("Lost", category = CategoryCode.of("NOPE"))),
            )
        }
        shouldThrow<UnknownUnitOfMeasure> {
            createItem.handle(
                CreateItemCommand(ItemNumber.of("300006"), details("Odd", packs = mapOf("PALLET" to 960))),
            )
        }
        shouldThrow<UpcAlreadyAssigned> {
            createItem.handle(
                CreateItemCommand(ItemNumber.of("300006"), details("Clash", upcs = setOf("036000241457"))),
            )
        }

        // The item may keep its own barcode when it changes.
        changeItem.handle(ChangeItemCommand(itemId, details("Original, renamed", upcs = setOf("036000241457"))))
        items
            .find(itemId)
            .details.description.value shouldBe "Original, renamed"
    }

    @Test
    fun `an item that does not exist cannot be found or changed`() {
        val unknown = ItemId.generate(clock)

        shouldThrow<ResourceNotFound> { items.find(unknown) }
        shouldThrow<ResourceNotFound> { changeItem.handle(ChangeItemCommand(unknown, details("Nothing"))) }
        shouldThrow<ResourceNotFound> {
            discontinueItem.handle(
                DiscontinueItemCommand(unknown, BusinessDate.today(clock)),
            )
        }
        shouldThrow<ResourceNotFound> {
            setVendorItem.handle(
                SetVendorItemCommand(unknown, VendorItem.of(VendorId.generate(clock), "X", 1, Money.of("1", "USD"))),
            )
        }
    }

    private fun details(
        description: String,
        category: CategoryCode = salty,
        upcs: Set<String> = emptySet(),
        packs: Map<String, Int> = emptyMap(),
    ) = ItemDetailsInput(
        description = ItemDescription.of(description),
        categoryCode = category,
        units =
            ItemUnits.of(
                UnitOfMeasure.EACH,
                packs.map { (unit, baseUnits) -> Pack.of(UnitOfMeasure.of(unit), BigDecimal(baseUnits)) },
            ),
        upcs = upcs.map(Upc::of).toSet(),
    )

    private fun vendor(number: String): VendorId =
        createVendor.handle(
            CreateVendorCommand(
                VendorNumber.of(number),
                VendorName.of("Vendor $number"),
                PaymentTerms.net(30),
                PostalAddress.of("1 Supply Way", null, "Springfield", "IL", "62701", "US"),
                emptyList(),
            ),
        )
}

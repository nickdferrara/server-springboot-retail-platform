package com.retailplatform.catalog.domain

import com.retailplatform.catalog.api.events.ItemChanged
import com.retailplatform.catalog.api.events.ItemCreated
import com.retailplatform.catalog.api.events.ItemDiscontinued
import com.retailplatform.catalog.api.events.VendorCostChanged
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import com.retailplatform.shared.Quantity
import com.retailplatform.shared.UnitOfMeasure
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ItemTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T15:00:00Z"), ZoneOffset.UTC)
    private val snacks = Category(CategoryCode.of("SALTY"), DepartmentCode.of("GROC"), CatalogName.of("Salty snacks"))
    private val chipsUpc = Upc.of("036000291452")
    private val eachAndCase = ItemUnits.of(UnitOfMeasure.EACH, listOf(Pack.of(UnitOfMeasure.CASE, BigDecimal(24))))
    private val vendorId = VendorId.generate(clock)

    private fun details(
        description: String = "Potato chips 8oz",
        units: ItemUnits = eachAndCase,
        upcs: Set<Upc> = setOf(chipsUpc),
    ) = ItemDetails(ItemDescription.of(description), snacks.placement(), units, upcs)

    private fun item() = Item.create(ItemNumber.of("100200"), details(), clock)

    private fun vendorItem(cost: String) = VendorItem.of(vendorId, " CH-8 ", 24, Money.of(cost, "USD"))

    @Test
    fun `creating an item raises ItemCreated with its place in the hierarchy`() {
        val item = item()

        val event = item.releaseEvents().single() as ItemCreated

        event.itemId shouldBe item.id
        event.item.itemNumber shouldBe "100200"
        event.item.departmentCode shouldBe "GROC"
        event.item.categoryCode shouldBe "SALTY"
        event.item.baseUnit shouldBe "EA"
        event.item.upcs shouldBe listOf("036000291452")
        event.businessDate shouldBe BusinessDate.parse("2026-11-10")
    }

    @Test
    fun `changing an item raises ItemChanged only when something differs`() {
        val item = item().also { it.releaseEvents() }

        item.change(details(), clock)
        item.releaseEvents().shouldBeEmpty()

        item.change(details(description = "Potato chips 9oz"), clock)
        (item.releaseEvents().single() as ItemChanged).item.description shouldBe "Potato chips 9oz"
    }

    @Test
    fun `the base unit of an item cannot change`() {
        val item = item()

        shouldThrow<BaseUnitCannotChange> { item.change(details(units = ItemUnits.of(UnitOfMeasure.POUND)), clock) }
            .code shouldBe "catalog.base-unit-cannot-change"
    }

    @Test
    fun `a discontinued item raises ItemDiscontinued once and can no longer change`() {
        val item = item().also { it.releaseEvents() }
        val lastDay = BusinessDate.parse("2026-12-31")

        item.discontinue(lastDay, clock)

        val event = item.releaseEvents().single() as ItemDiscontinued
        event.businessDate shouldBe lastDay
        item.isDiscontinued shouldBe true
        shouldThrow<ItemIsDiscontinued> { item.discontinue(lastDay, clock) }
        shouldThrow<ItemIsDiscontinued> { item.change(details(description = "New"), clock) }
        shouldThrow<ItemIsDiscontinued> { item.setVendorItem(vendorItem("20.00"), clock) }
            .code shouldBe "catalog.item-discontinued"
    }

    @Test
    fun `assigning a vendor and changing its cost raise VendorCostChanged`() {
        val item = item().also { it.releaseEvents() }

        item.setVendorItem(vendorItem("20.00"), clock)
        val first = item.releaseEvents().single() as VendorCostChanged
        first.previousCost shouldBe null
        first.newCost shouldBe BigDecimal("20.0000")
        first.vendorSku shouldBe "CH-8"
        first.casePack shouldBe 24
        first.currency shouldBe "USD"

        item.setVendorItem(vendorItem("20.00"), clock)
        item.releaseEvents().shouldBeEmpty()

        item.setVendorItem(vendorItem("21.50"), clock)
        val second = item.releaseEvents().single() as VendorCostChanged
        second.previousCost shouldBe BigDecimal("20.0000")
        second.newCost shouldBe BigDecimal("21.5000")
        item.vendorItems.size shouldBe 1
    }

    @Test
    fun `quantities convert from a pack to the base unit`() {
        val item = item()

        item.toBaseUnits(Quantity.of(2, UnitOfMeasure.CASE)) shouldBe Quantity.of(48, UnitOfMeasure.EACH)
        item.toBaseUnits(Quantity.of(5, UnitOfMeasure.EACH)) shouldBe Quantity.of(5, UnitOfMeasure.EACH)
        shouldThrow<UnitNotDefinedForItem> { item.toBaseUnits(Quantity.of(1, UnitOfMeasure.POUND)) }
            .code shouldBe "catalog.unit-not-defined-for-item"
    }

    @Test
    fun `packs must be positive, use distinct units, and not repeat the base unit`() {
        shouldThrow<IllegalArgumentException> { Pack.of(UnitOfMeasure.CASE, BigDecimal.ZERO) }
        shouldThrow<IllegalArgumentException> { Pack.of(UnitOfMeasure.CASE, BigDecimal("1.0001")) }
        shouldThrow<IllegalArgumentException> {
            ItemUnits.of(UnitOfMeasure.EACH, listOf(Pack.of(UnitOfMeasure.EACH, BigDecimal(6))))
        }
        shouldThrow<IllegalArgumentException> {
            val sixPack = Pack.of(UnitOfMeasure.CASE, BigDecimal(6))
            ItemUnits.of(UnitOfMeasure.EACH, listOf(sixPack, sixPack))
        }
        eachAndCase.all shouldBe setOf(UnitOfMeasure.EACH, UnitOfMeasure.CASE)
    }

    @Test
    fun `a UPC is 12 or 13 digits with a valid check digit`() {
        Upc.of("036000291452").toString() shouldBe "036000291452"
        Upc.of("4006381333931").value shouldBe "4006381333931"
        shouldThrow<IllegalArgumentException> { Upc.of("036000291453") }
        shouldThrow<IllegalArgumentException> { Upc.of("12345") }
        shouldThrow<IllegalArgumentException> { Upc.of("03600029145X") }
    }

    @Test
    fun `a vendor item needs a SKU, a case of at least one, and a cost that is not negative`() {
        vendorItem("0").listCost.isZero shouldBe true
        shouldThrow<IllegalArgumentException> { VendorItem.of(vendorId, " ", 24, Money.of("1", "USD")) }
        shouldThrow<IllegalArgumentException> { VendorItem.of(vendorId, "x".repeat(31), 24, Money.of("1", "USD")) }
        shouldThrow<IllegalArgumentException> { VendorItem.of(vendorId, "CH-8", 0, Money.of("1", "USD")) }
        shouldThrow<IllegalArgumentException> { VendorItem.of(vendorId, "CH-8", 24, Money.of("-1", "USD")) }
    }

    @Test
    fun `codes, numbers, names, and descriptions are validated`() {
        shouldThrow<IllegalArgumentException> { DepartmentCode.of("groc") }
        shouldThrow<IllegalArgumentException> { CategoryCode.of("TOO-LONG-CODE") }
        shouldThrow<IllegalArgumentException> { ItemNumber.of("12A") }
        shouldThrow<IllegalArgumentException> { ItemDescription.of(" ") }
        shouldThrow<IllegalArgumentException> { ItemDescription.of("x".repeat(101)) }
        shouldThrow<IllegalArgumentException> { CatalogName.of("") }
        shouldThrow<IllegalArgumentException> { CatalogName.of("x".repeat(61)) }
        CatalogName.of(" Grocery ").toString() shouldBe "Grocery"
        ItemNumber.of("0001").toString() shouldBe "0001"
    }

    @Test
    fun `a rebuilt item raises nothing, and each rule has a stable code`() {
        val original = item()
        val rebuilt = Item.rehydrate(original.id, original.number, original.details, emptyList(), null, 4)

        rebuilt.releaseEvents().shouldBeEmpty()
        rebuilt.version shouldBe 4
        ItemNumberAlreadyInUse(original.number).code shouldBe "catalog.item-number-in-use"
        UpcAlreadyAssigned(listOf(chipsUpc)).code shouldBe "catalog.upc-already-assigned"
        UnknownVendor(vendorId).code shouldBe "catalog.unknown-vendor"
        UnknownCategory(snacks.code).code shouldBe "catalog.unknown-category"
        UnknownDepartment(snacks.departmentCode).code shouldBe "catalog.unknown-department"
        UnknownUnitOfMeasure(listOf(UnitOfMeasure.POUND)).code shouldBe "catalog.unknown-unit-of-measure"
        DepartmentCodeAlreadyInUse(snacks.departmentCode).code shouldBe "catalog.department-code-in-use"
        CategoryCodeAlreadyInUse(snacks.code).code shouldBe "catalog.category-code-in-use"
        UnitOfMeasureAlreadyDefined("EA").code shouldBe "catalog.unit-already-defined"
    }
}

package com.retailplatform.inventory.domain

import com.retailplatform.inventory.api.events.InventoryAdjusted
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Quantity
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.UnitOfMeasure
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StockLevelTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val itemId = ItemId.generate(clock)
    private val warehouse = StockLocation.Warehouse(WarehouseId.generate(clock))

    @Test
    fun `a first receipt sets the average cost and later receipts move it by weight`() {
        val level = empty()
        val source = MovementSource("PO 100001", EventId.generate(clock))

        val first = level.receive(each(100), usd("1.00"), today, source, clock)
        val second = level.receive(each(300), usd("1.40"), today.plusDays(1), source, clock)

        first.type shouldBe MovementType.RECEIPT
        first.after shouldBe StockPosition(each(100), usd("1.00"))
        first.extendedCost shouldBe usd("100.00")
        second.after shouldBe StockPosition(each(400), usd("1.30"))
        second.businessDate shouldBe today.plusDays(1)
        second.source shouldBe source
        level.value shouldBe usd("520.00")
        level.releaseEvents().shouldBeEmpty()
    }

    @Test
    fun `the average is rounded half-even to four places`() {
        val level = empty()
        level.receive(each(3), usd("1.00"), today, MovementSource.NONE, clock)
        level.receive(each(3), usd("1.0001"), today, MovementSource.NONE, clock)

        // (3 x 1.0000 + 3 x 1.0001) / 6 = 1.00005, which rounds to the even 1.0000.
        level.averageCost shouldBe usd("1.0000")
    }

    @Test
    fun `shrink and damage leave at average cost and announce the write-off`() {
        val level = stocked(onHand = 100, averageCost = "1.25")

        val movement = level.adjust(each(-8), AdjustmentReason.SHRINK, unitCost = usd("9.99"), clock)

        movement.type shouldBe MovementType.ADJUSTMENT
        movement.unitCost shouldBe usd("1.25")
        movement.extendedCost shouldBe usd("-10.00")
        movement.after shouldBe StockPosition(each(92), usd("1.25"))
        val event = level.releaseEvents().single().shouldBeInstanceOf<InventoryAdjusted>()
        event.reason shouldBe "SHRINK"
        event.quantity shouldBe BigDecimal("-8.000")
        event.extendedCost shouldBe BigDecimal("-10.0000")
        event.warehouseId shouldBe warehouse.warehouseId
        event.storeId shouldBe null
        event.movementId shouldBe movement.id
    }

    @Test
    fun `shrink and damage only reduce stock, and never below zero`() {
        val level = stocked(onHand = 5, averageCost = "1.25")

        shouldThrow<AdjustmentMustReduceStock> { level.adjust(each(1), AdjustmentReason.DAMAGE, null, clock) }
        shouldThrow<AdjustmentExceedsOnHand> { level.adjust(each(-6), AdjustmentReason.SHRINK, null, clock) }
        shouldThrow<AdjustmentChangesNothing> { level.adjust(each(0), AdjustmentReason.COUNT, null, clock) }
        shouldThrow<QuantityNotInBaseUnit> {
            level.adjust(Quantity.of(-1, UnitOfMeasure.CASE), AdjustmentReason.SHRINK, null, clock)
        }
        level.onHand shouldBe each(5)
        level.releaseEvents().shouldBeEmpty()
    }

    @Test
    fun `a count adjusts on-hand to what was found`() {
        val level = stocked(onHand = 40, averageCost = "2.00")

        level.count(each(40), null, clock) shouldBe null
        val loss = level.count(each(37), null, clock)
        val gain = level.count(each(47), usd("3.00"), clock)

        loss?.quantity shouldBe each(-3)
        loss?.reason shouldBe AdjustmentReason.COUNT
        gain?.quantity shouldBe each(10)
        gain?.unitCost shouldBe usd("3.00")
        // 37 at 2.00 plus 10 at 3.00 is 104.00 over 47 units.
        level.averageCost shouldBe usd("2.2128")
        level.releaseEvents().size shouldBe 2
        shouldThrow<CountIsNegative> { level.count(each(-1), null, clock) }
    }

    @Test
    fun `stock found without a cost is valued at average, and needs a cost when none is known`() {
        val known = stocked(onHand = 10, averageCost = "2.00")
        known.count(each(12), null, clock)?.unitCost shouldBe usd("2.00")
        known.averageCost shouldBe usd("2.00")

        val unknown = empty()
        unknown.value shouldBe null
        shouldThrow<CostNotKnown> { unknown.count(each(12), null, clock) }
        unknown.count(each(12), usd("1.50"), clock)?.after shouldBe StockPosition(each(12), usd("1.50"))
        shouldThrow<CostCurrencyMismatch> { unknown.count(each(13), Money.of("1.50", "CAD"), clock) }
    }

    @Test
    fun `a receipt into stock at or below zero takes the incoming cost`() {
        val level = stocked(onHand = 0, averageCost = "5.00")

        level.receive(each(10), usd("4.00"), today, MovementSource.NONE, clock)

        level.averageCost shouldBe usd("4.00")
        shouldThrow<QuantityNotPositive> { level.receive(each(0), usd("4.00"), today, MovementSource.NONE, clock) }
    }

    @Test
    fun `a location is a warehouse or a store`() {
        val store = StockLocation.Store(StoreId.generate(clock))

        StockLocation.of("STORE", store.id) shouldBe store
        StockLocation.of("WAREHOUSE", warehouse.id) shouldBe warehouse
        shouldThrow<IllegalArgumentException> { StockLocation.of("TRUCK", store.id) }
    }

    @Test
    fun `product a vendor takes back leaves at average cost, even past zero on hand`() {
        val level = stocked(onHand = 10, averageCost = "1.30")
        val source = MovementSource("Receipt 100009", EventId.generate(clock))

        val returned = level.returnToVendor(each(4), usd("1.25"), today, source, clock)

        returned.type shouldBe MovementType.VENDOR_RETURN
        returned.quantity shouldBe each(-4)
        returned.unitCost shouldBe usd("1.30")
        returned.after shouldBe StockPosition(each(6), usd("1.30"))
        level.returnToVendor(each(8), usd("1.25"), today, source, clock).after.onHand shouldBe each(-2)
        shouldThrow<QuantityNotPositive> { level.returnToVendor(each(0), usd("1.25"), today, source, clock) }

        // With no cost known yet, the credited cost values what leaves.
        val unknown = empty()
        unknown.returnToVendor(each(2), usd("1.25"), today, source, clock).unitCost shouldBe usd("1.25")
        unknown.averageCost shouldBe usd("1.25")
    }

    @Test
    fun `a receipt taken back out is undone at its own cost, and a return put back comes in at average`() {
        val level = stocked(onHand = 10, averageCost = "1.00")
        val source = MovementSource("Receipt 100010", EventId.generate(clock))
        level.receive(each(10), usd("2.00"), today, source, clock)
        level.averageCost shouldBe usd("1.50")

        val undone = level.reverseReceipt(each(10), usd("2.00"), today, source, clock)

        undone.type shouldBe MovementType.RECEIPT
        undone.quantity shouldBe each(-10)
        undone.unitCost shouldBe usd("2.00")
        undone.after shouldBe StockPosition(each(10), usd("1.00"))

        val putBack = level.reverseVendorReturn(each(2), usd("9.99"), today, source, clock)
        putBack.type shouldBe MovementType.VENDOR_RETURN
        putBack.quantity shouldBe each(2)
        putBack.unitCost shouldBe usd("1.00")
        level.onHand shouldBe each(12)

        // Taking out more than is on hand leaves the average where it was.
        level.reverseReceipt(each(20), usd("2.00"), today, source, clock).after shouldBe
            StockPosition(each(-8), usd("1.00"))
        shouldThrow<QuantityNotPositive> { level.reverseReceipt(each(0), usd("1.00"), today, source, clock) }
        shouldThrow<QuantityNotPositive> { level.reverseVendorReturn(each(0), usd("1.00"), today, source, clock) }
    }

    private fun empty() = StockLevel.empty(itemId, warehouse, UnitOfMeasure.EACH, clock)

    private fun stocked(
        onHand: Int,
        averageCost: String,
    ) = StockLevel.rehydrate(
        StockLevelId.generate(clock),
        itemId,
        warehouse,
        StockPosition(each(onHand), usd(averageCost)),
        version = 1,
    )

    private fun each(amount: Int) = Quantity.of(amount, UnitOfMeasure.EACH)

    private fun usd(amount: String) = Money.of(amount, "USD")
}

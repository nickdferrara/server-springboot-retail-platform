package com.retailplatform.payables.domain

import com.retailplatform.payables.api.events.AccrualRecorded
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AccrualTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val receipt =
        AccruedReceipt(
            ReceiptId.generate(clock),
            "100001",
            PurchaseOrderId.generate(clock),
            "200001",
            VendorId.generate(clock),
            WarehouseId.generate(clock),
            receivedOn = today.minusDays(3),
        )

    @Test
    fun `a receipt is accrued at purchase order cost and fully open`() {
        val lines =
            listOf(
                AccrualLine.of(1, ItemId.generate(clock), 4, usd("18.00")),
                AccrualLine.of(2, ItemId.generate(clock), 5, usd("24.00")),
            )

        val accrual = Accrual.record(receipt, lines, clock)

        accrual.amount shouldBe usd("192.00")
        accrual.openAmount shouldBe usd("192.00")
        accrual.isOpen shouldBe true
        accrual.ageOn(today) shouldBe 3
        val event = accrual.releaseEvents().single().shouldBeInstanceOf<AccrualRecorded>()
        event.amount shouldBe BigDecimal("192.0000")
        event.currency shouldBe "USD"
        event.businessDate shouldBe today.minusDays(3)
        event.accrualId shouldBe accrual.id
        event.receiptId shouldBe receipt.receiptId
    }

    @Test
    fun `an accrual needs lines in one currency with cases and a cost`() {
        val itemId = ItemId.generate(clock)

        shouldThrow<IllegalArgumentException> { Accrual.record(receipt, emptyList(), clock) }
        shouldThrow<IllegalArgumentException> {
            val mixed =
                listOf(AccrualLine.of(1, itemId, 1, usd("1.00")), AccrualLine.of(2, itemId, 1, Money.of("1.00", "CAD")))
            Accrual.record(receipt, mixed, clock)
        }
        shouldThrow<IllegalArgumentException> { AccrualLine.of(1, itemId, 0, usd("1.00")) }
        shouldThrow<IllegalArgumentException> { AccrualLine.of(1, itemId, 1, usd("-1.00")) }
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}

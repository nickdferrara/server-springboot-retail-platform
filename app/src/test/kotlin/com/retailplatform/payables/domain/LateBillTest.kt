package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LateBillTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val ticket = BilledTicket(VendorId.generate(clock), StoreId.generate(clock), "T-1001")
    private val manager = MatchOverride.of("manager", "Vendor did deliver the units")

    @Test
    fun `a late bill accepted for payment is vouchered for its total and tells the ledger`() {
        val lateBill = recorded("14.25")
        lateBill.status shouldBe LateBillStatus.Open
        lateBill.recordedOn shouldBe today
        shouldThrow<IllegalStateException> { lateBill.payable() }
        shouldThrow<IllegalStateException> { lateBill.accepted(clock) }

        val paid = lateBill.pay(manager, clock)

        paid.status shouldBe LateBillStatus.Paid(today, manager)
        val voucher = paid.payable()
        voucher.amount shouldBe usd("14.25")
        voucher.invoice.invoiceId.value shouldBe lateBill.id.value
        voucher.invoice.vendorInvoiceNumber shouldBe VendorInvoiceNumber.of("DSD-1B")
        voucher.invoice.invoiceDate shouldBe today.minusDays(2)
        voucher.overrideApprovedBy shouldBe "manager"
        val event = paid.accepted(clock)
        event.amount shouldBe BigDecimal("14.2500")
        event.vendorInvoiceNumber shouldBe "DSD-1B"
        event.ticketNumber shouldBe "T-1001"
        event.businessDate shouldBe today
    }

    @Test
    fun `a late bill is settled once, by someone else than who recorded it`() {
        val lateBill = recorded("14.25")
        val recorder = MatchOverride.of("clerk", "Mine")

        shouldThrow<LateBillSettledByRecorder> { lateBill.pay(recorder, clock) }
        shouldThrow<LateBillSettledByRecorder> { lateBill.reject(recorder, clock) }
        val rejected = lateBill.reject(manager, clock)

        rejected.status shouldBe LateBillStatus.Rejected(today, manager)
        shouldThrow<IllegalStateException> { rejected.payable() }
        shouldThrow<LateBillAlreadySettled> { rejected.pay(manager, clock) }
        shouldThrow<LateBillAlreadySettled> { lateBill.pay(manager, clock).reject(manager, clock) }
        shouldThrow<IllegalArgumentException> { recorded("0.00") }
    }

    private fun recorded(amount: String) =
        LateBill.record(
            LateBillDetails(ticket, VendorInvoiceNumber.of("DSD-1B"), today.minusDays(2), usd(amount)),
            "clerk",
            clock,
        )

    private fun usd(amount: String) = Money.of(amount, "USD")
}

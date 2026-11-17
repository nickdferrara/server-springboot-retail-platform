package com.retailplatform.payables.domain

import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency

class DeductionNoticeTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-17T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val ticket = BilledTicket(VendorId.generate(clock), StoreId.generate(clock), "T-1001")
    private val cola = ItemId.generate(clock)
    private val chips = ItemId.generate(clock)
    private val extra = ItemId.generate(clock)
    private val match = TwoWayMatch(MatchTolerance.of(Percentage.of("2"), BigDecimal("10.00")))
    private val approval = MatchOverride.of("manager", "Store count confirmed with the driver")

    @Test
    fun `a notice lists each item billed beyond what was received, and adds up to the deduction`() {
        val accrual = accrual(line(cola, 18, "1.25"), line(chips, 10, "0.80"))
        // Bills 20 colas where 18 came, 8 chips where 10 came, and an item that never came.
        val invoice = invoice(line(cola, 20, "1.26"), line(chips, 8, "0.80"), line(extra, 5, "2.00"))
        val payment = match.shortPay(invoice, accrual)

        val notice =
            DeductionNotice.raise(
                DeductionNoticeNumber.of("100001"),
                invoice.deducted(),
                approval,
                match.deductionLines(invoice, accrual),
                clock,
            )

        notice.lines.map { Triple(it.itemId, it.unitsDeducted, it.amount) } shouldBe
            listOf(Triple(cola, 2, usd("2.52")), Triple(extra, 5, usd("10.00")))
        notice.amount shouldBe payment.deduction
        notice.raisedOn shouldBe today
        notice.reason shouldBe "Store count confirmed with the driver"
        notice.invoice.number shouldBe VendorInvoiceNumber.of("DSD-1")
        notice.invoice.ticket shouldBe ticket
        notice.sending shouldBe null
        shouldThrow<IllegalArgumentException> {
            DeductionNotice.raise(DeductionNoticeNumber.of("100002"), invoice.deducted(), approval, emptyList(), clock)
        }
        shouldThrow<IllegalArgumentException> { DeductionLine.of(cola, 5, 5, usd("1.00")) }
        shouldThrow<IllegalArgumentException> { DeductionNoticeNumber.of("N-1") }
    }

    @Test
    fun `a notice is recorded as sent once, on a day between when it was raised and today`() {
        val accrual = accrual(line(cola, 18, "1.25"))
        val invoice = invoice(line(cola, 20, "1.25"))
        val lines = match.deductionLines(invoice, accrual)
        val notice =
            DeductionNotice.raise(
                DeductionNoticeNumber.of("100003"),
                invoice.deducted(),
                approval,
                lines,
                clock,
            )
        val sending = sentOn(today)

        shouldThrow<DeductionNoticeSentDateInvalid> { notice.sent(sentOn(today.plusDays(1)), clock) }
        shouldThrow<DeductionNoticeSentDateInvalid> { notice.sent(sentOn(today.minusDays(1)), clock) }
        val sent = notice.sent(sending, clock)

        sent.sending shouldBe sending
        sending.recipient shouldBe "ar@vendor.example"
        shouldThrow<DeductionNoticeAlreadySent> { sent.sent(sending, clock) }

        // A sending recorded in error is withdrawn: the notice is unsent again and keeps what was withdrawn.
        val why = MatchOverride.of("clerk", "Sent to the wrong address")
        shouldThrow<DeductionNoticeNotSent> { notice.withdrawSending(why, clock) }
        val unsent = sent.withdrawSending(why, clock)
        unsent.sending shouldBe null
        unsent.withdrawn shouldBe listOf(WithdrawnSending(sending, today, why))
        val again = unsent.sent(sentOn(today), clock).withdrawSending(why, clock)
        again.withdrawn.size shouldBe 2
        shouldThrow<DeductionNoticeNotSent> { again.withdrawSending(why, clock) }
        shouldThrow<IllegalArgumentException> { NoticeSending.of(today, NoticeChannel.POST, " ", "clerk") }
    }

    private fun sentOn(day: BusinessDate) = NoticeSending.of(day, NoticeChannel.EMAIL, " ar@vendor.example ", "clerk")

    private fun invoice(vararg lines: TicketAmountLine) =
        DirectDeliveryInvoice.enter(
            TicketInvoiceDetails(ticket, VendorInvoiceNumber.of("DSD-1"), today, lines.toList()),
            "clerk",
            clock,
        )

    private fun accrual(vararg lines: TicketAmountLine) =
        DirectDeliveryAccrual.record(
            DeliveredTicket(
                DirectDeliveryId.generate(clock),
                "100001",
                ticket.vendorId,
                ticket.storeId,
                ticket.ticketNumber,
                today.minusDays(2),
            ),
            Currency.getInstance("USD"),
            lines.toList(),
        )

    private fun line(
        itemId: ItemId,
        units: Int,
        unitCost: String,
    ) = TicketAmountLine.of(itemId, units, usd(unitCost))

    private fun usd(amount: String) = Money.of(amount, "USD")
}

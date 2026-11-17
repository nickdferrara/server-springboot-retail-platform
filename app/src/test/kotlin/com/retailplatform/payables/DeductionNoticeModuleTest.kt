package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.application.DeductionNoticeQueries
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.NoticeSent
import com.retailplatform.payables.application.RecordDeductionNoticeSent
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.ShortPayDirectDeliveryInvoice
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeductionNoticeAlreadySent
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeductionNoticeSentDateInvalid
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.NoticeChannel
import com.retailplatform.payables.domain.NoticeSending
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.VendorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.time.Clock

/** Deduction notices: what a vendor is told was held back from an invoice that was paid short. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class DeductionNoticeModuleTest(
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val enterDirectDeliveryInvoice: EnterDirectDeliveryInvoice,
    @Autowired private val shortPayInvoice: ShortPayDirectDeliveryInvoice,
    @Autowired private val recordSent: RecordDeductionNoticeSent,
    @Autowired private val notices: DeductionNoticeQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)

    @Test
    fun `a short-pay raises a numbered notice of what was held back, and a clerk records that it was sent`() {
        val vendorId = VendorId.generate(clock)
        val ticket = BilledTicket(vendorId, StoreId.generate(clock), "T-120")
        val cola = ItemId.generate(clock)
        val chips = ItemId.generate(clock)
        delivered(ticket, TicketAmountLine.of(cola, 18, usd("1.25")), TicketAmountLine.of(chips, 10, usd("0.80")))
        // Bills 20 colas where 18 came; the chips are billed right.
        val invoiceId =
            enterDirectDeliveryInvoice.handle(
                TicketInvoiceDetails(
                    ticket,
                    VendorInvoiceNumber.of("DSD-120"),
                    today,
                    listOf(TicketAmountLine.of(cola, 20, usd("1.25")), TicketAmountLine.of(chips, 10, usd("0.80"))),
                ),
            )
        shouldThrow<ResourceNotFound> { notices.forInvoice(invoiceId) }

        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        shortPayInvoice.handle(invoiceId, "Store count confirmed with the driver")

        val notice = notices.forInvoice(invoiceId)
        (notice.number.value.toLong() >= FIRST_NOTICE_NUMBER) shouldBe true
        notice.amount shouldBe usd("2.50")
        notice.reason shouldBe "Store count confirmed with the driver"
        notice.raisedOn shouldBe today
        notice.invoice.number shouldBe VendorInvoiceNumber.of("DSD-120")
        notice.invoice.ticket shouldBe ticket
        val line = notice.lines.single()
        line.itemId shouldBe cola
        line.unitsBilled shouldBe 20
        line.unitsReceived shouldBe 18
        line.unitsDeducted shouldBe 2
        notices.list(vendorId, unsentOnly = true, PageQuery.of()).items.map { it.id } shouldBe listOf(notice.id)

        // Recording that it was sent is the clerk's, not the manager's; it is recorded once.
        val sent = NoticeSent(today, NoticeChannel.EMAIL, "ar@vendor.example")
        shouldThrow<AccessDeniedException> { recordSent.handle(notice.id, sent) }
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        shouldThrow<DeductionNoticeSentDateInvalid> {
            recordSent.handle(
                notice.id,
                sent.copy(sentOn = today.plusDays(1)),
            )
        }
        recordSent.handle(notice.id, sent)

        notices.find(notice.id).sending shouldBe
            NoticeSending.of(today, NoticeChannel.EMAIL, "ar@vendor.example", "clerk")
        notices.find(notice.id).lines shouldBe notice.lines
        notices.list(vendorId, unsentOnly = true, PageQuery.of()).total shouldBe 0
        notices.list(vendorId, unsentOnly = false, PageQuery.of()).total shouldBe 1
        shouldThrow<DeductionNoticeAlreadySent> { recordSent.handle(notice.id, sent) }
        shouldThrow<ResourceNotFound> { recordSent.handle(DeductionNoticeId.generate(clock), sent) }
        shouldThrow<ResourceNotFound> { notices.forInvoice(InvoiceId.generate(clock)) }
    }

    private fun delivered(
        ticket: BilledTicket,
        vararg lines: TicketAmountLine,
    ) {
        val checkedIn =
            DeliveredTicket(
                DirectDeliveryId.generate(clock),
                "100096",
                ticket.vendorId,
                ticket.storeId,
                ticket.ticketNumber,
                today,
            )
        recordDirectDelivery.handle(RecordDirectDeliveryCommand(checkedIn, lines.toList(), emptyList()))
    }

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")

    private companion object {
        const val FIRST_NOTICE_NUMBER = 100001L
    }
}

package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.api.events.DirectDeliveryLateBillAccepted
import com.retailplatform.payables.application.AcceptUnderBilledDirectDeliveryInvoice
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.LateBillQueries
import com.retailplatform.payables.application.PayLateBill
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.RecordLateBill
import com.retailplatform.payables.application.RejectLateBill
import com.retailplatform.payables.application.VoucherQueries
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.LateBillAlreadySettled
import com.retailplatform.payables.domain.LateBillDetails
import com.retailplatform.payables.domain.LateBillId
import com.retailplatform.payables.domain.LateBillInAnotherCurrency
import com.retailplatform.payables.domain.LateBillSettledByRecorder
import com.retailplatform.payables.domain.LateBillStatus
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.TicketAlreadyInvoiced
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.TicketNotClosed
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
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
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.Clock

/** Late bills: a vendor's further bill for a delivery ticket that was already invoiced and closed. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class LateBillModuleTest(
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val enterDirectDeliveryInvoice: EnterDirectDeliveryInvoice,
    @Autowired private val acceptUnderBilled: AcceptUnderBilledDirectDeliveryInvoice,
    @Autowired private val recordLateBill: RecordLateBill,
    @Autowired private val payLateBill: PayLateBill,
    @Autowired private val rejectLateBill: RejectLateBill,
    @Autowired private val lateBills: LateBillQueries,
    @Autowired private val vouchers: VoucherQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)

    @Test
    fun `a late bill for a closed ticket is recorded by a clerk and paid by a manager who did not record it`(
        scenario: Scenario,
    ) {
        val vendorId = VendorId.generate(clock)
        val ticket = closedUnderBilled(vendorId, "T-110", "DSD-110")

        // The rest cannot be entered as an invoice; the clerk records it as a late bill instead.
        shouldThrow<TicketAlreadyInvoiced> {
            enterDirectDeliveryInvoice.handle(invoice(ticket, "DSD-110B", ItemId.generate(clock), 5, "1.25"))
        }
        val id = recordLateBill.handle(lateBill(ticket, "DSD-110B", "6.25"))
        lateBills.find(id).status shouldBe LateBillStatus.Open
        lateBills.find(id).recordedBy shouldBe "clerk"
        shouldThrow<InvoiceAlreadyEntered> { recordLateBill.handle(lateBill(ticket, "DSD-110B", "6.25")) }
        shouldThrow<InvoiceAlreadyEntered> { recordLateBill.handle(lateBill(ticket, "DSD-110", "6.25")) }

        // A clerk does not settle it, nor does a manager who recorded it.
        shouldThrow<AccessDeniedException> { payLateBill.handle(id, "Vendor did deliver them") }
        shouldThrow<AccessDeniedException> { rejectLateBill.handle(id, "Not owed") }
        actingAs("clerk", Roles.ACCOUNTING_MANAGER)
        shouldThrow<LateBillSettledByRecorder> { payLateBill.handle(id, "Vendor did deliver them") }

        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        scenario
            .stimulate(Runnable { payLateBill.handle(id, "Vendor did deliver them") })
            .andWaitForEventOfType(DirectDeliveryLateBillAccepted::class.java)
            .matching { it.lateBillId == id.value }
            .toArriveAndVerify { event ->
                event.amount shouldBe BigDecimal("6.2500")
                event.vendorInvoiceNumber shouldBe "DSD-110B"
                event.ticketNumber shouldBe "T-110"
            }

        lateBills.find(id).status shouldBe
            LateBillStatus.Paid(today, MatchOverride.of("manager", "Vendor did deliver them"))
        val voucher = vouchers.list(vendorId, null, PageQuery.of()).items.single { it.amount == usd("6.25") }
        voucher.invoice.vendorInvoiceNumber shouldBe VendorInvoiceNumber.of("DSD-110B")
        shouldThrow<LateBillAlreadySettled> { rejectLateBill.handle(id, "Changed my mind") }
        shouldThrow<ResourceNotFound> { payLateBill.handle(LateBillId.generate(clock), "Missing") }
    }

    @Test
    fun `a rejected late bill stays on record and nothing is paid`() {
        val vendorId = VendorId.generate(clock)
        val ticket = closedUnderBilled(vendorId, "T-111", "DSD-111")
        val id = recordLateBill.handle(lateBill(ticket, "DSD-111B", "6.25"))
        lateBills.list(vendorId, openOnly = true, PageQuery.of()).total shouldBe 1

        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        rejectLateBill.handle(id, "Ticket was closed with the vendor's agreement")

        lateBills.find(id).status shouldBe
            LateBillStatus.Rejected(today, MatchOverride.of("manager", "Ticket was closed with the vendor's agreement"))
        lateBills.list(vendorId, openOnly = true, PageQuery.of()).total shouldBe 0
        lateBills.list(vendorId, openOnly = false, PageQuery.of()).total shouldBe 1
        // Only the first invoice has a voucher.
        vouchers.list(vendorId, null, PageQuery.of()).total shouldBe 1
        shouldThrow<LateBillAlreadySettled> { payLateBill.handle(id, "Too late") }
    }

    @Test
    fun `a late bill needs a ticket whose invoice is payable, in that invoice's currency`() {
        val vendorId = VendorId.generate(clock)
        val storeId = StoreId.generate(clock)
        val cola = ItemId.generate(clock)

        // No invoice at all, then an invoice still waiting in exception.
        val notInvoiced = BilledTicket(vendorId, storeId, "T-112")
        shouldThrow<TicketNotClosed> { recordLateBill.handle(lateBill(notInvoiced, "DSD-112B", "6.25")) }
        delivered(notInvoiced, TicketAmountLine.of(cola, 20, usd("1.25")))
        enterDirectDeliveryInvoice.handle(invoice(notInvoiced, "DSD-112", cola, 15, "1.25"))
        shouldThrow<TicketNotClosed> { recordLateBill.handle(lateBill(notInvoiced, "DSD-112B", "6.25")) }

        val closed = closedUnderBilled(vendorId, "T-113", "DSD-113")
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        shouldThrow<LateBillInAnotherCurrency> {
            recordLateBill.handle(
                LateBillDetails(closed, VendorInvoiceNumber.of("DSD-113B"), today, Money.of("6.25", "CAD")),
            )
        }
        actingAs("buyer", Roles.BUYER)
        shouldThrow<AccessDeniedException> { recordLateBill.handle(lateBill(closed, "DSD-113B", "6.25")) }
    }

    /** A ticket of 20 colas at 1.25, billed for 15 and accepted under-billed, so 6.25 was never billed. */
    private fun closedUnderBilled(
        vendorId: VendorId,
        ticketNumber: String,
        number: String,
    ): BilledTicket {
        val ticket = BilledTicket(vendorId, StoreId.generate(clock), ticketNumber)
        val cola = ItemId.generate(clock)
        delivered(ticket, TicketAmountLine.of(cola, 20, usd("1.25")))
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        val invoiceId = enterDirectDeliveryInvoice.handle(invoice(ticket, number, cola, 15, "1.25"))
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        acceptUnderBilled.handle(invoiceId, "Vendor will not bill the rest")
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        return ticket
    }

    private fun delivered(
        ticket: BilledTicket,
        line: TicketAmountLine,
    ) {
        val checkedIn =
            DeliveredTicket(
                DirectDeliveryId.generate(clock),
                "100095",
                ticket.vendorId,
                ticket.storeId,
                ticket.ticketNumber,
                today,
            )
        recordDirectDelivery.handle(RecordDirectDeliveryCommand(checkedIn, listOf(line), emptyList()))
    }

    private fun invoice(
        ticket: BilledTicket,
        number: String,
        itemId: ItemId,
        units: Int,
        unitCost: String,
    ) = TicketInvoiceDetails(
        ticket,
        VendorInvoiceNumber.of(number),
        today,
        listOf(TicketAmountLine.of(itemId, units, usd(unitCost))),
    )

    private fun lateBill(
        ticket: BilledTicket,
        number: String,
        amount: String,
    ) = LateBillDetails(ticket, VendorInvoiceNumber.of(number), today, usd(amount))

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}

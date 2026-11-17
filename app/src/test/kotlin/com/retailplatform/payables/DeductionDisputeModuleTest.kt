package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.api.events.DeductionDisputeConceded
import com.retailplatform.payables.application.ConcedeDeduction
import com.retailplatform.payables.application.DeductionDisputeQueries
import com.retailplatform.payables.application.DeductionNoticeQueries
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.RecordDeductionDispute
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.ShortPayDirectDeliveryInvoice
import com.retailplatform.payables.application.UpholdDeduction
import com.retailplatform.payables.application.VoucherQueries
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.ConcessionOutOfRange
import com.retailplatform.payables.domain.DeductionAlreadyDisputed
import com.retailplatform.payables.domain.DeductionDisputeId
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DisputeAlreadyDecided
import com.retailplatform.payables.domain.DisputeClaim
import com.retailplatform.payables.domain.DisputeDateInvalid
import com.retailplatform.payables.domain.DisputeDecidedByDeductor
import com.retailplatform.payables.domain.DisputeStatus
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.TicketAmountLine
import com.retailplatform.payables.domain.TicketInvoiceDetails
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

/** Disputes: a vendor contests a deduction, and accounting upholds it or concedes some or all of it. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class DeductionDisputeModuleTest(
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val enterDirectDeliveryInvoice: EnterDirectDeliveryInvoice,
    @Autowired private val shortPayInvoice: ShortPayDirectDeliveryInvoice,
    @Autowired private val notices: DeductionNoticeQueries,
    @Autowired private val recordDispute: RecordDeductionDispute,
    @Autowired private val upholdDeduction: UpholdDeduction,
    @Autowired private val concedeDeduction: ConcedeDeduction,
    @Autowired private val disputes: DeductionDisputeQueries,
    @Autowired private val vouchers: VoucherQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)

    @Test
    fun `a clerk records a dispute and another manager concedes part of the deduction`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val noticeId = shortPaid(vendorId, "T-130", "DSD-130")
        shouldThrow<ResourceNotFound> { disputes.forNotice(noticeId) }

        // A claim cannot predate the deduction or reuse one of the vendor's document numbers.
        shouldThrow<DisputeDateInvalid> { recordDispute.handle(noticeId, claim("CLAIM-130", today.minusDays(1))) }
        shouldThrow<InvoiceAlreadyEntered> { recordDispute.handle(noticeId, claim("DSD-130")) }
        val id = recordDispute.handle(noticeId, claim("CLAIM-130"))
        val recorded = disputes.forNotice(noticeId)
        recorded.id shouldBe id
        recorded.status shouldBe DisputeStatus.Open
        recorded.deduction.amount shouldBe usd("2.50")
        recorded.deduction.deductedBy shouldBe "manager"
        shouldThrow<DeductionAlreadyDisputed> { recordDispute.handle(noticeId, claim("CLAIM-130B")) }

        // A clerk does not decide, nor does the manager who made the deduction, and no more is conceded than held.
        shouldThrow<AccessDeniedException> { concedeDeduction.handle(id, usd("1.25"), "One unit was delivered") }
        shouldThrow<AccessDeniedException> { upholdDeduction.handle(id, "Count stands") }
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        shouldThrow<DisputeDecidedByDeductor> { concedeDeduction.handle(id, usd("1.25"), "One unit was delivered") }
        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        shouldThrow<ConcessionOutOfRange> { concedeDeduction.handle(id, usd("2.51"), "Too much") }

        scenario
            .stimulate(Runnable { concedeDeduction.handle(id, usd("1.25"), "One unit was delivered") })
            .andWaitForEventOfType(DeductionDisputeConceded::class.java)
            .matching { it.disputeId == id.value }
            .toArriveAndVerify { event ->
                event.amount shouldBe BigDecimal("1.2500")
                event.vendorReference shouldBe "CLAIM-130"
                event.ticketNumber shouldBe "T-130"
            }

        disputes.find(id).status shouldBe
            DisputeStatus.Conceded(today, MatchOverride.of("controller", "One unit was delivered"), usd("1.25"))
        val voucher = vouchers.list(vendorId, null, PageQuery.of()).items.single { it.amount == usd("1.25") }
        voucher.invoice.vendorInvoiceNumber shouldBe VendorInvoiceNumber.of("CLAIM-130")
        shouldThrow<DisputeAlreadyDecided> { upholdDeduction.handle(id, "Changed my mind") }
        shouldThrow<ResourceNotFound> { upholdDeduction.handle(DeductionDisputeId.generate(clock), "Missing") }
    }

    @Test
    fun `an upheld deduction stands and nothing is paid`() {
        val vendorId = VendorId.generate(clock)
        val noticeId = shortPaid(vendorId, "T-131", "DSD-131")
        val id = recordDispute.handle(noticeId, claim("CLAIM-131"))
        disputes.list(vendorId, openOnly = true, PageQuery.of()).total shouldBe 1

        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        upholdDeduction.handle(id, "Store's count was witnessed by the driver")

        disputes.find(id).status shouldBe
            DisputeStatus.Upheld(today, MatchOverride.of("controller", "Store's count was witnessed by the driver"))
        disputes.list(vendorId, openOnly = true, PageQuery.of()).total shouldBe 0
        disputes.list(vendorId, openOnly = false, PageQuery.of()).total shouldBe 1
        // Only the short-paid invoice has a voucher.
        vouchers.list(vendorId, null, PageQuery.of()).total shouldBe 1
        shouldThrow<DisputeAlreadyDecided> { concedeDeduction.handle(id, usd("1.25"), "Too late") }
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        shouldThrow<ResourceNotFound> { recordDispute.handle(DeductionNoticeId.generate(clock), claim("CLAIM-X")) }
    }

    /**
     * A ticket of 18 colas at 1.25, billed for 20 and paid short by "manager": 2.50 is held back. Returns the
     * deduction notice's id, with the clerk signed in again.
     */
    private fun shortPaid(
        vendorId: VendorId,
        ticketNumber: String,
        number: String,
    ): DeductionNoticeId {
        val ticket = BilledTicket(vendorId, StoreId.generate(clock), ticketNumber)
        val cola = ItemId.generate(clock)
        val checkedIn =
            DeliveredTicket(DirectDeliveryId.generate(clock), "100097", vendorId, ticket.storeId, ticketNumber, today)
        recordDirectDelivery.handle(
            RecordDirectDeliveryCommand(checkedIn, listOf(TicketAmountLine.of(cola, 18, usd("1.25"))), emptyList()),
        )
        val invoiceId =
            enterDirectDeliveryInvoice.handle(
                TicketInvoiceDetails(
                    ticket,
                    VendorInvoiceNumber.of(number),
                    today,
                    listOf(TicketAmountLine.of(cola, 20, usd("1.25"))),
                ),
            )
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        shortPayInvoice.handle(invoiceId, "Store count confirmed with the driver")
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        return notices.forInvoice(invoiceId).id
    }

    private fun claim(
        reference: String,
        receivedOn: BusinessDate = today,
    ) = DisputeClaim.of(receivedOn, VendorInvoiceNumber.of(reference), "We delivered 19 of the 20 colas billed")

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}

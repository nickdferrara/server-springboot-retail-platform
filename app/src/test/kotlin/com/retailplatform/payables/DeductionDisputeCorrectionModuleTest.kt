package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.application.ConcedeDeduction
import com.retailplatform.payables.application.DeductionDisputeQueries
import com.retailplatform.payables.application.DeductionNoticeQueries
import com.retailplatform.payables.application.EnterDirectDeliveryInvoice
import com.retailplatform.payables.application.OpenItemAgeing
import com.retailplatform.payables.application.RecordDeductionDispute
import com.retailplatform.payables.application.RecordDirectDelivery
import com.retailplatform.payables.application.RecordDirectDeliveryCommand
import com.retailplatform.payables.application.ReopenDeductionDispute
import com.retailplatform.payables.application.ShortPayDirectDeliveryInvoice
import com.retailplatform.payables.application.UpholdDeduction
import com.retailplatform.payables.application.VoucherQueries
import com.retailplatform.payables.application.WithdrawDeductionDispute
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DeductionAlreadyDisputed
import com.retailplatform.payables.domain.DeductionDisputeId
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeliveredTicket
import com.retailplatform.payables.domain.DisputeAlreadyDecided
import com.retailplatform.payables.domain.DisputeClaim
import com.retailplatform.payables.domain.DisputeDecidedByDeductor
import com.retailplatform.payables.domain.DisputeNotUpheld
import com.retailplatform.payables.domain.DisputeReopening
import com.retailplatform.payables.domain.DisputeStatus
import com.retailplatform.payables.domain.DisputeWithdrawn
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
import io.kotest.matchers.shouldNotBe
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

/** Correcting disputes: one recorded in error is withdrawn, and one that was upheld is reopened. */
@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class DeductionDisputeCorrectionModuleTest(
    @Autowired private val recordDirectDelivery: RecordDirectDelivery,
    @Autowired private val enterDirectDeliveryInvoice: EnterDirectDeliveryInvoice,
    @Autowired private val shortPayInvoice: ShortPayDirectDeliveryInvoice,
    @Autowired private val notices: DeductionNoticeQueries,
    @Autowired private val recordDispute: RecordDeductionDispute,
    @Autowired private val upholdDeduction: UpholdDeduction,
    @Autowired private val concedeDeduction: ConcedeDeduction,
    @Autowired private val reopenDispute: ReopenDeductionDispute,
    @Autowired private val withdrawDispute: WithdrawDeductionDispute,
    @Autowired private val disputes: DeductionDisputeQueries,
    @Autowired private val ageing: OpenItemAgeing,
    @Autowired private val vouchers: VoucherQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)

    @Test
    fun `a dispute recorded in error is withdrawn and the deduction is disputed again`() {
        val vendorId = VendorId.generate(clock)
        val noticeId = shortPaid(vendorId, "T-170", "DSD-170")
        val mistaken = recordDispute.handle(noticeId, claim("CLAIM-170"))
        shouldThrow<DeductionAlreadyDisputed> { recordDispute.handle(noticeId, claim("CLAIM-170B")) }

        // A manager who is not a clerk does not withdraw.
        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        shouldThrow<AccessDeniedException> { withdrawDispute.handle(mistaken, "Not mine to withdraw") }
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        withdrawDispute.handle(mistaken, "Vendor's letter was about another ticket")

        val withdrawn = disputes.find(mistaken)
        withdrawn.status shouldBe
            DisputeStatus.Withdrawn(today, MatchOverride.of("clerk", "Vendor's letter was about another ticket"))
        ageing.ageOf(withdrawn) shouldBe null
        disputes.concedable(withdrawn) shouldBe null
        shouldThrow<ResourceNotFound> { disputes.forNotice(noticeId) }
        disputes.list(vendorId, openOnly = true, PageQuery.of()).total shouldBe 0
        disputes.list(vendorId, openOnly = false, PageQuery.of()).total shouldBe 1
        shouldThrow<DisputeWithdrawn> { withdrawDispute.handle(mistaken, "Again") }

        // The deduction is disputed again, and the vendor's reference is free to use.
        val again = recordDispute.handle(noticeId, claim("CLAIM-170"))
        again shouldNotBe mistaken
        disputes.forNotice(noticeId).id shouldBe again
        disputes.list(vendorId, openOnly = false, PageQuery.of()).total shouldBe 2

        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        shouldThrow<DisputeWithdrawn> { upholdDeduction.handle(mistaken, "Count stands") }
        shouldThrow<DisputeWithdrawn> { concedeDeduction.handle(mistaken, usd("1.25"), "One unit") }
        concedeDeduction.handle(again, usd("1.25"), "One unit was delivered")
        vouchers.list(vendorId, null, PageQuery.of()).items.count { it.amount == usd("1.25") } shouldBe 1
        actingAs("clerk", Roles.ACCOUNTING_CLERK)
        shouldThrow<DisputeAlreadyDecided> { withdrawDispute.handle(again, "Too late") }
        shouldThrow<ResourceNotFound> { withdrawDispute.handle(DeductionDisputeId.generate(clock), "Missing") }
    }

    @Test
    fun `an upheld dispute is reopened by another manager and then conceded`() {
        val vendorId = VendorId.generate(clock)
        val noticeId = shortPaid(vendorId, "T-171", "DSD-171")
        val id = recordDispute.handle(noticeId, claim("CLAIM-171"))
        shouldThrow<AccessDeniedException> { reopenDispute.handle(id, "Clerks do not reopen") }

        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        shouldThrow<DisputeNotUpheld> { reopenDispute.handle(id, "Nothing to reopen yet") }
        upholdDeduction.handle(id, "Store's count was witnessed by the driver")
        disputes.list(vendorId, openOnly = true, PageQuery.of()).total shouldBe 0
        actingAs("manager", Roles.ACCOUNTING_MANAGER)
        shouldThrow<DisputeDecidedByDeductor> { reopenDispute.handle(id, "Let me look again") }
        actingAs("controller", Roles.ACCOUNTING_MANAGER)
        reopenDispute.handle(id, "Vendor sent the driver's signed ticket")

        val reopened = disputes.find(id)
        reopened.status shouldBe DisputeStatus.Open
        reopened.reopenings shouldBe
            listOf(
                DisputeReopening(
                    today,
                    MatchOverride.of("controller", "Store's count was witnessed by the driver"),
                    today,
                    MatchOverride.of("controller", "Vendor sent the driver's signed ticket"),
                ),
            )
        ageing.ageOf(reopened)?.days shouldBe 0
        disputes.concedable(reopened) shouldBe usd("2.50")
        disputes.forNotice(noticeId).id shouldBe id
        disputes.list(vendorId, openOnly = true, PageQuery.of()).total shouldBe 1
        // Only the short-paid invoice has a voucher until the reopened dispute is conceded.
        vouchers.list(vendorId, null, PageQuery.of()).total shouldBe 1

        concedeDeduction.handle(id, usd("1.25"), "One unit was delivered")
        val conceded = disputes.find(id)
        conceded.status shouldBe
            DisputeStatus.Conceded(today, MatchOverride.of("controller", "One unit was delivered"), usd("1.25"))
        conceded.reopenings shouldBe reopened.reopenings
        vouchers.list(vendorId, null, PageQuery.of()).total shouldBe 2
        shouldThrow<DisputeNotUpheld> { reopenDispute.handle(id, "Money has been released") }
        shouldThrow<ResourceNotFound> { reopenDispute.handle(DeductionDisputeId.generate(clock), "Missing") }
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

    private fun claim(reference: String) =
        DisputeClaim.of(today, VendorInvoiceNumber.of(reference), "We delivered 19 of the 20 colas billed")

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}

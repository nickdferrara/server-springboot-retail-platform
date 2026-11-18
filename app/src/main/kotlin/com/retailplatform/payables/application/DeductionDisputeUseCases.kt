package com.retailplatform.payables.application

import com.retailplatform.payables.domain.AdjustmentCoveredByConcession
import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.ConcessionOutOfRange
import com.retailplatform.payables.domain.DeductionAlreadyDisputed
import com.retailplatform.payables.domain.DeductionAlreadyRecovered
import com.retailplatform.payables.domain.DeductionDispute
import com.retailplatform.payables.domain.DeductionDisputeId
import com.retailplatform.payables.domain.DeductionNotice
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeductionRecovery
import com.retailplatform.payables.domain.DeliveryAdjustment
import com.retailplatform.payables.domain.DeliveryAdjustmentKind
import com.retailplatform.payables.domain.DisputeAlreadyDecided
import com.retailplatform.payables.domain.DisputeClaim
import com.retailplatform.payables.domain.DisputeDateInvalid
import com.retailplatform.payables.domain.DisputeDecidedByDeductor
import com.retailplatform.payables.domain.DisputeNotUpheld
import com.retailplatform.payables.domain.DisputeStatus
import com.retailplatform.payables.domain.DisputeWithdrawn
import com.retailplatform.payables.domain.DisputedDeduction
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.TicketInvoiceStatus
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Port: where vendors' disputes of deductions are kept. A deduction notice has at most one dispute that stands;
 * those withdrawn as recorded in error are kept beside it.
 */
interface DeductionDisputeRepository {
    /** Inserts a new dispute or updates an existing one; a stale version is refused. */
    fun save(dispute: DeductionDispute)

    fun findById(id: DeductionDisputeId): DeductionDispute?

    /** The dispute that stands of the deduction a notice told the vendor of, if there is one. */
    fun findByNotice(noticeId: DeductionNoticeId): DeductionDispute?

    /** The dispute that stands of the deduction on a vendor's ticket at a store, if there is one. */
    fun findByTicket(ticket: BilledTicket): DeductionDispute?

    /** Whether a dispute that stands is under the vendor's claim [reference]. */
    fun existsReference(
        vendorId: VendorId,
        reference: VendorInvoiceNumber,
    ): Boolean

    /** A vendor's disputes, withdrawn ones included, the oldest first; only those still waiting if [openOnly]. */
    fun findByVendor(
        vendorId: VendorId,
        openOnly: Boolean,
        query: PageQuery,
    ): Page<DeductionDispute>
}

/**
 * Keeps the units a short-pay held back from being paid for twice: once through an adjustment, after the store
 * corrected its receipt, and again through a conceded dispute.
 */
@Component
class DeductionRecoveryGuard(
    private val disputes: DeductionDisputeRepository,
    private val notices: DeductionNoticeRepository,
    private val adjustments: DeliveryAdjustmentRepository,
) {
    /**
     * What of [dispute]'s deduction can still be conceded. Adjustments on the ticket that are open or paid count
     * as covering their units; to concede those, the adjustment is written off first.
     */
    fun concedable(dispute: DeductionDispute): Money {
        val notice = checkNotNull(notices.findById(dispute.deduction.noticeId)) { "A dispute has a notice" }
        return DeductionRecovery.concedable(notice.lines, adjustments.findByTicket(dispute.deduction.ticket))
    }

    /**
     * Checks that [adjustment], as it is about to be paid, would not pay for units a concession already gave
     * back. Only the units being paid count, with those of adjustments on the ticket already paid; others still
     * open do not stand in its way.
     *
     * @throws AdjustmentCoveredByConcession if what was conceded is more than would be left to concede.
     */
    fun requirePayable(adjustment: DeliveryAdjustment) {
        if (adjustment.kind != DeliveryAdjustmentKind.RECEIVED_AFTER_INVOICE) return
        val ticket = BilledTicket(adjustment.ticket.vendorId, adjustment.ticket.storeId, adjustment.ticket.ticketNumber)
        val dispute = disputes.findByTicket(ticket)
        val conceded = (dispute?.status as? DisputeStatus.Conceded)?.amount ?: return
        val notice = checkNotNull(notices.findById(dispute.deduction.noticeId)) { "A dispute has a notice" }
        val paid = adjustments.findByTicket(ticket).filter { !it.isOpen && it.id != adjustment.id }
        if (conceded > DeductionRecovery.concedable(notice.lines, paid + adjustment)) {
            throw AdjustmentCoveredByConcession(ticket.ticketNumber, notice.number)
        }
    }
}

/**
 * Use case: record that a vendor disputes a deduction. A clerk records when the claim arrived, the vendor's
 * reference for it, and what the vendor says, against the deduction notice. A deduction has one dispute at a
 * time: it is disputed again only after the earlier dispute was withdrawn.
 */
@Service
class RecordDeductionDispute(
    private val disputes: DeductionDisputeRepository,
    private val notices: DeductionNoticeRepository,
    private val invoices: DirectDeliveryInvoiceRepository,
    private val documentNumbers: VendorDocumentNumbers,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws DeductionAlreadyDisputed if the deduction has a dispute that was not withdrawn.
     * @throws DisputeDateInvalid if the claim arrived before the deduction was made, or after today.
     * @throws InvoiceAlreadyEntered if the vendor's reference was already used for another of its documents.
     */
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(
        noticeId: DeductionNoticeId,
        claim: DisputeClaim,
    ): DeductionDisputeId {
        val notice = notices.findById(noticeId) ?: throw ResourceNotFound("Deduction notice", noticeId.toString())
        if (disputes.findByNotice(noticeId) != null) throw DeductionAlreadyDisputed(notice.number)
        requireAcceptable(claim, notice)

        val dispute = DeductionDispute.record(disputed(notice), claim, currentUser.name, clock)
        disputes.save(dispute)
        return dispute.id
    }

    private fun requireAcceptable(
        claim: DisputeClaim,
        notice: DeductionNotice,
    ) {
        if (claim.receivedOn < notice.raisedOn) throw DisputeDateInvalid(notice.number)
        if (documentNumbers.isUsed(notice.invoice.ticket.vendorId, claim.reference)) {
            throw InvoiceAlreadyEntered(claim.reference)
        }
    }

    /** The notice's deduction with who made it, which the invoice knows. */
    private fun disputed(notice: DeductionNotice): DisputedDeduction {
        val shortPaid = invoices.findById(notice.invoice.invoiceId)?.status as? TicketInvoiceStatus.ShortPaid
        checkNotNull(shortPaid) { "Notice ${notice.number} is for an invoice that was not paid short" }
        return DisputedDeduction(
            notice.id,
            notice.number,
            notice.invoice.ticket,
            notice.amount,
            shortPaid.approval.approvedBy,
        )
    }
}

/**
 * Use case: uphold a deduction a vendor disputed. An accounting manager other than the one who paid the invoice
 * short decides the deduction stands in full, and says why. Nothing is paid and nothing is posted.
 */
@Service
class UpholdDeduction(
    private val disputes: DeductionDisputeRepository,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws DisputeAlreadyDecided if the dispute has been decided.
     * @throws DisputeWithdrawn if the dispute was withdrawn.
     * @throws DisputeDecidedByDeductor if the person asking paid the invoice short.
     */
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        id: DeductionDisputeId,
        reason: String,
    ) {
        val dispute = disputes.findById(id) ?: throw ResourceNotFound("Deduction dispute", id.toString())
        disputes.save(dispute.uphold(MatchOverride.of(currentUser.name, reason), clock))
    }
}

/**
 * Use case: concede a deduction a vendor disputed, in whole or in part. An accounting manager other than the one
 * who paid the invoice short decides how much is given back, and says why. A voucher is released for that amount
 * on the vendor's payment terms; the ledger takes it as a cost variance, and stock is not touched.
 */
@Service
class ConcedeDeduction(
    private val disputes: DeductionDisputeRepository,
    private val recovery: DeductionRecoveryGuard,
    private val voucherRelease: VoucherRelease,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * @throws DisputeAlreadyDecided if the dispute has been decided.
     * @throws DisputeWithdrawn if the dispute was withdrawn.
     * @throws DisputeDecidedByDeductor if the person asking paid the invoice short.
     * @throws ConcessionOutOfRange unless [amount] is more than nothing and no more than was held back.
     * @throws DeductionAlreadyRecovered if a correction to the store's receipt already covers part of [amount].
     */
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        id: DeductionDisputeId,
        amount: Money,
        reason: String,
    ) {
        val dispute = disputes.findById(id) ?: throw ResourceNotFound("Deduction dispute", id.toString())
        val decision = MatchOverride.of(currentUser.name, reason)
        val conceded = dispute.concede(amount, decision, clock, recovery.concedable(dispute))

        disputes.save(conceded)
        voucherRelease.releaseFor(conceded.payable())
        events.publishEvent(conceded.concession(clock))
    }
}

/**
 * Use case: reopen a dispute that was upheld, because the vendor came back with more or the decision was recorded
 * wrongly. An accounting manager other than the one who paid the invoice short says why. The dispute waits again,
 * since the day the vendor's claim arrived, and the decision set aside stays on it. A conceded dispute is not
 * reopened: its voucher has been released.
 */
@Service
class ReopenDeductionDispute(
    private val disputes: DeductionDisputeRepository,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws DisputeNotUpheld unless the deduction was upheld.
     * @throws DisputeDecidedByDeductor if the person asking paid the invoice short.
     */
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        id: DeductionDisputeId,
        reason: String,
    ) {
        val dispute = disputes.findById(id) ?: throw ResourceNotFound("Deduction dispute", id.toString())
        disputes.save(dispute.reopen(MatchOverride.of(currentUser.name, reason), clock))
    }
}

/**
 * Use case: withdraw a dispute that was recorded in error, for example against the wrong notice or under the
 * wrong reference. A clerk says why, while the dispute still waits. It stays on record, no longer waits for
 * accounting, and the deduction can be disputed again.
 */
@Service
class WithdrawDeductionDispute(
    private val disputes: DeductionDisputeRepository,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws DisputeAlreadyDecided if the dispute has been decided.
     * @throws DisputeWithdrawn if the dispute was withdrawn before.
     */
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(
        id: DeductionDisputeId,
        reason: String,
    ) {
        val dispute = disputes.findById(id) ?: throw ResourceNotFound("Deduction dispute", id.toString())
        disputes.save(dispute.withdraw(MatchOverride.of(currentUser.name, reason), clock))
    }
}

/** Queries: disputes by identifier, by deduction notice, and by vendor. */
@Service
@Transactional(readOnly = true)
class DeductionDisputeQueries(
    private val disputes: DeductionDisputeRepository,
    private val recovery: DeductionRecoveryGuard,
) {
    /** What of an open dispute's deduction can still be conceded; `null` once it is decided. */
    fun concedable(dispute: DeductionDispute): Money? =
        if (dispute.status == DisputeStatus.Open) recovery.concedable(dispute) else null

    fun find(id: DeductionDisputeId): DeductionDispute =
        disputes.findById(id) ?: throw ResourceNotFound("Deduction dispute", id.toString())

    /** The dispute of a notice's deduction that stands; one that was withdrawn is found by its identifier. */
    fun forNotice(noticeId: DeductionNoticeId): DeductionDispute =
        disputes.findByNotice(noticeId) ?: throw ResourceNotFound("Dispute of deduction notice", noticeId.toString())

    fun list(
        vendorId: VendorId,
        openOnly: Boolean,
        query: PageQuery,
    ): Page<DeductionDispute> = disputes.findByVendor(vendorId, openOnly, query)
}

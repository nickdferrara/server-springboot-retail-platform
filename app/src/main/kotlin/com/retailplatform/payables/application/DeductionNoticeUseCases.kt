package com.retailplatform.payables.application

import com.retailplatform.payables.domain.DeductionLine
import com.retailplatform.payables.domain.DeductionNotice
import com.retailplatform.payables.domain.DeductionNoticeAlreadySent
import com.retailplatform.payables.domain.DeductionNoticeId
import com.retailplatform.payables.domain.DeductionNoticeNotSent
import com.retailplatform.payables.domain.DeductionNoticeNumber
import com.retailplatform.payables.domain.DeductionNoticeSentDateInvalid
import com.retailplatform.payables.domain.DirectDeliveryInvoice
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.NoticeChannel
import com.retailplatform.payables.domain.NoticeSending
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Port: where deduction notices are kept, one per short-paid invoice. */
interface DeductionNoticeRepository {
    /** Inserts a new notice or updates an existing one; a stale version is refused. */
    fun save(notice: DeductionNotice)

    fun findById(id: DeductionNoticeId): DeductionNotice?

    /** The notice raised for an invoice, if it was paid short. */
    fun findByInvoice(invoiceId: InvoiceId): DeductionNotice?

    /** A vendor's notices, the oldest first; only those not yet sent if [unsentOnly]. */
    fun findByVendor(
        vendorId: VendorId,
        unsentOnly: Boolean,
        query: PageQuery,
    ): Page<DeductionNotice>
}

/** Port: hands out deduction notice numbers, each one once. */
fun interface DeductionNoticeNumbers {
    fun next(): DeductionNoticeNumber
}

/**
 * Raises the deduction notice for an invoice that has just been paid short, so no deduction exists without a
 * notice that says what was held back.
 */
@Component
class DeductionNotices(
    private val notices: DeductionNoticeRepository,
    private val numbers: DeductionNoticeNumbers,
    private val clock: Clock,
) {
    fun raiseFor(
        invoice: DirectDeliveryInvoice,
        approval: MatchOverride,
        lines: List<DeductionLine>,
    ) {
        notices.save(DeductionNotice.raise(numbers.next(), invoice.deducted(), approval, lines, clock))
    }
}

/** When, how, and to whom a deduction notice was sent. */
data class NoticeSent(
    val sentOn: BusinessDate,
    val channel: NoticeChannel,
    val recipient: String,
)

/**
 * Use case: record that a deduction notice was sent. The system sends nothing itself: accounting sends the
 * notice by its usual means, and a clerk records when, how, and to whom.
 */
@Service
class RecordDeductionNoticeSent(
    private val notices: DeductionNoticeRepository,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws DeductionNoticeAlreadySent if the notice was recorded as sent before.
     * @throws DeductionNoticeSentDateInvalid if it was sent before it was raised, or after today.
     */
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(
        id: DeductionNoticeId,
        sent: NoticeSent,
    ) {
        val notice = notices.findById(id) ?: throw ResourceNotFound("Deduction notice", id.toString())
        val sending = NoticeSending.of(sent.sentOn, sent.channel, sent.recipient, currentUser.name)
        notices.save(notice.sent(sending, clock))
    }
}

/**
 * Use case: withdraw a sending that was recorded in error, because a detail is wrong or the notice was never
 * sent. A clerk says why. The notice is unsent again, so it ages and is listed until the right sending is
 * recorded; the withdrawn one stays on the notice.
 */
@Service
class WithdrawDeductionNoticeSending(
    private val notices: DeductionNoticeRepository,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /** @throws DeductionNoticeNotSent if the notice has no recorded sending. */
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(
        id: DeductionNoticeId,
        reason: String,
    ) {
        val notice = notices.findById(id) ?: throw ResourceNotFound("Deduction notice", id.toString())
        notices.save(notice.withdrawSending(MatchOverride.of(currentUser.name, reason), clock))
    }
}

/** Queries: deduction notices by identifier, by invoice, and by vendor. */
@Service
@Transactional(readOnly = true)
class DeductionNoticeQueries(
    private val notices: DeductionNoticeRepository,
) {
    fun find(id: DeductionNoticeId): DeductionNotice =
        notices.findById(id) ?: throw ResourceNotFound("Deduction notice", id.toString())

    fun forInvoice(invoiceId: InvoiceId): DeductionNotice =
        notices.findByInvoice(invoiceId) ?: throw ResourceNotFound("Deduction notice for invoice", invoiceId.toString())

    fun list(
        vendorId: VendorId,
        unsentOnly: Boolean,
        query: PageQuery,
    ): Page<DeductionNotice> = notices.findByVendor(vendorId, unsentOnly, query)
}

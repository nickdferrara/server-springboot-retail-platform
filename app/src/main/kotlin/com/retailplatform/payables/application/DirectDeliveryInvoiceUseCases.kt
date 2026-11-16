package com.retailplatform.payables.application

import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DirectDeliveryAccrual
import com.retailplatform.payables.domain.DirectDeliveryInvoice
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.MatchTolerance
import com.retailplatform.payables.domain.OverrideDoesNotResolve
import com.retailplatform.payables.domain.ShortPayNotPossible
import com.retailplatform.payables.domain.TicketAlreadyInvoiced
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.TicketMatchOutcome
import com.retailplatform.payables.domain.TwoWayMatch
import com.retailplatform.payables.domain.UnderBillingNotAcceptable
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.Percentage
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Port: where invoices for direct store deliveries are kept, one per delivery ticket. */
interface DirectDeliveryInvoiceRepository {
    /** Inserts a new invoice or updates an existing one; a stale version is refused. */
    fun save(invoice: DirectDeliveryInvoice)

    fun findById(id: InvoiceId): DirectDeliveryInvoice?

    fun existsFor(ticket: BilledTicket): Boolean

    /** The invoice entered for a ticket, if there is one. */
    fun findFor(ticket: BilledTicket): DirectDeliveryInvoice?

    fun exists(
        vendorId: VendorId,
        number: VendorInvoiceNumber,
    ): Boolean

    /** Invoices with the newest first; only those waiting on an exception if [exceptionsOnly]. */
    fun findPage(
        exceptionsOnly: Boolean,
        query: PageQuery,
    ): Page<DirectDeliveryInvoice>
}

/**
 * Runs the two-way match for an invoice against the accrual of its delivery ticket, and saves what the match
 * changed: the invoice's status and, when it matched, the relieved accrual and the voucher released for it.
 */
@Component
class TicketInvoiceMatching(
    private val invoices: DirectDeliveryInvoiceRepository,
    private val accruals: DirectDeliveryAccrualRepository,
    private val voucherRelease: VoucherRelease,
    private val properties: PayablesProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * With [override], a unit cost outside tolerance is accepted, now and when the invoice is matched again. An
     * override is also taken when all that is left is for accounting to decide, by accepting under-billing or
     * paying short; the invoice then stays in exception with its price accepted.
     *
     * @throws OverrideDoesNotResolve if accepting the price neither matches the invoice nor leaves it for
     * accounting to accept or pay short, or if its price was accepted before.
     */
    fun match(
        invoice: DirectDeliveryInvoice,
        override: MatchOverride? = null,
    ) {
        invoice.requireMatchable(override)
        val accrual = accruals.findFor(invoice.details.ticket)
        val acceptPrice = override != null || invoice.priceAcceptance != null
        val outcome = twoWayMatch().match(invoice, accrual, acceptPrice)
        if (override != null && outcome is TicketMatchOutcome.Exceptions && !onlyPriceHoldsUp(invoice, accrual)) {
            throw OverrideDoesNotResolve(invoice.details.number)
        }

        invoice.record(outcome, clock, override)
        invoices.save(invoice)
        if (outcome is TicketMatchOutcome.Matched && accrual != null) payable(invoice, accrual)
        invoice.releaseEvents().forEach(events::publishEvent)
    }

    /**
     * Pays [invoice] for what the store received and holds the rest back, on the word of [approval].
     *
     * @throws ShortPayNotPossible if the store's count leaves nothing to hold back or nothing to pay.
     */
    fun shortPay(
        invoice: DirectDeliveryInvoice,
        approval: MatchOverride,
    ) {
        invoice.requireMatchable(approval)
        val accrual = accruals.findFor(invoice.details.ticket)
        val payment = twoWayMatch().shortPay(invoice, accrual, invoice.priceAcceptance != null)

        invoice.shortPay(payment, approval, clock)
        invoices.save(invoice)
        payable(invoice, checkNotNull(accrual))
        invoice.releaseEvents().forEach(events::publishEvent)
    }

    /**
     * Accepts [invoice] as billed though it bills less than the store received, on the word of [approval]. The
     * ticket is closed: nothing more is owed for the units received and not billed.
     *
     * @throws UnderBillingNotAcceptable if the invoice bills more than was received on any line, bills everything,
     * or has a price outside tolerance that no buyer has accepted.
     */
    fun acceptUnderBilling(
        invoice: DirectDeliveryInvoice,
        approval: MatchOverride,
    ) {
        invoice.requireMatchable(approval)
        val accrual = accruals.findFor(invoice.details.ticket)
        val underBilling = twoWayMatch().underBilling(invoice, accrual, invoice.priceAcceptance != null)

        invoice.acceptUnderBilling(underBilling, approval, clock)
        invoices.save(invoice)
        payable(invoice, checkNotNull(accrual))
        invoice.releaseEvents().forEach(events::publishEvent)
    }

    /**
     * Whether a price outside tolerance, not yet accepted, is all that keeps accounting from accepting [invoice]
     * as under-billed or paying it short.
     */
    private fun onlyPriceHoldsUp(
        invoice: DirectDeliveryInvoice,
        accrual: DirectDeliveryAccrual?,
    ): Boolean {
        val match = twoWayMatch()
        val underBilled =
            match.underBillingRefusal(invoice, accrual, priceAccepted = false) != null &&
                match.underBillingRefusal(invoice, accrual, priceAccepted = true) == null
        val overBilled =
            match.shortPayRefusal(invoice, accrual, priceAccepted = false) != null &&
                match.shortPayRefusal(invoice, accrual, priceAccepted = true) == null
        return invoice.priceAcceptance == null && (underBilled || overBilled)
    }

    /** The invoice is payable: its ticket's accrual is relieved and its voucher released. */
    private fun payable(
        invoice: DirectDeliveryInvoice,
        accrual: DirectDeliveryAccrual,
    ) {
        accrual.relieve(BusinessDate.today(clock))
        accruals.save(accrual)
        voucherRelease.releaseFor(invoice.matched())
    }

    private fun twoWayMatch() =
        TwoWayMatch(
            MatchTolerance.of(
                Percentage.of(properties.match.priceTolerancePercent),
                properties.match.priceToleranceAmount,
            ),
        )
}

/** Use case: enter a vendor's invoice for a direct store delivery ticket and match it to the store's receipt. */
@Service
class EnterDirectDeliveryInvoice(
    private val invoices: DirectDeliveryInvoiceRepository,
    private val documentNumbers: VendorDocumentNumbers,
    private val matching: TicketInvoiceMatching,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    /**
     * @throws TicketAlreadyInvoiced if an invoice for the ticket was already entered.
     * @throws InvoiceAlreadyEntered if the vendor's invoice number was already entered.
     */
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(details: TicketInvoiceDetails): InvoiceId {
        if (invoices.existsFor(details.ticket)) throw TicketAlreadyInvoiced(details.ticket.ticketNumber)
        if (documentNumbers.isUsed(details.ticket.vendorId, details.number)) {
            throw InvoiceAlreadyEntered(details.number)
        }

        val invoice = DirectDeliveryInvoice.enter(details, currentUser.name, clock)
        matching.match(invoice)
        return invoice.id
    }
}

/** Use case: match an invoice again after what held it up was resolved, for example the store checked in the ticket. */
@Service
class RematchDirectDeliveryInvoice(
    private val invoices: DirectDeliveryInvoiceRepository,
    private val matching: TicketInvoiceMatching,
) {
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(invoiceId: InvoiceId) {
        val invoice = invoices.findById(invoiceId) ?: throw ResourceNotFound("Invoice", invoiceId.toString())
        matching.match(invoice)
    }
}

/**
 * Use case: accept a direct delivery invoice whose unit cost is outside tolerance. The approver is a buyer who
 * may approve, other than the person who entered the invoice, and says why. The whole difference from agreed
 * cost goes to the cost variance. If the invoice also bills less or more than the store received, it keeps
 * waiting, with its price accepted, for accounting to accept the under-billing or pay it short.
 */
@Service
class OverrideDirectDeliveryException(
    private val invoices: DirectDeliveryInvoiceRepository,
    private val matching: TicketInvoiceMatching,
    private val currentUser: CurrentUser,
) {
    @RequiresRole(Roles.BUYER_APPROVER)
    @Transactional
    fun handle(
        invoiceId: InvoiceId,
        reason: String,
    ) {
        val invoice = invoices.findById(invoiceId) ?: throw ResourceNotFound("Invoice", invoiceId.toString())
        matching.match(invoice, MatchOverride.of(currentUser.name, reason))
    }
}

/**
 * Use case: pay a direct delivery invoice short. When it bills more than the store received and the store's
 * count stands, an accounting manager other than the person who entered the invoice pays for what was received
 * and holds the rest back as a deduction against the vendor, and says why.
 */
@Service
class ShortPayDirectDeliveryInvoice(
    private val invoices: DirectDeliveryInvoiceRepository,
    private val matching: TicketInvoiceMatching,
    private val currentUser: CurrentUser,
) {
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        invoiceId: InvoiceId,
        reason: String,
    ) {
        val invoice = invoices.findById(invoiceId) ?: throw ResourceNotFound("Invoice", invoiceId.toString())
        matching.shortPay(invoice, MatchOverride.of(currentUser.name, reason))
    }
}

/**
 * Use case: accept a direct delivery invoice that bills less than the store received. When no line bills more
 * than was received, an accounting manager other than the person who entered the invoice accepts it as billed
 * and says why. The invoice is paid in full, the ticket is closed, and the units never billed are not owed.
 */
@Service
class AcceptUnderBilledDirectDeliveryInvoice(
    private val invoices: DirectDeliveryInvoiceRepository,
    private val matching: TicketInvoiceMatching,
    private val currentUser: CurrentUser,
) {
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        invoiceId: InvoiceId,
        reason: String,
    ) {
        val invoice = invoices.findById(invoiceId) ?: throw ResourceNotFound("Invoice", invoiceId.toString())
        matching.acceptUnderBilling(invoice, MatchOverride.of(currentUser.name, reason))
    }
}

/** Queries: direct delivery invoices by identifier and by page. */
@Service
@Transactional(readOnly = true)
class DirectDeliveryInvoiceQueries(
    private val invoices: DirectDeliveryInvoiceRepository,
) {
    fun find(id: InvoiceId): DirectDeliveryInvoice =
        invoices.findById(id) ?: throw ResourceNotFound("Invoice", id.toString())

    fun list(
        exceptionsOnly: Boolean,
        query: PageQuery,
    ): Page<DirectDeliveryInvoice> = invoices.findPage(exceptionsOnly, query)
}

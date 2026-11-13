package com.retailplatform.payables.application

import com.retailplatform.payables.domain.BilledTicket
import com.retailplatform.payables.domain.DirectDeliveryInvoice
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.MatchOverride
import com.retailplatform.payables.domain.MatchTolerance
import com.retailplatform.payables.domain.OverrideDoesNotResolve
import com.retailplatform.payables.domain.TicketAlreadyInvoiced
import com.retailplatform.payables.domain.TicketInvoiceDetails
import com.retailplatform.payables.domain.TicketMatchOutcome
import com.retailplatform.payables.domain.TwoWayMatch
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
     * With [override], a unit cost outside tolerance is accepted.
     *
     * @throws OverrideDoesNotResolve if the invoice still does not match with its price accepted.
     */
    fun match(
        invoice: DirectDeliveryInvoice,
        override: MatchOverride? = null,
    ) {
        invoice.requireMatchable(override)
        val tolerance =
            MatchTolerance.of(
                Percentage.of(properties.match.priceTolerancePercent),
                properties.match.priceToleranceAmount,
            )
        val accrual = accruals.findFor(invoice.details.ticket)
        val outcome = TwoWayMatch(tolerance).match(invoice, accrual, acceptPrice = override != null)
        if (override != null && outcome is TicketMatchOutcome.Exceptions) {
            throw OverrideDoesNotResolve(invoice.details.number)
        }

        invoice.record(outcome, clock, override)
        invoices.save(invoice)
        if (outcome is TicketMatchOutcome.Matched && accrual != null) {
            accrual.relieve(BusinessDate.today(clock))
            accruals.save(accrual)
            voucherRelease.releaseFor(invoice.matched())
        }
        invoice.releaseEvents().forEach(events::publishEvent)
    }
}

/** Use case: enter a vendor's invoice for a direct store delivery ticket and match it to the store's receipt. */
@Service
class EnterDirectDeliveryInvoice(
    private val invoices: DirectDeliveryInvoiceRepository,
    private val vendorInvoices: VendorInvoiceRepository,
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
        val vendorId = details.ticket.vendorId
        if (invoices.exists(vendorId, details.number) || vendorInvoices.exists(vendorId, details.number)) {
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
 * cost goes to the cost variance.
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

package com.retailplatform.payables.application

import com.retailplatform.payables.domain.Payment
import com.retailplatform.payables.domain.PaymentNumber
import com.retailplatform.payables.domain.PaymentRun
import com.retailplatform.payables.domain.PaymentRunId
import com.retailplatform.payables.domain.PaymentWindow
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.ResourceNotFound
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Port: where payment runs are kept. */
interface PaymentRunRepository {
    /** Inserts a new run or updates an existing one; a stale version is refused. */
    fun save(run: PaymentRun)

    fun findById(id: PaymentRunId): PaymentRun?

    /** Payment runs with the newest first. */
    fun findPage(query: PageQuery): Page<PaymentRun>
}

/** Port: where payments are kept. A payment is written once. */
interface PaymentRepository {
    fun save(payment: Payment)

    fun findById(id: PaymentId): Payment?

    /** The payments a run issued, in payment number order. */
    fun findByRun(paymentRunId: PaymentRunId): List<Payment>
}

/** Port: hands out payment numbers, each one once. */
fun interface PaymentNumbers {
    fun next(): PaymentNumber
}

/**
 * Use case: propose a payment run. It selects every released voucher that is due on or before the window's
 * pay-through date, or whose early-payment discount would run out by then, and reserves them until the run is
 * approved or cancelled.
 */
@Service
class ProposePaymentRun(
    private val runs: PaymentRunRepository,
    private val vouchers: VoucherRepository,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    @RequiresRole(Roles.ACCOUNTING_CLERK)
    @Transactional
    fun handle(window: PaymentWindow): PaymentRunId {
        val payable = vouchers.findPayable(window)
        val run = PaymentRun.propose(window, payable, currentUser.name, clock)
        runs.save(run)
        payable.forEach(vouchers::save)
        return run.id
    }
}

/**
 * Use case: approve a payment run and issue its payments. The approver is an accounting manager other than the
 * person who proposed the run.
 */
@Service
class ApprovePaymentRun(
    private val runs: PaymentRunRepository,
    private val payments: PaymentRepository,
    private val numbers: PaymentNumbers,
    private val vouchers: VoucherRepository,
    private val currentUser: CurrentUser,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(id: PaymentRunId) {
        val run = runs.findById(id) ?: throw ResourceNotFound("Payment run", id.toString())
        val issued = run.approve(currentUser.name, numbers::next, clock)
        runs.save(run)

        val reserved = vouchers.findByIds(run.lines.map { it.voucherId }).associateBy { it.id }
        issued.forEach { payment ->
            payments.save(payment)
            payment.vouchers.forEach { line ->
                val voucher = reserved.getValue(line.voucherId)
                voucher.pay(run.id, payment.id, payment.paidOn)
                vouchers.save(voucher)
            }
            payment.releaseEvents().forEach(events::publishEvent)
        }
    }
}

/** Use case: cancel a payment run that has not been approved, giving its vouchers back. */
@Service
class CancelPaymentRun(
    private val runs: PaymentRunRepository,
    private val vouchers: VoucherRepository,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) {
    @RequiresRole(Roles.ACCOUNTING_CLERK, Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(id: PaymentRunId) {
        val run = runs.findById(id) ?: throw ResourceNotFound("Payment run", id.toString())
        run.cancel(currentUser.name, clock)
        runs.save(run)
        vouchers.findByIds(run.lines.map { it.voucherId }).forEach {
            it.unschedule(run.id)
            vouchers.save(it)
        }
    }
}

/** A payment run with the payments it issued; none until it is approved. */
data class PaymentRunDetail(
    val run: PaymentRun,
    val payments: List<Payment>,
)

/** Queries: payment runs and payments. */
@Service
@Transactional(readOnly = true)
class PaymentQueries(
    private val runs: PaymentRunRepository,
    private val payments: PaymentRepository,
) {
    fun run(id: PaymentRunId): PaymentRunDetail {
        val run = runs.findById(id) ?: throw ResourceNotFound("Payment run", id.toString())
        return PaymentRunDetail(run, payments.findByRun(id))
    }

    fun runs(query: PageQuery): Page<PaymentRun> = runs.findPage(query)

    fun payment(id: PaymentId): Payment = payments.findById(id) ?: throw ResourceNotFound("Payment", id.toString())
}

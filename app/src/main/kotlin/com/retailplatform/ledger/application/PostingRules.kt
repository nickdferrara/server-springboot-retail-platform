package com.retailplatform.ledger.application

import com.retailplatform.ledger.domain.EntrySide
import com.retailplatform.ledger.domain.EntrySource
import com.retailplatform.ledger.domain.Journal
import com.retailplatform.ledger.domain.PostingAccount
import com.retailplatform.payables.api.events.DirectDeliveryCorrectionRecorded
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatched
import com.retailplatform.payables.api.events.InvoiceMatched
import com.retailplatform.payables.api.events.PaymentIssued
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.Money
import org.springframework.stereotype.Component
import java.util.Currency

/** One line a posting rule asks for: [amount] on one [side] of whichever account fills [role]. */
data class PostingLine(
    val role: PostingAccount,
    val side: EntrySide,
    val amount: Money,
)

/** The journal entry a posting rule asks for, with accounts named by role. */
data class Posting(
    val journal: Journal,
    val businessDate: BusinessDate,
    val source: EntrySource,
    val description: String,
    val lines: List<PostingLine>,
)

/**
 * Turns one kind of business event into the journal entry it calls for. A new kind of transaction is a new rule;
 * no rule knows account numbers, only the roles accounts play.
 */
fun interface PostingRule<E : Any> {
    /** The entry [event] calls for, or `null` if it moves no money. */
    fun posting(event: E): Posting?
}

/** Goods received at a warehouse: debit warehouse inventory, credit accrued payables, at purchase order cost. */
@Component
class GoodsReceivedPostingRule : PostingRule<GoodsReceived> {
    override fun posting(event: GoodsReceived): Posting? {
        val currency = Currency.getInstance(event.currency)
        val cost =
            event.lines
                .map { Money.of(it.caseCost, currency) * it.casesReceived }
                .fold(Money.zero(currency), Money::plus)
        if (cost.isZero) return null

        val receipt = event.receipt
        return Posting(
            journal = Journal.INVENTORY,
            businessDate = event.businessDate,
            source = EntrySource("receiving", event.eventId, "Receipt ${receipt.receiptNumber}"),
            description = "Goods received on purchase order ${receipt.purchaseOrderNumber}",
            lines =
                listOf(
                    PostingLine(PostingAccount.WAREHOUSE_INVENTORY, EntrySide.DEBIT, cost),
                    PostingLine(PostingAccount.ACCRUED_PAYABLES, EntrySide.CREDIT, cost),
                ),
        )
    }
}

/**
 * Vendor invoice matched: debit accrued payables for what was accrued, credit accounts payable for what the
 * vendor billed, and put the difference to purchase price variance.
 */
@Component
class InvoiceMatchedPostingRule : PostingRule<InvoiceMatched> {
    override fun posting(event: InvoiceMatched): Posting? {
        val currency = Currency.getInstance(event.currency)
        val billed = Money.of(event.invoiceAmount, currency)
        val accrued = Money.of(event.accruedAmount, currency)
        val variance = billed - accrued
        val lines =
            listOf(
                PostingLine(PostingAccount.ACCRUED_PAYABLES, EntrySide.DEBIT, accrued),
                PostingLine(
                    PostingAccount.PURCHASE_PRICE_VARIANCE,
                    if (variance.isNegative) EntrySide.CREDIT else EntrySide.DEBIT,
                    if (variance.isNegative) -variance else variance,
                ),
                PostingLine(PostingAccount.ACCOUNTS_PAYABLE, EntrySide.CREDIT, billed),
            ).filterNot { it.amount.isZero }
        if (lines.isEmpty()) return null

        return Posting(
            journal = Journal.PURCHASES,
            businessDate = event.businessDate,
            source = EntrySource("payables", event.eventId, "Invoice ${event.invoice.vendorInvoiceNumber}"),
            description = "Vendor invoice ${event.invoice.vendorInvoiceNumber} matched",
            lines = lines,
        )
    }
}

/**
 * Vendor payment issued: debit accounts payable for what the vouchers owed less the vendor credits deducted,
 * credit the bank for what was paid, and credit purchase discounts taken for the early-payment discount.
 */
@Component
class PaymentIssuedPostingRule : PostingRule<PaymentIssued> {
    override fun posting(event: PaymentIssued): Posting? {
        val currency = Currency.getInstance(event.currency)
        val lines =
            listOf(
                // A vendor credit already reduced accounts payable when it was recorded, so only the rest is cleared.
                PostingLine(
                    PostingAccount.ACCOUNTS_PAYABLE,
                    EntrySide.DEBIT,
                    Money.of(event.grossAmount - event.creditAmount, currency),
                ),
                PostingLine(PostingAccount.BANK, EntrySide.CREDIT, Money.of(event.netAmount, currency)),
                PostingLine(
                    PostingAccount.PURCHASE_DISCOUNTS_TAKEN,
                    EntrySide.CREDIT,
                    Money.of(event.discountAmount, currency),
                ),
            ).filterNot { it.amount.isZero }
        if (lines.isEmpty()) return null

        return Posting(
            journal = Journal.CASH_DISBURSEMENTS,
            businessDate = event.businessDate,
            source = EntrySource("payables", event.eventId, "Payment ${event.paymentNumber}"),
            description = "Vendor payment ${event.paymentNumber}",
            lines = lines,
        )
    }
}

/**
 * Direct store delivery received: credit accrued direct delivery payables for what was delivered, debit accounts
 * payable for the vendor credit on what the driver took back, and put the difference to store inventory, all at
 * agreed cost.
 */
@Component
class DirectDeliveryReceivedPostingRule : PostingRule<DirectDeliveryReceived> {
    override fun posting(event: DirectDeliveryReceived): Posting? {
        val currency = Currency.getInstance(event.currency)
        val zero = Money.zero(currency)
        val delivered = event.lines.map { Money.of(it.unitCost, currency) * it.unitsDelivered }.fold(zero, Money::plus)
        val returned = event.lines.map { Money.of(it.unitCost, currency) * it.unitsReturned }.fold(zero, Money::plus)
        val intoStock = delivered - returned
        val lines =
            listOf(
                PostingLine(
                    PostingAccount.STORE_INVENTORY,
                    if (intoStock.isNegative) EntrySide.CREDIT else EntrySide.DEBIT,
                    if (intoStock.isNegative) -intoStock else intoStock,
                ),
                PostingLine(PostingAccount.ACCOUNTS_PAYABLE, EntrySide.DEBIT, returned),
                PostingLine(PostingAccount.ACCRUED_DIRECT_DELIVERY_PAYABLES, EntrySide.CREDIT, delivered),
            ).filterNot { it.amount.isZero }
        if (lines.isEmpty()) return null

        val delivery = event.delivery
        return Posting(
            journal = Journal.INVENTORY,
            businessDate = event.businessDate,
            source = EntrySource("receiving", event.eventId, "Receipt ${delivery.receiptNumber}"),
            description = "Direct store delivery on ticket ${delivery.ticketNumber}",
            lines = lines,
        )
    }
}

/**
 * Direct store delivery invoice matched: debit accrued direct delivery payables for what was accrued, credit
 * accounts payable for what the vendor billed, and put the difference to purchase price variance.
 */
@Component
class DirectDeliveryInvoiceMatchedPostingRule : PostingRule<DirectDeliveryInvoiceMatched> {
    override fun posting(event: DirectDeliveryInvoiceMatched): Posting? {
        val currency = Currency.getInstance(event.currency)
        val billed = Money.of(event.invoiceAmount, currency)
        val accrued = Money.of(event.accruedAmount, currency)
        val variance = billed - accrued
        val lines =
            listOf(
                PostingLine(PostingAccount.ACCRUED_DIRECT_DELIVERY_PAYABLES, EntrySide.DEBIT, accrued),
                PostingLine(
                    PostingAccount.PURCHASE_PRICE_VARIANCE,
                    if (variance.isNegative) EntrySide.CREDIT else EntrySide.DEBIT,
                    if (variance.isNegative) -variance else variance,
                ),
                PostingLine(PostingAccount.ACCOUNTS_PAYABLE, EntrySide.CREDIT, billed),
            ).filterNot { it.amount.isZero }
        if (lines.isEmpty()) return null

        return Posting(
            journal = Journal.PURCHASES,
            businessDate = event.businessDate,
            source = EntrySource("payables", event.eventId, "Invoice ${event.invoice.vendorInvoiceNumber}"),
            description = "Direct delivery invoice ${event.invoice.vendorInvoiceNumber} matched",
            lines = lines,
        )
    }
}

/**
 * A store's receipt of a direct delivery was corrected: store inventory moves by the value of the units that
 * changed, accrued direct delivery payables by the change in what is owed to the vendor, and accounts payable by
 * the change in what the vendor owes as credits.
 */
@Component
class DirectDeliveryCorrectionPostingRule : PostingRule<DirectDeliveryCorrectionRecorded> {
    override fun posting(event: DirectDeliveryCorrectionRecorded): Posting? {
        val currency = Currency.getInstance(event.currency)
        val lines =
            listOfNotNull(
                line(PostingAccount.STORE_INVENTORY, EntrySide.DEBIT, Money.of(event.inventoryChange, currency)),
                line(PostingAccount.ACCOUNTS_PAYABLE, EntrySide.DEBIT, Money.of(event.creditChange, currency)),
                line(
                    PostingAccount.ACCRUED_DIRECT_DELIVERY_PAYABLES,
                    EntrySide.CREDIT,
                    Money.of(event.accrualChange, currency),
                ),
            )
        if (lines.isEmpty()) return null

        return Posting(
            journal = Journal.INVENTORY,
            businessDate = event.businessDate,
            source = EntrySource("payables", event.eventId, "Receipt ${event.correctionReceiptNumber}"),
            description = "Correction to direct store delivery on ticket ${event.ticketNumber}",
            lines = lines,
        )
    }

    /** [amount] on [side] when it is an increase, on the other side when it is a decrease, and no line for zero. */
    private fun line(
        role: PostingAccount,
        side: EntrySide,
        amount: Money,
    ): PostingLine? =
        when {
            amount.isZero -> null
            amount.isPositive -> PostingLine(role, side, amount)
            else -> PostingLine(role, if (side == EntrySide.DEBIT) EntrySide.CREDIT else EntrySide.DEBIT, -amount)
        }
}

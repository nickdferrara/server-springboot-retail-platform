package com.retailplatform.ledger.application

import com.retailplatform.ledger.domain.EntrySide
import com.retailplatform.ledger.domain.EntrySource
import com.retailplatform.ledger.domain.Journal
import com.retailplatform.ledger.domain.PostingAccount
import com.retailplatform.payables.api.events.InvoiceMatched
import com.retailplatform.payables.api.events.PaymentIssued
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
 * Vendor payment issued: debit accounts payable for what the vouchers owed, credit the bank for what was paid,
 * and credit purchase discounts taken for the early-payment discount.
 */
@Component
class PaymentIssuedPostingRule : PostingRule<PaymentIssued> {
    override fun posting(event: PaymentIssued): Posting? {
        val currency = Currency.getInstance(event.currency)
        val lines =
            listOf(
                PostingLine(PostingAccount.ACCOUNTS_PAYABLE, EntrySide.DEBIT, Money.of(event.grossAmount, currency)),
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

package com.retailplatform.ledger.application

import com.retailplatform.ledger.domain.EntrySide
import com.retailplatform.ledger.domain.EntrySource
import com.retailplatform.ledger.domain.Journal
import com.retailplatform.ledger.domain.PostingAccount
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

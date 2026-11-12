package com.retailplatform.ledger.domain

import com.retailplatform.ledger.api.events.JournalEntryPosted
import com.retailplatform.shared.AggregateRoot
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.BusinessRuleViolation
import com.retailplatform.shared.EventId
import com.retailplatform.shared.JournalEntryId
import com.retailplatform.shared.Money
import java.time.Clock

/** The books journal entries are written in, by the kind of activity they record. */
enum class Journal {
    SALES,
    INVENTORY,
    PURCHASES,
    CASH_RECEIPTS,
    CASH_DISBURSEMENTS,
    GENERAL,
    RECURRING,
    REVERSING,
    CLOSING,
}

/** Which side of an account a line is written on. */
enum class EntrySide {
    DEBIT,
    CREDIT,
}

/** One line of a journal entry: [amount] on one [side] of an [account]. */
@ConsistentCopyVisibility
data class JournalLine private constructor(
    val account: AccountNumber,
    val side: EntrySide,
    val amount: Money,
) {
    companion object {
        /** @throws IllegalArgumentException unless [amount] is above zero. */
        fun of(
            account: AccountNumber,
            side: EntrySide,
            amount: Money,
        ): JournalLine {
            require(amount.isPositive) { "A journal line is for more than zero: $amount" }
            return JournalLine(account, side, amount)
        }

        fun debit(
            account: AccountNumber,
            amount: Money,
        ): JournalLine = of(account, EntrySide.DEBIT, amount)

        fun credit(
            account: AccountNumber,
            amount: Money,
        ): JournalLine = of(account, EntrySide.CREDIT, amount)
    }
}

/**
 * Where a journal entry came from: the module and business event it was posted for, and the document a person
 * would look up, such as `Receipt 100001`.
 */
data class EntrySource(
    val module: String,
    val eventId: EventId?,
    val document: String,
)

/** What a journal entry records, before it is given a number and a fiscal period. */
data class JournalEntryContent(
    val journal: Journal,
    val businessDate: BusinessDate,
    val source: EntrySource,
    val description: String,
    val lines: List<JournalLine>,
)

/** The number the business knows a journal entry by: one to ten digits, kept as text. */
@JvmInline
value class JournalEntryNumber private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[0-9]{1,10}")

        /** @throws IllegalArgumentException unless [value] is one to ten digits. */
        fun of(value: String): JournalEntryNumber {
            require(FORMAT.matches(value)) { "Not a journal entry number: '$value'" }
            return JournalEntryNumber(value)
        }
    }
}

/**
 * A balanced entry in the general ledger. An entry is never changed once posted; a correction is a reversing
 * entry and a new one.
 */
class JournalEntry private constructor(
    val id: JournalEntryId,
    val number: JournalEntryNumber,
    val period: FiscalPeriod,
    val content: JournalEntryContent,
) : AggregateRoot() {
    val lines: List<JournalLine> get() = content.lines

    /** The total of the debits, which equals the total of the credits. */
    val amount: Money get() = total(EntrySide.DEBIT)

    private fun total(side: EntrySide): Money = lines.filter { it.side == side }.map { it.amount }.reduce(Money::plus)

    companion object {
        /**
         * Posts [content] in [period], raising [JournalEntryPosted].
         *
         * @throws JournalEntryIncomplete unless there is at least one debit and one credit in one currency.
         * @throws JournalEntryUnbalanced unless the debits equal the credits.
         * @throws BusinessDateOutsidePeriod unless the business date falls in [period].
         */
        fun post(
            number: JournalEntryNumber,
            period: FiscalPeriod,
            content: JournalEntryContent,
            clock: Clock,
        ): JournalEntry {
            check(content, period)
            val entry = JournalEntry(JournalEntryId.generate(clock), number, period, content)
            entry.raise(
                JournalEntryPosted(
                    eventId = EventId.generate(clock),
                    occurredAt = clock.instant(),
                    businessDate = content.businessDate,
                    journalEntryId = entry.id,
                    journalEntryNumber = number.value,
                    journal = content.journal.name,
                    fiscalYear = period.fiscalYear,
                    fiscalPeriod = period.period,
                    sourceModule = content.source.module,
                    sourceEventId = content.source.eventId,
                    amount = entry.amount.amount,
                    currency = entry.amount.currency.currencyCode,
                ),
            )
            return entry
        }

        /** Rebuilds an entry from stored state. Raises no events. */
        fun rehydrate(
            id: JournalEntryId,
            number: JournalEntryNumber,
            period: FiscalPeriod,
            content: JournalEntryContent,
        ): JournalEntry = JournalEntry(id, number, period, content)

        private fun check(
            content: JournalEntryContent,
            period: FiscalPeriod,
        ) {
            val sides = content.lines.map { it.side }.toSet()
            val currencies = content.lines.map { it.amount.currency }.toSet()
            if (sides.size != EntrySide.entries.size || currencies.size != 1) throw JournalEntryIncomplete()

            val debits =
                content.lines
                    .filter { it.side == EntrySide.DEBIT }
                    .map { it.amount }
                    .reduce(Money::plus)
            val credits =
                content.lines
                    .filter { it.side == EntrySide.CREDIT }
                    .map { it.amount }
                    .reduce(Money::plus)
            val violation =
                when {
                    debits != credits -> JournalEntryUnbalanced(debits, credits)
                    content.businessDate !in period -> BusinessDateOutsidePeriod(content.businessDate, period)
                    else -> null
                }
            if (violation != null) throw violation
        }
    }
}

/** What an account has been debited and credited in total, up to a date. */
data class AccountBalance(
    val account: Account,
    val debits: Money,
    val credits: Money,
) {
    /** Debits less credits: positive for a debit balance, negative for a credit balance. */
    val balance: Money get() = debits - credits
}

class JournalEntryIncomplete :
    BusinessRuleViolation(
        "ledger.journal-entry-incomplete",
        "A journal entry has at least one debit and one credit, all in one currency",
    )

class JournalEntryUnbalanced(
    debits: Money,
    credits: Money,
) : BusinessRuleViolation(
        "ledger.journal-entry-unbalanced",
        "A journal entry must balance: debits are $debits and credits are $credits",
    )

class BusinessDateOutsidePeriod(
    businessDate: BusinessDate,
    period: FiscalPeriod,
) : BusinessRuleViolation(
        "ledger.business-date-outside-period",
        "$businessDate is not in period ${period.period} of fiscal year ${period.fiscalYear}",
    )

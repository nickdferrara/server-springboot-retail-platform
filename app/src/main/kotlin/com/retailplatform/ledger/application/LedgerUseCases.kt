package com.retailplatform.ledger.application

import com.retailplatform.ledger.domain.Account
import com.retailplatform.ledger.domain.AccountBalance
import com.retailplatform.ledger.domain.AccountNumber
import com.retailplatform.ledger.domain.AccountNumberAlreadyInUse
import com.retailplatform.ledger.domain.FiscalCalendarRules
import com.retailplatform.ledger.domain.FiscalPeriod
import com.retailplatform.ledger.domain.JournalEntry
import com.retailplatform.ledger.domain.JournalEntryContent
import com.retailplatform.ledger.domain.JournalLine
import com.retailplatform.ledger.domain.PostingAccount
import com.retailplatform.ledger.domain.PostingAccountNotMapped
import com.retailplatform.ledger.domain.UnknownAccount
import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.JournalEntryId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.ResourceNotFound
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Use case: add an account to the chart of accounts. */
@Service
class CreateAccount(
    private val accounts: AccountRepository,
) {
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(account: Account) {
        if (accounts.exists(account.number)) throw AccountNumberAlreadyInUse(account.number)
        accounts.add(account)
    }
}

/** Use case: choose the account that fills a role in the posting rules. */
@Service
class AssignPostingAccount(
    private val accounts: AccountRepository,
) {
    @RequiresRole(Roles.ACCOUNTING_MANAGER)
    @Transactional
    fun handle(
        role: PostingAccount,
        number: AccountNumber,
    ) {
        if (!accounts.exists(number)) throw UnknownAccount(number)
        accounts.assign(role, number)
    }
}

/**
 * Finds the fiscal period a business date falls in. The calendar is stored as data; a fiscal year that has not
 * been stored yet is generated from the calendar rules the first time a date in it is needed.
 */
@Component
class FiscalCalendar(
    private val periods: FiscalPeriodRepository,
) {
    private val rules = FiscalCalendarRules()

    fun periodFor(date: BusinessDate): FiscalPeriod =
        periods.findContaining(date) ?: run {
            val year = rules.yearOf(date)
            if (!periods.existsForYear(year)) periods.addAll(rules.periodsOf(year))
            checkNotNull(periods.findContaining(date)) { "The fiscal calendar has no period for $date" }
        }

    fun periodsOf(fiscalYear: Int): List<FiscalPeriod> {
        if (!periods.existsForYear(fiscalYear)) periods.addAll(rules.periodsOf(fiscalYear))
        return periods.findByYear(fiscalYear)
    }
}

/** Use case: post the journal entry a posting rule asked for. */
@Service
class PostJournalEntry(
    private val entries: JournalEntryRepository,
    private val numbers: JournalEntryNumbers,
    private val accounts: AccountRepository,
    private val calendar: FiscalCalendar,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /**
     * Does nothing if an entry has already been posted for the posting's source event, so a business event is
     * never posted twice.
     *
     * @throws PostingAccountNotMapped if a role the posting names has no account.
     */
    @Transactional
    fun handle(posting: Posting) {
        val eventId = posting.source.eventId
        if (eventId != null && entries.existsForEvent(eventId)) return

        val lines =
            posting.lines.map {
                val account = accounts.findAssigned(it.role) ?: throw PostingAccountNotMapped(it.role)
                JournalLine.of(account, it.side, it.amount)
            }
        val content =
            JournalEntryContent(posting.journal, posting.businessDate, posting.source, posting.description, lines)
        val entry = JournalEntry.post(numbers.next(), calendar.periodFor(posting.businessDate), content, clock)
        entries.add(entry)
        entry.releaseEvents().forEach(events::publishEvent)
    }
}

/** A trial balance: every account with activity up to [asOf], and proof that debits equal credits. */
data class TrialBalance(
    val asOf: BusinessDate,
    val accounts: List<AccountBalance>,
) {
    val isBalanced: Boolean
        get() = accounts.sumOf { it.debits.amount }.compareTo(accounts.sumOf { it.credits.amount }) == 0
}

/** Queries: the chart of accounts, the fiscal calendar, journal entries, and the trial balance. */
@Service
@Transactional
class LedgerQueries(
    private val accounts: AccountRepository,
    private val entries: JournalEntryRepository,
    private val calendar: FiscalCalendar,
) {
    fun accounts(): List<Account> = accounts.findAll()

    fun postingAccounts(): Map<PostingAccount, AccountNumber> = accounts.findAssignments()

    fun periods(fiscalYear: Int): List<FiscalPeriod> = calendar.periodsOf(fiscalYear)

    fun entry(id: JournalEntryId): JournalEntry =
        entries.findById(id) ?: throw ResourceNotFound("Journal entry", id.toString())

    fun entries(query: PageQuery): Page<JournalEntry> = entries.findPage(query)

    fun trialBalance(asOf: BusinessDate): TrialBalance = TrialBalance(asOf, entries.balancesAsOf(asOf))
}

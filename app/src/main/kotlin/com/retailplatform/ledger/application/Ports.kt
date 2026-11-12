package com.retailplatform.ledger.application

import com.retailplatform.ledger.domain.Account
import com.retailplatform.ledger.domain.AccountBalance
import com.retailplatform.ledger.domain.AccountNumber
import com.retailplatform.ledger.domain.FiscalPeriod
import com.retailplatform.ledger.domain.JournalEntry
import com.retailplatform.ledger.domain.JournalEntryNumber
import com.retailplatform.ledger.domain.PostingAccount
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.JournalEntryId
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery

/** Port: the chart of accounts, and which account fills each role in the posting rules. */
interface AccountRepository {
    fun add(account: Account)

    fun exists(number: AccountNumber): Boolean

    /** Accounts ordered by number. */
    fun findAll(): List<Account>

    fun assign(
        role: PostingAccount,
        number: AccountNumber,
    )

    fun findAssigned(role: PostingAccount): AccountNumber?

    /** Every role that has an account, with that account. */
    fun findAssignments(): Map<PostingAccount, AccountNumber>
}

/** Port: the fiscal calendar, stored as periods. */
interface FiscalPeriodRepository {
    fun findContaining(date: BusinessDate): FiscalPeriod?

    fun existsForYear(fiscalYear: Int): Boolean

    fun addAll(periods: List<FiscalPeriod>)

    /** The periods of a fiscal year, in order. */
    fun findByYear(fiscalYear: Int): List<FiscalPeriod>
}

/** Port: the general ledger. Entries are added and read, never changed or removed. */
interface JournalEntryRepository {
    fun add(entry: JournalEntry)

    fun findById(id: JournalEntryId): JournalEntry?

    fun existsForEvent(eventId: EventId): Boolean

    /** Entries with the newest first. */
    fun findPage(query: PageQuery): Page<JournalEntry>

    /** Debits and credits per account from every entry with a business date on or before [asOf]. */
    fun balancesAsOf(asOf: BusinessDate): List<AccountBalance>
}

/** Port: hands out journal entry numbers, each once. */
interface JournalEntryNumbers {
    fun next(): JournalEntryNumber
}

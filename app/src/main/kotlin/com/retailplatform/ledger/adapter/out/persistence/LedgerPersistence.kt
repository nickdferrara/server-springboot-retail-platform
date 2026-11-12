package com.retailplatform.ledger.adapter.out.persistence

import com.retailplatform.ledger.application.AccountRepository
import com.retailplatform.ledger.application.FiscalPeriodRepository
import com.retailplatform.ledger.application.JournalEntryNumbers
import com.retailplatform.ledger.domain.Account
import com.retailplatform.ledger.domain.AccountNumber
import com.retailplatform.ledger.domain.AccountType
import com.retailplatform.ledger.domain.FiscalPeriod
import com.retailplatform.ledger.domain.JournalEntryNumber
import com.retailplatform.ledger.domain.PostingAccount
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.shared.BusinessDate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@Repository
class JdbcAccountRepository(
    private val jdbc: JdbcClient,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) : AccountRepository {
    override fun add(account: Account) {
        jdbc
            .sql(
                """
                INSERT INTO ledger.account (number, name, type, version, created_at, created_by, updated_at, updated_by)
                VALUES (:number, :name, :type, 1, :now, :user, :now, :user)
                """.trimIndent(),
            ).param("number", account.number.value)
            .param("name", account.name)
            .param("type", account.type.name)
            .param("now", clock.instant().atOffset(ZoneOffset.UTC))
            .param("user", currentUser.name)
            .update()
    }

    override fun exists(number: AccountNumber): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM ledger.account WHERE number = :number)")
            .param("number", number.value)
            .query(Boolean::class.java)
            .single()

    override fun findAll(): List<Account> =
        jdbc
            .sql("SELECT number, name, type FROM ledger.account ORDER BY number")
            .query { row, _ -> row.account() }
            .list()

    override fun assign(
        role: PostingAccount,
        number: AccountNumber,
    ) {
        jdbc
            .sql(
                """
                INSERT INTO ledger.posting_account
                    (role, account_number, version, created_at, created_by, updated_at, updated_by)
                VALUES (:role, :number, 1, :now, :user, :now, :user)
                ON CONFLICT (role) DO UPDATE
                    SET account_number = EXCLUDED.account_number, version = ledger.posting_account.version + 1,
                        updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
                """.trimIndent(),
            ).param("role", role.name)
            .param("number", number.value)
            .param("now", clock.instant().atOffset(ZoneOffset.UTC))
            .param("user", currentUser.name)
            .update()
    }

    override fun findAssigned(role: PostingAccount): AccountNumber? = findAssignments()[role]

    override fun findAssignments(): Map<PostingAccount, AccountNumber> =
        jdbc
            .sql("SELECT role, account_number FROM ledger.posting_account")
            .query { row, _ -> row.getString("role") to AccountNumber.of(row.getString("account_number")) }
            .list()
            .mapNotNull { (role, number) -> PostingAccount.entries.find { it.name == role }?.let { it to number } }
            .toMap()
}

internal fun ResultSet.account(): Account =
    Account.of(AccountNumber.of(getString("number")), getString("name"), AccountType.valueOf(getString("type")))

@Repository
class JdbcFiscalPeriodRepository(
    private val jdbc: JdbcClient,
) : FiscalPeriodRepository {
    override fun findContaining(date: BusinessDate): FiscalPeriod? =
        jdbc
            .sql("$SELECT WHERE :date BETWEEN starts_on AND ends_on")
            .param("date", date.value)
            .query { row, _ -> row.period() }
            .optional()
            .orElse(null)

    override fun existsForYear(fiscalYear: Int): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM ledger.fiscal_period WHERE fiscal_year = :year)")
            .param("year", fiscalYear)
            .query(Boolean::class.java)
            .single()

    override fun addAll(periods: List<FiscalPeriod>) {
        periods.forEach {
            // Two instances may generate the same year at once; the second finds the rows already there.
            jdbc
                .sql(
                    """
                    INSERT INTO ledger.fiscal_period (fiscal_year, period, starts_on, ends_on)
                    VALUES (:year, :period, :startsOn, :endsOn)
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                ).param("year", it.fiscalYear)
                .param("period", it.period)
                .param("startsOn", it.startsOn.value)
                .param("endsOn", it.endsOn.value)
                .update()
        }
    }

    override fun findByYear(fiscalYear: Int): List<FiscalPeriod> =
        jdbc
            .sql("$SELECT WHERE fiscal_year = :year ORDER BY period")
            .param("year", fiscalYear)
            .query { row, _ -> row.period() }
            .list()

    private fun ResultSet.period() =
        FiscalPeriod(
            fiscalYear = getInt("fiscal_year"),
            period = getInt("period"),
            startsOn = BusinessDate(getObject("starts_on", LocalDate::class.java)),
            endsOn = BusinessDate(getObject("ends_on", LocalDate::class.java)),
        )

    private companion object {
        const val SELECT = "SELECT fiscal_year, period, starts_on, ends_on FROM ledger.fiscal_period"
    }
}

@Repository
class SequenceJournalEntryNumbers(
    private val jdbc: JdbcClient,
) : JournalEntryNumbers {
    override fun next(): JournalEntryNumber =
        JournalEntryNumber.of(
            jdbc
                .sql("SELECT nextval('ledger.journal_entry_number_seq')")
                .query(Long::class.java)
                .single()
                .toString(),
        )
}

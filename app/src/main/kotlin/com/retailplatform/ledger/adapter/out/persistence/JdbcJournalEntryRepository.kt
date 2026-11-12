package com.retailplatform.ledger.adapter.out.persistence

import com.retailplatform.ledger.application.JournalEntryRepository
import com.retailplatform.ledger.domain.AccountBalance
import com.retailplatform.ledger.domain.AccountNumber
import com.retailplatform.ledger.domain.EntrySide
import com.retailplatform.ledger.domain.EntrySource
import com.retailplatform.ledger.domain.FiscalPeriod
import com.retailplatform.ledger.domain.Journal
import com.retailplatform.ledger.domain.JournalEntry
import com.retailplatform.ledger.domain.JournalEntryContent
import com.retailplatform.ledger.domain.JournalEntryNumber
import com.retailplatform.ledger.domain.JournalLine
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.JournalEntryId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency
import java.util.UUID

@Repository
class JdbcJournalEntryRepository(
    private val jdbc: JdbcClient,
    private val currentUser: CurrentUser,
    private val clock: Clock,
) : JournalEntryRepository {
    override fun add(entry: JournalEntry) {
        val content = entry.content
        jdbc
            .sql(
                """
                INSERT INTO ledger.journal_entry
                    (id, number, journal, business_date, fiscal_year, fiscal_period, source_module, source_event_id,
                     source_document, description, posted_at, posted_by)
                VALUES (:id, :number, :journal, :businessDate, :fiscalYear, :fiscalPeriod, :sourceModule,
                        :sourceEventId, :sourceDocument, :description, :postedAt, :postedBy)
                """.trimIndent(),
            ).param("id", entry.id.value)
            .param("number", entry.number.value)
            .param("journal", content.journal.name)
            .param("businessDate", content.businessDate.value)
            .param("fiscalYear", entry.period.fiscalYear)
            .param("fiscalPeriod", entry.period.period)
            .param("sourceModule", content.source.module)
            .param("sourceEventId", content.source.eventId?.value)
            .param("sourceDocument", content.source.document)
            .param("description", content.description)
            .param("postedAt", clock.instant().atOffset(ZoneOffset.UTC))
            .param("postedBy", currentUser.name)
            .update()
        entry.lines.forEachIndexed { index, line ->
            jdbc
                .sql(
                    """
                    INSERT INTO ledger.journal_entry_line
                        (journal_entry_id, line_number, account_number, side, amount, currency)
                    VALUES (:entryId, :lineNumber, :account, :side, :amount, :currency)
                    """.trimIndent(),
                ).param("entryId", entry.id.value)
                .param("lineNumber", index + 1)
                .param("account", line.account.value)
                .param("side", line.side.name)
                .param("amount", line.amount.amount)
                .param("currency", line.amount.currency.currencyCode)
                .update()
        }
    }

    override fun findById(id: JournalEntryId): JournalEntry? =
        jdbc
            .sql("$SELECT_ENTRY WHERE e.id = :id")
            .param("id", id.value)
            .query { row, _ -> row.entry() }
            .optional()
            .orElse(null)

    override fun existsForEvent(eventId: EventId): Boolean =
        jdbc
            .sql("SELECT EXISTS (SELECT 1 FROM ledger.journal_entry WHERE source_event_id = :eventId)")
            .param("eventId", eventId.value)
            .query(Boolean::class.java)
            .single()

    override fun findPage(query: PageQuery): Page<JournalEntry> {
        // Identifiers are time-ordered, so the newest entry comes first.
        val items =
            jdbc
                .sql("$SELECT_ENTRY ORDER BY e.id DESC LIMIT :limit OFFSET :offset")
                .param("limit", query.size)
                .param("offset", query.offset)
                .query { row, _ -> row.entry() }
                .list()
        val total = jdbc.sql("SELECT count(*) FROM ledger.journal_entry").query(Long::class.java).single()
        return Page(items, query.page, query.size, total)
    }

    override fun balancesAsOf(asOf: BusinessDate): List<AccountBalance> =
        jdbc
            .sql(
                """
                SELECT a.number, a.name, a.type, l.currency,
                       coalesce(sum(l.amount) FILTER (WHERE l.side = 'DEBIT'), 0) AS debits,
                       coalesce(sum(l.amount) FILTER (WHERE l.side = 'CREDIT'), 0) AS credits
                FROM ledger.journal_entry_line l
                         JOIN ledger.journal_entry e ON e.id = l.journal_entry_id
                         JOIN ledger.account a ON a.number = l.account_number
                WHERE e.business_date <= :asOf
                GROUP BY a.number, a.name, a.type, l.currency
                ORDER BY a.number, l.currency
                """.trimIndent(),
            ).param("asOf", asOf.value)
            .query { row, _ ->
                val currency = Currency.getInstance(row.getString("currency"))
                AccountBalance(
                    account = row.account(),
                    debits = Money.of(row.getBigDecimal("debits"), currency),
                    credits = Money.of(row.getBigDecimal("credits"), currency),
                )
            }.list()

    private fun ResultSet.entry(): JournalEntry {
        val id = getObject("id", UUID::class.java)
        return JournalEntry.rehydrate(
            id = JournalEntryId.of(id),
            number = JournalEntryNumber.of(getString("number")),
            period =
                FiscalPeriod(
                    fiscalYear = getInt("fiscal_year"),
                    period = getInt("fiscal_period"),
                    startsOn = BusinessDate(getObject("starts_on", LocalDate::class.java)),
                    endsOn = BusinessDate(getObject("ends_on", LocalDate::class.java)),
                ),
            content =
                JournalEntryContent(
                    journal = Journal.valueOf(getString("journal")),
                    businessDate = BusinessDate(getObject("business_date", LocalDate::class.java)),
                    source =
                        EntrySource(
                            module = getString("source_module"),
                            eventId = getObject("source_event_id", UUID::class.java)?.let(EventId::of),
                            document = getString("source_document"),
                        ),
                    description = getString("description"),
                    lines = linesOf(id),
                ),
        )
    }

    private fun linesOf(entryId: UUID): List<JournalLine> =
        jdbc
            .sql(
                """
                SELECT account_number, side, amount, currency FROM ledger.journal_entry_line
                WHERE journal_entry_id = :entryId ORDER BY line_number
                """.trimIndent(),
            ).param("entryId", entryId)
            .query { row, _ ->
                JournalLine.of(
                    AccountNumber.of(row.getString("account_number")),
                    EntrySide.valueOf(row.getString("side")),
                    Money.of(row.getBigDecimal("amount"), Currency.getInstance(row.getString("currency"))),
                )
            }.list()

    private companion object {
        const val SELECT_ENTRY =
            "SELECT e.*, p.starts_on, p.ends_on FROM ledger.journal_entry e " +
                "JOIN ledger.fiscal_period p ON p.fiscal_year = e.fiscal_year AND p.period = e.fiscal_period"
    }
}

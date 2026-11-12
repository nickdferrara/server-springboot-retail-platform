package com.retailplatform.ledger

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.ledger.api.events.JournalEntryPosted
import com.retailplatform.ledger.application.AssignPostingAccount
import com.retailplatform.ledger.application.CreateAccount
import com.retailplatform.ledger.application.LedgerQueries
import com.retailplatform.ledger.application.PostJournalEntry
import com.retailplatform.ledger.application.Posting
import com.retailplatform.ledger.application.PostingLine
import com.retailplatform.ledger.domain.Account
import com.retailplatform.ledger.domain.AccountNumber
import com.retailplatform.ledger.domain.AccountNumberAlreadyInUse
import com.retailplatform.ledger.domain.AccountType
import com.retailplatform.ledger.domain.EntrySide
import com.retailplatform.ledger.domain.EntrySource
import com.retailplatform.ledger.domain.Journal
import com.retailplatform.ledger.domain.JournalEntryUnbalanced
import com.retailplatform.ledger.domain.PostingAccount
import com.retailplatform.ledger.domain.UnknownAccount
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.receiving.api.events.GoodsReceivedLine
import com.retailplatform.receiving.api.events.ReceiptFacts
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.JournalEntryId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "controller", authorities = [Roles.ACCOUNTING_MANAGER])
class LedgerModuleTest(
    @Autowired private val postJournalEntry: PostJournalEntry,
    @Autowired private val createAccount: CreateAccount,
    @Autowired private val assignPostingAccount: AssignPostingAccount,
    @Autowired private val ledger: LedgerQueries,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)
    private val inventory = AccountNumber.of("01-1400-0000-00")
    private val accrued = AccountNumber.of("01-2100-0000-00")

    @Test
    fun `goods received post a balanced entry to inventory and accrued payables`(scenario: Scenario) {
        val before = balances()
        val event =
            GoodsReceived(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = today,
                receipt =
                    ReceiptFacts(
                        ReceiptId.generate(clock),
                        "400001",
                        PurchaseOrderId.generate(clock),
                        "200001",
                        VendorId.generate(clock),
                        WarehouseId.generate(clock),
                    ),
                currency = "USD",
                lines =
                    listOf(
                        GoodsReceivedLine(1, ItemId.generate(clock), 12, 4, BigDecimal("18.0000")),
                        GoodsReceivedLine(2, ItemId.generate(clock), 24, 5, BigDecimal("24.0000")),
                    ),
                purchaseOrderComplete = false,
            )

        var posted: JournalEntryId? = null
        scenario
            .publish(event)
            .andWaitForEventOfType(JournalEntryPosted::class.java)
            .matching { it.sourceEventId == event.eventId }
            .toArriveAndVerify { journalEntry ->
                posted = journalEntry.journalEntryId
                journalEntry.amount shouldBe BigDecimal("192.0000")
                journalEntry.journal shouldBe "INVENTORY"
                journalEntry.sourceModule shouldBe "receiving"
            }

        val entry = ledger.entry(requireNotNull(posted))
        entry.content.source.document shouldBe "Receipt 400001"
        entry.content.businessDate shouldBe today
        (today in entry.period) shouldBe true
        entry.lines.map { Triple(it.account, it.side, it.amount) } shouldBe
            listOf(
                Triple(inventory, EntrySide.DEBIT, usd("192.00")),
                Triple(accrued, EntrySide.CREDIT, usd("192.00")),
            )
        ledger.entries(PageQuery.of(0, 5)).items.map { it.id } shouldContain entry.id

        val after = balances()
        after.getValue(inventory) - before.getOrDefault(inventory, usd("0")) shouldBe usd("192.00")
        after.getValue(accrued) - before.getOrDefault(accrued, usd("0")) shouldBe usd("-192.00")
        ledger.trialBalance(today).isBalanced shouldBe true
        ledger.trialBalance(today.minusDays(1)).accounts.sumOf { it.debits.amount } shouldBe
            before.values.filter { it.isPositive }.sumOf { it.amount }
    }

    @Test
    fun `a business event is posted once and an unbalanced posting is refused`() {
        val eventId = EventId.generate(clock)
        val posting = posting(eventId, debit = "50.00", credit = "50.00")
        val count = ledger.entries(PageQuery.of()).total

        postJournalEntry.handle(posting)
        postJournalEntry.handle(posting)
        ledger.entries(PageQuery.of()).total shouldBe count + 1

        shouldThrow<JournalEntryUnbalanced> {
            postJournalEntry.handle(posting(EventId.generate(clock), debit = "50.00", credit = "49.00"))
        }
        shouldThrow<ResourceNotFound> { ledger.entry(JournalEntryId.generate(clock)) }
    }

    @Test
    fun `the database refuses to change an entry or to commit one that does not balance`() {
        postJournalEntry.handle(posting(EventId.generate(clock), debit = "10.00", credit = "10.00"))
        val entryId =
            ledger
                .entries(PageQuery.of(0, 1))
                .items
                .single()
                .id
                .value

        shouldThrow<DataAccessException> {
            jdbc
                .sql(
                    "UPDATE ledger.journal_entry_line SET amount = 11 WHERE journal_entry_id = :id",
                ).param("id", entryId)
                .update()
        }
        shouldThrow<DataAccessException> {
            jdbc.sql("DELETE FROM ledger.journal_entry WHERE id = :id").param("id", entryId).update()
        }
        // The balance check runs at commit, so the failure surfaces from the transaction, not the statement.
        shouldThrow<TransactionException> {
            transactions.executeWithoutResult {
                jdbc
                    .sql(
                        """
                        INSERT INTO ledger.journal_entry_line
                            (journal_entry_id, line_number, account_number, side, amount, currency)
                        VALUES (:id, 3, '01-1400-0000-00', 'DEBIT', 5, 'USD')
                        """.trimIndent(),
                    ).param("id", entryId)
                    .update()
            }
        }
        ledger.trialBalance(today).isBalanced shouldBe true
    }

    @Test
    fun `accounts are added to the chart and assigned to posting roles`() {
        val number = AccountNumber.of("01-1400-${(System.nanoTime() % 10_000).toString().padStart(4, '0')}-00")
        createAccount.handle(Account.of(number, "Plant raw materials", AccountType.ASSET))

        ledger.accounts().map { it.number } shouldContain number
        ledger.postingAccounts()[PostingAccount.ACCRUED_PAYABLES] shouldBe accrued
        shouldThrow<AccountNumberAlreadyInUse> {
            createAccount.handle(Account.of(number, "Again", AccountType.ASSET))
        }
        shouldThrow<UnknownAccount> {
            assignPostingAccount.handle(PostingAccount.WAREHOUSE_INVENTORY, AccountNumber.of("99-9999-9999-99"))
        }
        assignPostingAccount.handle(PostingAccount.WAREHOUSE_INVENTORY, number)
        ledger.postingAccounts()[PostingAccount.WAREHOUSE_INVENTORY] shouldBe number
        assignPostingAccount.handle(PostingAccount.WAREHOUSE_INVENTORY, inventory)

        val periods = ledger.periods(2031)
        periods.size shouldBe 12
        ledger.periods(2031) shouldBe periods
    }

    @Test
    @WithMockUser(authorities = [Roles.ACCOUNTING_CLERK])
    fun `only an accounting manager changes the chart of accounts`() {
        shouldThrow<AccessDeniedException> {
            createAccount.handle(Account.of(AccountNumber.of("01-9000-0000-00"), "Nope", AccountType.EXPENSE))
        }
        shouldThrow<AccessDeniedException> { assignPostingAccount.handle(PostingAccount.ACCRUED_PAYABLES, accrued) }
    }

    private fun posting(
        eventId: EventId,
        debit: String,
        credit: String,
    ) = Posting(
        journal = Journal.INVENTORY,
        businessDate = today,
        source = EntrySource("receiving", eventId, "Receipt 400002"),
        description = "Goods received",
        lines =
            listOf(
                PostingLine(PostingAccount.WAREHOUSE_INVENTORY, EntrySide.DEBIT, usd(debit)),
                PostingLine(PostingAccount.ACCRUED_PAYABLES, EntrySide.CREDIT, usd(credit)),
            ),
    )

    private fun balances(): Map<AccountNumber, Money> =
        ledger.trialBalance(today).accounts.associate { it.account.number to it.balance }

    private fun usd(amount: String) = Money.of(amount, "USD")
}

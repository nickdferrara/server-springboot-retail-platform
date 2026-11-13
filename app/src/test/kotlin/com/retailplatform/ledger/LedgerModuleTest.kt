package com.retailplatform.ledger

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.ledger.api.events.JournalEntryPosted
import com.retailplatform.ledger.application.AssignPostingAccount
import com.retailplatform.ledger.application.CreateAccount
import com.retailplatform.ledger.application.DirectDeliveryCorrectionPostingRule
import com.retailplatform.ledger.application.DirectDeliveryInvoiceMatchedPostingRule
import com.retailplatform.ledger.application.DirectDeliveryReceivedPostingRule
import com.retailplatform.ledger.application.InvoiceMatchedPostingRule
import com.retailplatform.ledger.application.LedgerQueries
import com.retailplatform.ledger.application.PaymentIssuedPostingRule
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
import com.retailplatform.payables.api.events.DirectDeliveryCorrectionRecorded
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceFacts
import com.retailplatform.payables.api.events.DirectDeliveryInvoiceMatched
import com.retailplatform.payables.api.events.InvoiceFacts
import com.retailplatform.payables.api.events.InvoiceMatched
import com.retailplatform.payables.api.events.PaymentIssued
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.api.events.DirectDeliveryFacts
import com.retailplatform.receiving.api.events.DirectDeliveryLine
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.receiving.api.events.GoodsReceivedLine
import com.retailplatform.receiving.api.events.ReceiptFacts
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.JournalEntryId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PaymentId
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
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
        // Every entry in this test database is dated today, so yesterday's trial balance is empty.
        ledger.trialBalance(today.minusDays(1)).accounts shouldBe emptyList()
    }

    @Test
    fun `a matched invoice moves the accrual to accounts payable with the price variance`(scenario: Scenario) {
        val before = balances()
        val event =
            InvoiceMatched(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = today,
                invoice =
                    InvoiceFacts(
                        InvoiceId.generate(clock),
                        "INV-77",
                        VendorId.generate(clock),
                        PurchaseOrderId.generate(clock),
                    ),
                invoiceAmount = BigDecimal("183.0000"),
                accruedAmount = BigDecimal("180.0000"),
                priceVariance = BigDecimal("3.0000"),
                currency = "USD",
            )

        scenario
            .publish(event)
            .andWaitForEventOfType(JournalEntryPosted::class.java)
            .matching { it.sourceEventId == event.eventId }
            .toArriveAndVerify { journalEntry ->
                journalEntry.journal shouldBe "PURCHASES"
                journalEntry.amount shouldBe BigDecimal("183.0000")
            }

        val after = balances()
        val payable = AccountNumber.of("01-2000-0000-00")
        val variance = AccountNumber.of("01-5100-0000-00")
        after.getValue(accrued) - before.getOrDefault(accrued, usd("0")) shouldBe usd("180.00")
        after.getValue(payable) - before.getOrDefault(payable, usd("0")) shouldBe usd("-183.00")
        after.getValue(variance) - before.getOrDefault(variance, usd("0")) shouldBe usd("3.00")
        ledger.trialBalance(today).isBalanced shouldBe true
    }

    @Test
    fun `a vendor payment clears accounts payable against the bank and the discount taken`(scenario: Scenario) {
        val before = balances()
        val event = paymentIssued(gross = "300.0000", discount = "4.0000", net = "296.0000")

        scenario
            .publish(event)
            .andWaitForEventOfType(JournalEntryPosted::class.java)
            .matching { it.sourceEventId == event.eventId }
            .toArriveAndVerify { journalEntry ->
                journalEntry.journal shouldBe "CASH_DISBURSEMENTS"
                journalEntry.amount shouldBe BigDecimal("300.0000")
            }

        val after = balances()
        val payable = AccountNumber.of("01-2000-0000-00")
        val bank = AccountNumber.of("01-1000-0000-00")
        val discounts = AccountNumber.of("01-5200-0000-00")
        after.getValue(payable) - before.getOrDefault(payable, usd("0")) shouldBe usd("300.00")
        after.getValue(bank) - before.getOrDefault(bank, usd("0")) shouldBe usd("-296.00")
        after.getValue(discounts) - before.getOrDefault(discounts, usd("0")) shouldBe usd("-4.00")
        ledger.trialBalance(today).isBalanced shouldBe true

        // A payment with no discount has no discount line.
        val plain = PaymentIssuedPostingRule().posting(paymentIssued("50.0000", "0.0000", "50.0000"))
        requireNotNull(plain).lines.map { it.role } shouldBe
            listOf(PostingAccount.ACCOUNTS_PAYABLE, PostingAccount.BANK)
        PaymentIssuedPostingRule().posting(paymentIssued("0.0000", "0.0000", "0.0000")) shouldBe null
    }

    @Test
    fun `a direct store delivery posts store inventory, the accrual, and the vendor credit`(scenario: Scenario) {
        val before = balances()
        val event = directDelivery(DirectDeliveryLine(1, ItemId.generate(clock), 20, 3, BigDecimal("1.2500")))

        scenario
            .publish(event)
            .andWaitForEventOfType(JournalEntryPosted::class.java)
            .matching { it.sourceEventId == event.eventId }
            .toArriveAndVerify { journalEntry ->
                journalEntry.journal shouldBe "INVENTORY"
                journalEntry.amount shouldBe BigDecimal("25.0000")
            }

        val after = balances()
        val storeInventory = AccountNumber.of("01-1410-0000-00")
        val accruedDirect = AccountNumber.of("01-2110-0000-00")
        val payable = AccountNumber.of("01-2000-0000-00")
        after.getValue(storeInventory) - before.getOrDefault(storeInventory, usd("0")) shouldBe usd("21.25")
        after.getValue(accruedDirect) - before.getOrDefault(accruedDirect, usd("0")) shouldBe usd("-25.00")
        after.getValue(payable) - before.getOrDefault(payable, usd("0")) shouldBe usd("3.75")
        ledger.trialBalance(today).isBalanced shouldBe true

        // A ticket that only takes product back credits store inventory against the vendor credit.
        val rule = DirectDeliveryReceivedPostingRule()
        val pickup =
            rule.posting(
                directDelivery(DirectDeliveryLine(1, ItemId.generate(clock), 0, 4, BigDecimal("2.0000"))),
            )
        requireNotNull(pickup).lines.map { Triple(it.role, it.side, it.amount) } shouldBe
            listOf(
                Triple(PostingAccount.STORE_INVENTORY, EntrySide.CREDIT, usd("8.00")),
                Triple(PostingAccount.ACCOUNTS_PAYABLE, EntrySide.DEBIT, usd("8.00")),
            )
        rule.posting(directDelivery(DirectDeliveryLine(1, ItemId.generate(clock), 4, 0, BigDecimal("0.0000")))) shouldBe
            null
    }

    @Test
    fun `a matched direct delivery invoice moves its accrual to accounts payable with the variance`(
        scenario: Scenario,
    ) {
        val before = balances()
        val event =
            DirectDeliveryInvoiceMatched(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = today,
                invoice =
                    DirectDeliveryInvoiceFacts(
                        InvoiceId.generate(clock),
                        "DSD-77",
                        VendorId.generate(clock),
                        StoreId.generate(clock),
                        "T-77",
                    ),
                invoiceAmount = BigDecimal("25.2000"),
                accruedAmount = BigDecimal("25.0000"),
                costVariance = BigDecimal("0.2000"),
                currency = "USD",
            )

        scenario
            .publish(event)
            .andWaitForEventOfType(JournalEntryPosted::class.java)
            .matching { it.sourceEventId == event.eventId }
            .toArriveAndVerify { journalEntry -> journalEntry.journal shouldBe "PURCHASES" }

        val after = balances()
        val accruedDirect = AccountNumber.of("01-2110-0000-00")
        val payable = AccountNumber.of("01-2000-0000-00")
        val variance = AccountNumber.of("01-5100-0000-00")
        after.getValue(accruedDirect) - before.getOrDefault(accruedDirect, usd("0")) shouldBe usd("25.00")
        after.getValue(payable) - before.getOrDefault(payable, usd("0")) shouldBe usd("-25.20")
        after.getValue(variance) - before.getOrDefault(variance, usd("0")) shouldBe usd("0.20")

        // A short-paid invoice credits accounts payable only for what is owed: 25.00 billed less 2.50 held back.
        val shortPaid =
            event.copy(
                invoiceAmount = BigDecimal("25.0000"),
                accruedAmount = BigDecimal("22.5000"),
                costVariance = BigDecimal("0.0000"),
                deductionAmount = BigDecimal("2.5000"),
            )
        requireNotNull(
            DirectDeliveryInvoiceMatchedPostingRule().posting(shortPaid),
        ).lines.map { it.role to it.amount } shouldBe
            listOf(
                PostingAccount.ACCRUED_DIRECT_DELIVERY_PAYABLES to usd("22.50"),
                PostingAccount.ACCOUNTS_PAYABLE to usd("22.50"),
            )

        // A payment that deducts a vendor credit clears only the rest of accounts payable.
        val withCredit = paymentIssued("25.0000", "0.0000", "20.0000").copy(creditAmount = BigDecimal("5.0000"))
        requireNotNull(PaymentIssuedPostingRule().posting(withCredit)).lines.map { it.role to it.amount } shouldBe
            listOf(PostingAccount.ACCOUNTS_PAYABLE to usd("20.00"), PostingAccount.BANK to usd("20.00"))
    }

    @Test
    fun `a corrected direct delivery moves inventory, the accrual, and the vendor credit by what changed`() {
        val rule = DirectDeliveryCorrectionPostingRule()

        fun corrected(
            inventory: String,
            accrual: String,
            credit: String,
        ) = DirectDeliveryCorrectionRecorded(
            eventId = EventId.generate(clock),
            occurredAt = clock.instant(),
            businessDate = today,
            vendorId = VendorId.generate(clock),
            storeId = StoreId.generate(clock),
            ticketNumber = "T-70",
            correctionReceiptNumber = "100091",
            inventoryChange = BigDecimal(inventory),
            accrualChange = BigDecimal(accrual),
            creditChange = BigDecimal(credit),
            currency = "USD",
        )

        // Two fewer delivered and one fewer returned: less inventory, less accrued, less credit.
        val fewer = requireNotNull(rule.posting(corrected("-1.2500", "-2.5000", "-1.2500")))
        fewer.lines.map { Triple(it.role, it.side, it.amount) } shouldBe
            listOf(
                Triple(PostingAccount.STORE_INVENTORY, EntrySide.CREDIT, usd("1.25")),
                Triple(PostingAccount.ACCOUNTS_PAYABLE, EntrySide.CREDIT, usd("1.25")),
                Triple(PostingAccount.ACCRUED_DIRECT_DELIVERY_PAYABLES, EntrySide.DEBIT, usd("2.50")),
            )
        fewer.journal shouldBe Journal.INVENTORY
        postJournalEntry.handle(fewer)

        // More delivered: more inventory and more accrued, with no credit line.
        requireNotNull(rule.posting(corrected("4.0000", "4.0000", "0.0000"))).lines.map { it.role to it.side } shouldBe
            listOf(
                PostingAccount.STORE_INVENTORY to EntrySide.DEBIT,
                PostingAccount.ACCRUED_DIRECT_DELIVERY_PAYABLES to EntrySide.CREDIT,
            )
        rule.posting(corrected("0.0000", "0.0000", "0.0000")) shouldBe null
        ledger.trialBalance(today).isBalanced shouldBe true
    }

    private fun directDelivery(vararg lines: DirectDeliveryLine) =
        DirectDeliveryReceived(
            eventId = EventId.generate(clock),
            occurredAt = clock.instant(),
            businessDate = today,
            delivery =
                DirectDeliveryFacts(
                    DirectDeliveryId.generate(clock),
                    "100088",
                    VendorId.generate(clock),
                    StoreId.generate(clock),
                    "T-9",
                ),
            currency = "USD",
            lines = lines.toList(),
        )

    private fun paymentIssued(
        gross: String,
        discount: String,
        net: String,
    ) = PaymentIssued(
        eventId = EventId.generate(clock),
        occurredAt = clock.instant(),
        businessDate = today,
        paymentId = PaymentId.generate(clock),
        paymentNumber = "100042",
        vendorId = VendorId.generate(clock),
        grossAmount = BigDecimal(gross),
        discountAmount = BigDecimal(discount),
        netAmount = BigDecimal(net),
        currency = "USD",
        vouchers = emptyList(),
    )

    @Test
    fun `an invoice billed under order cost credits the variance, and one at cost has none`() {
        val rule = InvoiceMatchedPostingRule()
        val invoice =
            InvoiceFacts(InvoiceId.generate(clock), "INV-78", VendorId.generate(clock), PurchaseOrderId.generate(clock))

        fun posting(
            billed: String,
            accruedAt: String,
        ) = rule.posting(
            InvoiceMatched(
                EventId.generate(clock),
                clock.instant(),
                today,
                invoice,
                BigDecimal(billed),
                BigDecimal(accruedAt),
                BigDecimal(billed) - BigDecimal(accruedAt),
                "USD",
            ),
        )

        posting("178.00", "180.00")?.lines?.map { it.role to it.side } shouldBe
            listOf(
                PostingAccount.ACCRUED_PAYABLES to EntrySide.DEBIT,
                PostingAccount.PURCHASE_PRICE_VARIANCE to EntrySide.CREDIT,
                PostingAccount.ACCOUNTS_PAYABLE to EntrySide.CREDIT,
            )
        posting("180.00", "180.00")?.lines?.map { it.role } shouldBe
            listOf(PostingAccount.ACCRUED_PAYABLES, PostingAccount.ACCOUNTS_PAYABLE)
        posting("0.00", "0.00") shouldBe null
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

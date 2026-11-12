package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.api.events.AccrualRecorded
import com.retailplatform.payables.api.events.InvoiceMatchException
import com.retailplatform.payables.api.events.InvoiceMatched
import com.retailplatform.payables.application.AccrualQueries
import com.retailplatform.payables.application.EnterVendorInvoice
import com.retailplatform.payables.application.InvoiceQueries
import com.retailplatform.payables.application.RecordAccrual
import com.retailplatform.payables.application.RecordAccrualCommand
import com.retailplatform.payables.application.RematchVendorInvoice
import com.retailplatform.payables.domain.AccrualLine
import com.retailplatform.payables.domain.AccruedReceipt
import com.retailplatform.payables.domain.InvoiceAlreadyEntered
import com.retailplatform.payables.domain.InvoiceAlreadyMatched
import com.retailplatform.payables.domain.InvoiceDetails
import com.retailplatform.payables.domain.InvoiceLine
import com.retailplatform.payables.domain.InvoiceStatus
import com.retailplatform.payables.domain.MatchExceptionReason
import com.retailplatform.payables.domain.VendorInvoiceNumber
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.receiving.api.events.GoodsReceivedLine
import com.retailplatform.receiving.api.events.ReceiptFacts
import com.retailplatform.shared.AccrualId
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InvoiceId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.Clock

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "clerk", authorities = [Roles.ACCOUNTING_CLERK])
class PayablesModuleTest(
    @Autowired private val recordAccrual: RecordAccrual,
    @Autowired private val accruals: AccrualQueries,
    @Autowired private val enterVendorInvoice: EnterVendorInvoice,
    @Autowired private val rematchVendorInvoice: RematchVendorInvoice,
    @Autowired private val invoices: InvoiceQueries,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)

    @Test
    fun `goods received are accrued once at purchase order cost`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val receipt = receiptFacts(vendorId, "300001")
        val lines =
            listOf(
                GoodsReceivedLine(1, ItemId.generate(clock), 12, 4, BigDecimal("18.0000")),
                GoodsReceivedLine(2, ItemId.generate(clock), 24, 5, BigDecimal("24.0000")),
            )
        val event = GoodsReceived(EventId.generate(clock), clock.instant(), today, receipt, "USD", lines, false)

        scenario
            .publish(event)
            .andWaitForEventOfType(AccrualRecorded::class.java)
            .matching { it.receiptId == receipt.receiptId }
            .toArriveAndVerify { recorded ->
                recorded.amount shouldBe BigDecimal("192.0000")
                recorded.vendorId shouldBe vendorId
                recorded.businessDate shouldBe today
            }

        // The same receipt arriving again, as a redelivered or re-published event would, accrues nothing more.
        recordAccrual.handle(
            RecordAccrualCommand(
                accruedReceipt(vendorId, receipt.receiptId, today),
                listOf(AccrualLine.of(1, ItemId.generate(clock), 4, usd("18.00"))),
            ),
        )

        val open = accruals.open(vendorId, PageQuery.of())
        open.total shouldBe 1
        val accrual = open.items.single()
        accrual.amount shouldBe usd("192.00")
        accrual.openAmount shouldBe usd("192.00")
        accrual.lines.map { it.casesReceived } shouldBe listOf(4, 5)
        accruals.find(accrual.id).receipt.receiptNumber shouldBe "300001"
        shouldThrow<ResourceNotFound> { accruals.find(AccrualId.generate(clock)) }
    }

    @Test
    fun `open accruals are totalled by vendor and flagged when they have waited too long`() {
        val recent = VendorId.generate(clock)
        val overdue = VendorId.generate(clock)
        accrue(recent, receivedOn = today.minusDays(2), amount = "50.00")
        accrue(recent, receivedOn = today.minusDays(10), amount = "25.00")
        accrue(overdue, receivedOn = today.minusDays(61), amount = "40.00")

        val balances = accruals.balances().associateBy { it.vendorId }

        balances.getValue(recent).openAmount shouldBe usd("75.00")
        balances.getValue(recent).openAccruals shouldBe 2
        balances.getValue(recent).oldestReceivedOn shouldBe today.minusDays(10)
        balances.getValue(recent).oldestAgeDays shouldBe 10
        balances.getValue(recent).stale shouldBe false
        balances.getValue(overdue).stale shouldBe true
        accruals.balances().indexOfFirst { it.vendorId == overdue } shouldBe 0
        accruals.open(recent, PageQuery.of()).items.map { it.receipt.receivedOn } shouldBe
            listOf(today.minusDays(10), today.minusDays(2))
    }

    @Test
    fun `an invoice that agrees with its receipts matches, relieves the accrual, and is announced`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val orderId = PurchaseOrderId.generate(clock)
        receive(vendorId, orderId, cases = 10, caseCost = "18.00")
        var invoiceId: InvoiceId? = null

        scenario
            .stimulate(
                Runnable { invoiceId = enterVendorInvoice.handle(details(vendorId, orderId, "INV-1", 6, "18.25")) },
            ).andWaitForEventOfType(InvoiceMatched::class.java)
            .matching { it.invoice.purchaseOrderId == orderId }
            .toArriveAndVerify { event ->
                event.invoiceAmount shouldBe BigDecimal("109.5000")
                event.accruedAmount shouldBe BigDecimal("108.0000")
                event.priceVariance shouldBe BigDecimal("1.5000")
            }

        val invoice = invoices.find(requireNotNull(invoiceId))
        invoice.status shouldBe InvoiceStatus.Matched(today)
        invoice.enteredBy shouldBe "clerk"
        accruals
            .open(vendorId, PageQuery.of())
            .items
            .single()
            .openAmount shouldBe usd("72.00")
        accruals.balances().first { it.vendorId == vendorId }.openAmount shouldBe usd("72.00")
        shouldThrow<InvoiceAlreadyEntered> {
            enterVendorInvoice.handle(
                details(vendorId, orderId, "INV-1", 1, "18.00"),
            )
        }
        shouldThrow<InvoiceAlreadyMatched> { rematchVendorInvoice.handle(invoice.id) }
    }

    @Test
    fun `an invoice ahead of its goods waits as an exception and matches once they arrive`(scenario: Scenario) {
        val vendorId = VendorId.generate(clock)
        val orderId = PurchaseOrderId.generate(clock)
        var invoiceId: InvoiceId? = null

        scenario
            .stimulate(
                Runnable { invoiceId = enterVendorInvoice.handle(details(vendorId, orderId, "INV-2", 4, "18.00")) },
            ).andWaitForEventOfType(InvoiceMatchException::class.java)
            .matching { it.invoice.purchaseOrderId == orderId }
            .toArriveAndVerify { event -> event.exceptions.single().reason shouldBe "NOTHING_RECEIVED" }

        val id = requireNotNull(invoiceId)
        invoices
            .find(id)
            .status
            .shouldBeInstanceOf<InvoiceStatus.Exception>()
            .exceptions
            .single()
            .reason shouldBe
            MatchExceptionReason.NOTHING_RECEIVED
        invoices.list(exceptionsOnly = true, PageQuery.of()).items.map { it.id } shouldContain id

        receive(vendorId, orderId, cases = 4, caseCost = "18.00")
        rematchVendorInvoice.handle(id)

        invoices.find(id).status shouldBe InvoiceStatus.Matched(today)
        invoices.list(exceptionsOnly = true, PageQuery.of()).items.map { it.id } shouldNotContain id
        invoices.list(exceptionsOnly = false, PageQuery.of()).items.map { it.id } shouldContain id
        accruals.open(vendorId, PageQuery.of()).total shouldBe 0
        shouldThrow<ResourceNotFound> { invoices.find(InvoiceId.generate(clock)) }
    }

    @Test
    @WithMockUser(authorities = [Roles.BUYER])
    fun `only accounting enters and matches invoices`() {
        val details = details(VendorId.generate(clock), PurchaseOrderId.generate(clock), "INV-3", 1, "1.00")

        shouldThrow<AccessDeniedException> { enterVendorInvoice.handle(details) }
        shouldThrow<AccessDeniedException> { rematchVendorInvoice.handle(InvoiceId.generate(clock)) }
    }

    private fun details(
        vendorId: VendorId,
        orderId: PurchaseOrderId,
        number: String,
        cases: Int,
        caseCost: String,
    ) = InvoiceDetails(
        vendorId,
        VendorInvoiceNumber.of(number),
        today,
        orderId,
        listOf(InvoiceLine.of(1, cases, usd(caseCost))),
    )

    private fun receive(
        vendorId: VendorId,
        orderId: PurchaseOrderId,
        cases: Int,
        caseCost: String,
    ) {
        recordAccrual.handle(
            RecordAccrualCommand(
                AccruedReceipt(
                    ReceiptId.generate(clock),
                    "300003",
                    orderId,
                    "200003",
                    vendorId,
                    WarehouseId.generate(clock),
                    today,
                ),
                listOf(AccrualLine.of(1, ItemId.generate(clock), cases, usd(caseCost))),
            ),
        )
    }

    private fun accrue(
        vendorId: VendorId,
        receivedOn: BusinessDate,
        amount: String,
    ) {
        recordAccrual.handle(
            RecordAccrualCommand(
                accruedReceipt(vendorId, ReceiptId.generate(clock), receivedOn),
                listOf(AccrualLine.of(1, ItemId.generate(clock), 1, usd(amount))),
            ),
        )
    }

    private fun accruedReceipt(
        vendorId: VendorId,
        receiptId: ReceiptId,
        receivedOn: BusinessDate,
    ) = AccruedReceipt(
        receiptId,
        "300002",
        PurchaseOrderId.generate(clock),
        "200002",
        vendorId,
        WarehouseId.generate(clock),
        receivedOn,
    )

    private fun receiptFacts(
        vendorId: VendorId,
        number: String,
    ) = ReceiptFacts(
        ReceiptId.generate(clock),
        number,
        PurchaseOrderId.generate(clock),
        "200001",
        vendorId,
        WarehouseId.generate(clock),
    )

    private fun usd(amount: String) = Money.of(amount, "USD")
}

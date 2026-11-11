package com.retailplatform.payables

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.payables.api.events.AccrualRecorded
import com.retailplatform.payables.application.AccrualQueries
import com.retailplatform.payables.application.RecordAccrual
import com.retailplatform.payables.application.RecordAccrualCommand
import com.retailplatform.payables.domain.AccrualLine
import com.retailplatform.payables.domain.AccruedReceipt
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.receiving.api.events.GoodsReceivedLine
import com.retailplatform.receiving.api.events.ReceiptFacts
import com.retailplatform.shared.AccrualId
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ReceiptId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import java.math.BigDecimal
import java.time.Clock

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class)
class PayablesModuleTest(
    @Autowired private val recordAccrual: RecordAccrual,
    @Autowired private val accruals: AccrualQueries,
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

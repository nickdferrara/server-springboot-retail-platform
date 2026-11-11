package com.retailplatform.purchasing.domain

import com.retailplatform.purchasing.api.events.PurchaseOrderApproved
import com.retailplatform.purchasing.api.events.PurchaseOrderCancelled
import com.retailplatform.purchasing.api.events.PurchaseOrderSent
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PurchaseOrderTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-11T15:00:00Z"), ZoneOffset.UTC)
    private val today = BusinessDate.today(clock)
    private val vendorId = VendorId.generate(clock)
    private val warehouseId = WarehouseId.generate(clock)
    private val chips = ItemId.generate(clock)
    private val cola = ItemId.generate(clock)
    private val limit = PoApprovalPolicy(usd("500.00"))
    private val buyer = Approver("buyer", mayApproveOverThreshold = false)
    private val manager = Approver("manager", mayApproveOverThreshold = true)

    @Test
    fun `a line is costed at the vendor's list cost unless the buyer gives another`() {
        val supply = VendorItemCost(vendorId, chips, "CH-1", casePack = 12, caseCost = usd("18.00"))

        val atList = PurchaseOrderLine.of(RequestedLine.of(chips, 10), supply)
        val negotiated = PurchaseOrderLine.of(RequestedLine.of(chips, 10, usd("17.25")), supply)

        atList.extendedCost shouldBe usd("180.00")
        atList.baseUnits shouldBe 120
        atList.vendorSku shouldBe "CH-1"
        negotiated.extendedCost shouldBe usd("172.50")
        shouldThrow<IllegalArgumentException> { RequestedLine.of(chips, 0) }
        shouldThrow<IllegalArgumentException> { RequestedLine.of(chips, 1, usd("-1.00")) }
    }

    @Test
    fun `a draft totals its lines and can be revised`() {
        val order = draft(line(chips, 10, "18.00"), line(cola, 5, "24.00"))

        order.state shouldBe PurchaseOrderState.Draft
        order.total shouldBe usd("300.00")

        order.revise(terms(today.plusDays(3), line(chips, 2, "18.00")), clock)

        order.total shouldBe usd("36.00")
        order.terms.expectedOn shouldBe today.plusDays(3)
        order.releaseEvents().shouldBeEmpty()
    }

    @Test
    fun `an order must be deliverable, list each item once, and use one currency`() {
        shouldThrow<ExpectedDeliveryInPast> { draft(line(chips, 1, "18.00"), expectedOn = today.minusDays(1)) }
        shouldThrow<ItemRepeatedOnOrder> { draft(line(chips, 1, "18.00"), line(chips, 2, "18.00")) }
        shouldThrow<MixedCurrenciesOnOrder> {
            draft(line(chips, 1, "18.00"), PurchaseOrderLine(cola, "CO-1", 24, 1, Money.of("20.00", "CAD")))
        }
        shouldThrow<IllegalArgumentException> { draft() }
    }

    @Test
    fun `any buyer approves an order at or under the threshold, even their own`() {
        val order = draft(line(chips, 10, "50.00"))

        order.approve(buyer, limit, clock)

        order.state shouldBe PurchaseOrderState.Approved(Approval("buyer", today))
        val event = order.releaseEvents().single().shouldBeInstanceOf<PurchaseOrderApproved>()
        event.approvedBy shouldBe "buyer"
        event.purchaseOrder.total shouldBe BigDecimal("500.0000")
        event.purchaseOrder.lines
            .single()
            .lineNumber shouldBe 1
    }

    @Test
    fun `an order over the threshold needs an approver who did not create it`() {
        val order = draft(line(chips, 10, "50.01"))

        shouldThrow<ApprovalNeedsApprover> { order.approve(buyer, limit, clock) }
        shouldThrow<ApprovalByCreator> { order.approve(manager.copy(user = "buyer"), limit, clock) }
        order.state shouldBe PurchaseOrderState.Draft

        order.approve(manager, limit, clock)
        order.state.approval shouldBe Approval("manager", today)
    }

    @Test
    fun `with no threshold, or one in another currency, every order needs an approver`() {
        PoApprovalPolicy(null).needsSecondApprover(usd("0.01")) shouldBe true
        PoApprovalPolicy(Money.of("500.00", "CAD")).needsSecondApprover(usd("1.00")) shouldBe true
        limit.needsSecondApprover(usd("500.00")) shouldBe false
        shouldThrow<IllegalArgumentException> { ApprovalThreshold.of(usd("-1.00"), today) }
    }

    @Test
    fun `an approved order is sent once and then no longer changes`() {
        val order = draft(line(chips, 1, "18.00"))
        shouldThrow<PurchaseOrderStateForbids> { order.send(clock) }
        order.approve(buyer, limit, clock)
        order.releaseEvents()

        order.send(clock)

        order.state shouldBe PurchaseOrderState.Sent(Approval("buyer", today), today)
        order
            .releaseEvents()
            .single()
            .shouldBeInstanceOf<PurchaseOrderSent>()
            .purchaseOrder.warehouseId shouldBe
            warehouseId
        shouldThrow<PurchaseOrderStateForbids> { order.send(clock) }
        shouldThrow<PurchaseOrderStateForbids> { order.approve(manager, limit, clock) }
        shouldThrow<PurchaseOrderStateForbids> { order.revise(terms(today, line(cola, 1, "24.00")), clock) }
    }

    @Test
    fun `cancelling a draft is silent, cancelling an announced order is published`() {
        val reason = CancellationReason.of("  Vendor out of stock ")
        val unannounced = draft(line(chips, 1, "18.00"))
        unannounced.cancel(reason, clock)
        unannounced.releaseEvents().shouldBeEmpty()
        shouldThrow<PurchaseOrderStateForbids> { unannounced.cancel(reason, clock) }

        val sent = draft(line(chips, 1, "18.00"))
        sent.approve(buyer, limit, clock)
        sent.send(clock)
        sent.releaseEvents()
        sent.cancel(reason, clock)

        val state = sent.state.shouldBeInstanceOf<PurchaseOrderState.Cancelled>()
        state.reason shouldBe "Vendor out of stock"
        state.approval shouldBe Approval("buyer", today)
        state.sentOn shouldBe today
        sent
            .releaseEvents()
            .single()
            .shouldBeInstanceOf<PurchaseOrderCancelled>()
            .purchaseOrderId shouldBe sent.id
        shouldThrow<IllegalArgumentException> { CancellationReason.of(" ") }
        shouldThrow<IllegalArgumentException> { PurchaseOrderNumber.of("PO-1") }
    }

    private fun draft(
        vararg lines: PurchaseOrderLine,
        expectedOn: BusinessDate = today.plusDays(2),
    ) = PurchaseOrder.draft(PurchaseOrderNumber.of("100001"), vendorId, "buyer", terms(expectedOn, *lines), clock)

    private fun terms(
        expectedOn: BusinessDate,
        vararg lines: PurchaseOrderLine,
    ) = PurchaseOrderTerms(warehouseId, expectedOn, lines.toList())

    private fun line(
        itemId: ItemId,
        cases: Int,
        caseCost: String,
    ) = PurchaseOrderLine(itemId, "SKU", casePack = 12, cases = cases, caseCost = usd(caseCost))

    private fun usd(amount: String) = Money.of(amount, "USD")
}

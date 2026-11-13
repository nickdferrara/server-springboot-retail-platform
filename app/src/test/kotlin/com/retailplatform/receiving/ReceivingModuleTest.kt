package com.retailplatform.receiving

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.platform.security.Roles
import com.retailplatform.purchasing.api.events.PurchaseOrderCancelled
import com.retailplatform.purchasing.api.events.PurchaseOrderFacts
import com.retailplatform.purchasing.api.events.PurchaseOrderLineFacts
import com.retailplatform.purchasing.api.events.PurchaseOrderSent
import com.retailplatform.receiving.api.events.GoodsReceived
import com.retailplatform.receiving.api.events.ReceiptDiscrepancyRecorded
import com.retailplatform.receiving.application.ExpectedDeliveryRepository
import com.retailplatform.receiving.application.ReceiveGoods
import com.retailplatform.receiving.application.ReceiveGoodsCommand
import com.retailplatform.receiving.application.ReceivingQueries
import com.retailplatform.receiving.domain.CountedLine
import com.retailplatform.receiving.domain.DeliveryNotOpen
import com.retailplatform.receiving.domain.DeliveryStatus
import com.retailplatform.receiving.domain.PurchaseOrderNotExpected
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.Clock
import java.util.Optional
import java.util.function.Supplier

@ApplicationModuleTest
@Import(TestcontainersConfiguration::class, KnownStoresAndVendors::class)
@WithMockUser(username = "clerk", authorities = [Roles.RECEIVING_CLERK])
class ReceivingModuleTest(
    @Autowired private val receiveGoods: ReceiveGoods,
    @Autowired private val receiving: ReceivingQueries,
    @Autowired private val deliveries: ExpectedDeliveryRepository,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)
    private val warehouseId = WarehouseId.generate(clock)
    private val chips = ItemId.generate(clock)
    private val cola = ItemId.generate(clock)

    @Test
    fun `a sent purchase order is expected at its warehouse and received in parts`(scenario: Scenario) {
        val order = sent(scenario, "200001")
        receiving.openDeliveries(warehouseId, PageQuery.of()).items.map { it.against.purchaseOrderId } shouldContain
            order

        var firstReceipt: ReceiptId? = null
        scenario
            .stimulate(
                Runnable {
                    firstReceipt =
                        receiveGoods.handle(ReceiveGoodsCommand(order, listOf(CountedLine.of(1, 4)), false))
                },
            ).andWaitForEventOfType(GoodsReceived::class.java)
            .matching { it.receipt.purchaseOrderId == order }
            .toArriveAndVerify { event ->
                event.purchaseOrderComplete shouldBe false
                event.receipt.warehouseId shouldBe warehouseId
                event.currency shouldBe "USD"
                event.lines.single().casesReceived shouldBe 4
                event.lines.single().caseCost shouldBe BigDecimal("18.0000")
            }

        scenario
            .stimulate(
                Runnable {
                    receiveGoods.handle(
                        ReceiveGoodsCommand(order, listOf(CountedLine.of(1, 6), CountedLine.of(2, 5)), false),
                    )
                },
            ).andWaitForEventOfType(GoodsReceived::class.java)
            .matching { it.receipt.purchaseOrderId == order && it.purchaseOrderComplete }
            .toArriveAndVerify { event -> event.lines.map { it.casesReceived } shouldBe listOf(6, 5) }

        val delivery = receiving.expected(order)
        delivery.status shouldBe DeliveryStatus.COMPLETE
        delivery.lines.map { it.casesReceived } shouldBe listOf(10, 5)
        val receipts = receiving.receiptsFor(order)
        receipts.map { it.id } shouldContain firstReceipt
        receipts.size shouldBe 2
        receipts.first().receivedBy shouldBe "clerk"
        receipts
            .first()
            .lines
            .single()
            .extendedCost shouldBe Money.of("72.00", "USD")
        receiving.receipt(receipts.last().id).number shouldBe receipts.last().number
        receiving.openDeliveries(warehouseId, PageQuery.of()).items.map { it.against.purchaseOrderId } shouldNotContain
            order
        shouldThrow<DeliveryNotOpen> {
            receiveGoods.handle(ReceiveGoodsCommand(order, listOf(CountedLine.of(1, 1)), false))
        }
    }

    @Test
    fun `a delivery that differs from its order is announced as a discrepancy`(scenario: Scenario) {
        val order = sent(scenario, "200002")
        val counted = listOf(CountedLine.of(1, 11, casesDamaged = 2))

        scenario
            .stimulate(Runnable { receiveGoods.handle(ReceiveGoodsCommand(order, counted, finalDelivery = true)) })
            .andWaitForEventOfType(ReceiptDiscrepancyRecorded::class.java)
            .matching { it.receipt.purchaseOrderId == order }
            .toArriveAndVerify { event ->
                event.lines.map { Triple(it.casesOver, it.casesShort, it.casesDamaged) } shouldBe
                    listOf(Triple(1, 0, 2), Triple(0, 5, 0))
            }

        val receipt = receiving.receiptsFor(order).single()
        receipt.finalDelivery shouldBe true
        receipt.lines.map { it.casesShort } shouldBe listOf(0, 5)
    }

    @Test
    fun `a cancelled purchase order is no longer expected`(scenario: Scenario) {
        val order = sent(scenario, "200003")

        scenario
            .publish(
                PurchaseOrderCancelled(EventId.generate(clock), clock.instant(), today, order, "200003", "Dropped"),
            ).andWaitForStateChange(
                Supplier {
                    Optional.ofNullable(
                        deliveries.findById(order)?.takeIf { it.status != DeliveryStatus.OPEN },
                    )
                },
            ).andVerify { delivery -> delivery.orElseThrow().status shouldBe DeliveryStatus.CANCELLED }

        shouldThrow<DeliveryNotOpen> {
            receiveGoods.handle(ReceiveGoodsCommand(order, listOf(CountedLine.of(1, 1)), false))
        }
    }

    @Test
    fun `only a purchase order that was sent can be received`() {
        val unknown = PurchaseOrderId.generate(clock)

        shouldThrow<PurchaseOrderNotExpected> {
            receiveGoods.handle(ReceiveGoodsCommand(unknown, listOf(CountedLine.of(1, 1)), false))
        }
        shouldThrow<ResourceNotFound> { receiving.expected(unknown) }
        shouldThrow<ResourceNotFound> { receiving.receipt(ReceiptId.generate(clock)) }
    }

    @Test
    @WithMockUser(authorities = [Roles.BUYER])
    fun `only receiving clerks check deliveries in`() {
        shouldThrow<AccessDeniedException> {
            receiveGoods.handle(ReceiveGoodsCommand(PurchaseOrderId.generate(clock), emptyList(), true))
        }
    }

    private fun sent(
        scenario: Scenario,
        number: String,
    ): PurchaseOrderId {
        val id = PurchaseOrderId.generate(clock)
        val facts =
            PurchaseOrderFacts(
                purchaseOrderId = id,
                purchaseOrderNumber = number,
                vendorId = VendorId.generate(clock),
                warehouseId = warehouseId,
                expectedOn = today.plusDays(2),
                currency = "USD",
                total = BigDecimal("300.0000"),
                lines =
                    listOf(
                        PurchaseOrderLineFacts(1, chips, "CH-1", 12, 10, BigDecimal("18.0000")),
                        PurchaseOrderLineFacts(2, cola, "CO-1", 24, 5, BigDecimal("24.0000")),
                    ),
            )
        scenario
            .publish(PurchaseOrderSent(EventId.generate(clock), clock.instant(), today, facts))
            .andWaitForStateChange(Supplier { Optional.ofNullable(deliveries.findById(id)) })
            .andVerify { delivery -> delivery.orElseThrow().status shouldBe DeliveryStatus.OPEN }
        return id
    }
}

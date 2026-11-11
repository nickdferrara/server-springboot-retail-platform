package com.retailplatform.purchasing

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.catalog.api.events.VendorCostChanged
import com.retailplatform.locations.application.CreateWarehouse
import com.retailplatform.locations.application.CreateWarehouseCommand
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.WarehouseNumber
import com.retailplatform.platform.security.Roles
import com.retailplatform.purchasing.api.events.PurchaseOrderApproved
import com.retailplatform.purchasing.api.events.PurchaseOrderCancelled
import com.retailplatform.purchasing.api.events.PurchaseOrderSent
import com.retailplatform.purchasing.application.ApprovalThresholdQueries
import com.retailplatform.purchasing.application.ApprovePurchaseOrder
import com.retailplatform.purchasing.application.CancelPurchaseOrder
import com.retailplatform.purchasing.application.CancelPurchaseOrderCommand
import com.retailplatform.purchasing.application.CreatePurchaseOrder
import com.retailplatform.purchasing.application.CreatePurchaseOrderCommand
import com.retailplatform.purchasing.application.PurchaseOrderQueries
import com.retailplatform.purchasing.application.PurchaseOrderTermsInput
import com.retailplatform.purchasing.application.RecordVendorCost
import com.retailplatform.purchasing.application.RevisePurchaseOrder
import com.retailplatform.purchasing.application.RevisePurchaseOrderCommand
import com.retailplatform.purchasing.application.SendPurchaseOrder
import com.retailplatform.purchasing.application.SetApprovalThreshold
import com.retailplatform.purchasing.application.VendorItemCostRepository
import com.retailplatform.purchasing.domain.ApprovalByCreator
import com.retailplatform.purchasing.domain.ApprovalNeedsApprover
import com.retailplatform.purchasing.domain.ApprovalThreshold
import com.retailplatform.purchasing.domain.CancellationReason
import com.retailplatform.purchasing.domain.ItemNotSuppliedByVendor
import com.retailplatform.purchasing.domain.PurchaseOrderState
import com.retailplatform.purchasing.domain.PurchaseOrderStateForbids
import com.retailplatform.purchasing.domain.RequestedLine
import com.retailplatform.purchasing.domain.ThresholdEffectiveInPast
import com.retailplatform.purchasing.domain.UnknownVendor
import com.retailplatform.purchasing.domain.UnknownWarehouse
import com.retailplatform.purchasing.domain.VendorItemCost
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.PurchaseOrderId
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import com.retailplatform.vendors.application.CreateVendor
import com.retailplatform.vendors.application.CreateVendorCommand
import com.retailplatform.vendors.domain.PaymentTerms
import com.retailplatform.vendors.domain.PostalAddress
import com.retailplatform.vendors.domain.VendorName
import com.retailplatform.vendors.domain.VendorNumber
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.modulith.test.Scenario
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneOffset
import java.util.Optional
import java.util.function.Supplier

/** Runs with the vendors and locations modules too: an order asks them whether its vendor and warehouse exist. */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "buyer", authorities = [Roles.BUYER])
class PurchasingModuleTest(
    @Autowired private val createPurchaseOrder: CreatePurchaseOrder,
    @Autowired private val revisePurchaseOrder: RevisePurchaseOrder,
    @Autowired private val approvePurchaseOrder: ApprovePurchaseOrder,
    @Autowired private val sendPurchaseOrder: SendPurchaseOrder,
    @Autowired private val cancelPurchaseOrder: CancelPurchaseOrder,
    @Autowired private val orders: PurchaseOrderQueries,
    @Autowired private val setApprovalThreshold: SetApprovalThreshold,
    @Autowired private val thresholds: ApprovalThresholdQueries,
    @Autowired private val recordVendorCost: RecordVendorCost,
    @Autowired private val costs: VendorItemCostRepository,
    @Autowired private val createVendor: CreateVendor,
    @Autowired private val createWarehouse: CreateWarehouse,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)
    private var vendorId = VendorId.generate(clock)
    private var warehouseId = WarehouseId.generate(clock)
    private var chips = ItemId.generate(clock)

    @BeforeEach
    fun masterData() {
        actingAs("setup", Roles.MASTER_DATA_EDITOR, Roles.BUYER_APPROVER)
        val unique = (System.nanoTime() % 1_000_000).toString()
        vendorId =
            createVendor.handle(
                CreateVendorCommand(
                    VendorNumber.of(unique),
                    VendorName.of("Snack supplier"),
                    PaymentTerms.net(30),
                    PostalAddress.of("1 Mill Road", null, "Springfield", "IL", "62701", "US"),
                    emptyList(),
                ),
            )
        warehouseId =
            createWarehouse.handle(
                CreateWarehouseCommand(WarehouseNumber.of(unique), LocationName.of("Central DC"), ZoneOffset.UTC),
            )
        chips = supplied("CH-1", casePack = 12, caseCost = "18.00")
        setApprovalThreshold.handle(ApprovalThreshold.of(usd("500.00"), today))
        actingAs("buyer", Roles.BUYER)
    }

    @Test
    fun `a vendor cost changed in the catalog becomes orderable in purchasing`(scenario: Scenario) {
        val itemId = ItemId.generate(clock)
        val event =
            VendorCostChanged(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = today,
                itemId = itemId,
                vendorId = vendorId,
                vendorSku = "CO-24",
                casePack = 24,
                previousCost = null,
                newCost = BigDecimal("21.5000"),
                currency = "USD",
            )

        scenario
            .publish(event)
            .andWaitForStateChange(
                Supplier { Optional.ofNullable(costs.findByVendorAndItems(vendorId, listOf(itemId))[itemId]) },
            ).andVerify { cost -> cost.orElseThrow().caseCost shouldBe usd("21.50") }

        val order = orders.find(createPurchaseOrder.handle(command(RequestedLine.of(itemId, 4))))
        order.lines.single().casePack shouldBe 24
        order.total shouldBe usd("86.00")
    }

    @Test
    fun `an order is drafted, revised, approved, and sent`(scenario: Scenario) {
        val cola = supplied("CO-1", casePack = 24, caseCost = "24.00")
        val id = createPurchaseOrder.handle(command(RequestedLine.of(chips, 10)))
        val draft = orders.find(id)
        draft.state shouldBe PurchaseOrderState.Draft
        draft.buyer shouldBe "buyer"
        draft.total shouldBe usd("180.00")

        revisePurchaseOrder.handle(
            RevisePurchaseOrderCommand(
                id,
                termsInput(today.plusDays(4), RequestedLine.of(chips, 10), RequestedLine.of(cola, 5, usd("23.00"))),
            ),
        )
        orders.find(id).total shouldBe usd("295.00")

        scenario
            .stimulate(Runnable { approvePurchaseOrder.handle(id) })
            .andWaitForEventOfType(PurchaseOrderApproved::class.java)
            .matching { it.purchaseOrder.purchaseOrderId == id }
            .toArriveAndVerify { event ->
                event.approvedBy shouldBe "buyer"
                event.purchaseOrder.lines.map { it.cases } shouldBe listOf(10, 5)
            }

        scenario
            .stimulate(Runnable { sendPurchaseOrder.handle(id) })
            .andWaitForEventOfType(PurchaseOrderSent::class.java)
            .matching { it.purchaseOrder.purchaseOrderId == id }
            .toArriveAndVerify { event ->
                event.purchaseOrder.expectedOn shouldBe today.plusDays(4)
                event.purchaseOrder.total shouldBe BigDecimal("295.0000")
            }

        val sent = orders.find(id)
        sent.state.shouldBeInstanceOf<PurchaseOrderState.Sent>().sentOn shouldBe today
        sent.number.value shouldBe draft.number.value
        orders.list(PageQuery.of(0, 5)).items.map { it.id } shouldContain id
        shouldThrow<PurchaseOrderStateForbids> {
            revisePurchaseOrder.handle(RevisePurchaseOrderCommand(id, termsInput(today, RequestedLine.of(chips, 1))))
        }
    }

    @Test
    fun `an order over the threshold needs an approver other than its buyer`() {
        val id = createPurchaseOrder.handle(command(RequestedLine.of(chips, 30)))

        shouldThrow<ApprovalNeedsApprover> { approvePurchaseOrder.handle(id) }
        actingAs("buyer", Roles.BUYER, Roles.BUYER_APPROVER)
        shouldThrow<ApprovalByCreator> { approvePurchaseOrder.handle(id) }
        actingAs("manager", Roles.BUYER_APPROVER)
        approvePurchaseOrder.handle(id)

        orders
            .find(id)
            .state.approval
            ?.approvedBy shouldBe "manager"
        thresholds.current().amount shouldBe usd("500.00")
        shouldThrow<ThresholdEffectiveInPast> {
            setApprovalThreshold.handle(ApprovalThreshold.of(usd("900.00"), today.minusDays(1)))
        }
    }

    @Test
    fun `cancelling a sent order is announced, and a cancelled order stays cancelled`(scenario: Scenario) {
        val id = createPurchaseOrder.handle(command(RequestedLine.of(chips, 1)))
        approvePurchaseOrder.handle(id)
        sendPurchaseOrder.handle(id)
        val cancel = CancelPurchaseOrderCommand(id, CancellationReason.of("Vendor out of stock"))

        scenario
            .stimulate(Runnable { cancelPurchaseOrder.handle(cancel) })
            .andWaitForEventOfType(PurchaseOrderCancelled::class.java)
            .matching { it.purchaseOrderId == id }
            .toArriveAndVerify { event -> event.reason shouldBe "Vendor out of stock" }

        val cancelled = orders.find(id).state.shouldBeInstanceOf<PurchaseOrderState.Cancelled>()
        cancelled.cancelledOn shouldBe today
        cancelled.sentOn shouldBe today
        shouldThrow<PurchaseOrderStateForbids> { cancelPurchaseOrder.handle(cancel) }
    }

    @Test
    fun `an order needs a vendor, a warehouse, and items the vendor supplies`() {
        shouldThrow<UnknownVendor> {
            createPurchaseOrder.handle(command(RequestedLine.of(chips, 1)).copy(vendorId = VendorId.generate(clock)))
        }
        shouldThrow<UnknownWarehouse> {
            createPurchaseOrder.handle(
                CreatePurchaseOrderCommand(
                    vendorId,
                    PurchaseOrderTermsInput(WarehouseId.generate(clock), today, listOf(RequestedLine.of(chips, 1))),
                ),
            )
        }
        shouldThrow<ItemNotSuppliedByVendor> {
            createPurchaseOrder.handle(command(RequestedLine.of(ItemId.generate(clock), 1)))
        }
        shouldThrow<ResourceNotFound> { orders.find(PurchaseOrderId.generate(clock)) }
    }

    @Test
    fun `only buyers work with purchase orders, and only approvers set the threshold`() {
        actingAs("clerk", Roles.MASTER_DATA_EDITOR)
        shouldThrow<AccessDeniedException> { createPurchaseOrder.handle(command(RequestedLine.of(chips, 1))) }
        shouldThrow<AccessDeniedException> { approvePurchaseOrder.handle(PurchaseOrderId.generate(clock)) }
        actingAs("buyer", Roles.BUYER)
        shouldThrow<AccessDeniedException> { setApprovalThreshold.handle(ApprovalThreshold.of(usd("1.00"), today)) }
    }

    private fun supplied(
        sku: String,
        casePack: Int,
        caseCost: String,
    ): ItemId =
        ItemId.generate(clock).also {
            recordVendorCost.handle(VendorItemCost(vendorId, it, sku, casePack, usd(caseCost)))
        }

    private fun command(vararg lines: RequestedLine) =
        CreatePurchaseOrderCommand(vendorId, termsInput(today.plusDays(2), *lines))

    private fun termsInput(
        expectedOn: BusinessDate,
        vararg lines: RequestedLine,
    ) = PurchaseOrderTermsInput(warehouseId, expectedOn, lines.toList())

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun usd(amount: String) = Money.of(amount, "USD")
}

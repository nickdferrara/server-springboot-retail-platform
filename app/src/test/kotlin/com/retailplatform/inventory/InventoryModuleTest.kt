package com.retailplatform.inventory

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.catalog.api.events.ItemCreated
import com.retailplatform.catalog.api.events.ItemFacts
import com.retailplatform.inventory.api.events.InventoryAdjusted
import com.retailplatform.inventory.application.AdjustInventory
import com.retailplatform.inventory.application.AdjustInventoryCommand
import com.retailplatform.inventory.application.ReceiveStock
import com.retailplatform.inventory.application.ReceiveStockCommand
import com.retailplatform.inventory.application.ReceivedStock
import com.retailplatform.inventory.application.RecordCycleCount
import com.retailplatform.inventory.application.RecordCycleCountCommand
import com.retailplatform.inventory.application.RegisterStockedItem
import com.retailplatform.inventory.application.StockQueries
import com.retailplatform.inventory.application.StockedItemRepository
import com.retailplatform.inventory.domain.AdjustmentExceedsOnHand
import com.retailplatform.inventory.domain.AdjustmentReason
import com.retailplatform.inventory.domain.CostNotKnown
import com.retailplatform.inventory.domain.ItemNotKnownToInventory
import com.retailplatform.inventory.domain.MovementSource
import com.retailplatform.inventory.domain.MovementType
import com.retailplatform.inventory.domain.QuantityNotInBaseUnit
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.inventory.domain.StockedItem
import com.retailplatform.inventory.domain.UnknownStockLocation
import com.retailplatform.locations.application.CreateWarehouse
import com.retailplatform.locations.application.CreateWarehouseCommand
import com.retailplatform.locations.application.OpenStore
import com.retailplatform.locations.application.OpenStoreCommand
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.locations.domain.WarehouseNumber
import com.retailplatform.platform.security.Roles
import com.retailplatform.receiving.api.events.DirectDeliveryFacts
import com.retailplatform.receiving.api.events.DirectDeliveryLine
import com.retailplatform.receiving.api.events.DirectDeliveryReceived
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.DirectDeliveryId
import com.retailplatform.shared.EventId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.Quantity
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.UnitOfMeasure
import com.retailplatform.shared.VendorId
import com.retailplatform.shared.WarehouseId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/** Runs with the locations module too: stock is only kept at a warehouse or store that exists. */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(TestcontainersConfiguration::class)
@WithMockUser(username = "counter", authorities = [Roles.INVENTORY_CONTROLLER])
class InventoryModuleTest(
    @Autowired private val adjustInventory: AdjustInventory,
    @Autowired private val recordCycleCount: RecordCycleCount,
    @Autowired private val receiveStock: ReceiveStock,
    @Autowired private val stock: StockQueries,
    @Autowired private val registerStockedItem: RegisterStockedItem,
    @Autowired private val stockedItems: StockedItemRepository,
    @Autowired private val createWarehouse: CreateWarehouse,
    @Autowired private val openStore: OpenStore,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
) {
    private val today get() = BusinessDate.today(clock)
    private var warehouse: StockLocation = StockLocation.Warehouse(WarehouseId.generate(clock))
    private var store: StockLocation = StockLocation.Store(StoreId.generate(clock))

    @BeforeEach
    fun locations() {
        actingAs("setup", Roles.MASTER_DATA_EDITOR)
        val unique = (System.nanoTime() % 1_000_000).toString()
        warehouse =
            StockLocation.Warehouse(
                createWarehouse.handle(
                    CreateWarehouseCommand(WarehouseNumber.of(unique), LocationName.of("Central DC"), ZoneOffset.UTC),
                ),
            )
        store =
            StockLocation.Store(
                openStore.handle(
                    OpenStoreCommand(StoreNumber.of(unique), LocationName.of("Main Street"), ZoneOffset.UTC, today),
                ),
            )
        actingAs("counter", Roles.INVENTORY_CONTROLLER)
    }

    @Test
    fun `an item created in the catalog can be stocked in its base unit`(scenario: Scenario) {
        val itemId = ItemId.generate(clock)
        val facts = ItemFacts("700001", "Ground coffee", "GROC", "COFFEE", "LB", emptyList())

        scenario
            .publish(ItemCreated(EventId.generate(clock), clock.instant(), today, itemId, facts))
            .andWaitForStateChange(Supplier { Optional.ofNullable(stockedItems.findById(itemId)) })
            .andVerify { item -> item.orElseThrow().baseUnit shouldBe UnitOfMeasure.POUND }

        val pounds = Quantity.of(BigDecimal("12.500"), UnitOfMeasure.POUND)
        recordCycleCount.handle(RecordCycleCountCommand(itemId, warehouse, pounds, usd("6.40")))

        stock.onHand(itemId, warehouse).value shouldBe usd("80.00")
        shouldThrow<QuantityNotInBaseUnit> {
            adjustInventory.handle(AdjustInventoryCommand(itemId, warehouse, each(-1), AdjustmentReason.SHRINK, null))
        }
    }

    @Test
    fun `a count brings stock in, shrink takes it out, and the ledger explains on-hand`(scenario: Scenario) {
        val itemId = item("700002")
        stock.onHand(itemId, warehouse).onHand shouldBe each(0)

        val counted = recordCycleCount.handle(RecordCycleCountCommand(itemId, warehouse, each(120), usd("1.50")))
        recordCycleCount.handle(RecordCycleCountCommand(itemId, warehouse, each(120), null)) shouldBe null

        scenario
            .stimulate(
                Runnable {
                    adjustInventory.handle(
                        AdjustInventoryCommand(itemId, warehouse, each(-20), AdjustmentReason.DAMAGE, null),
                    )
                },
            ).andWaitForEventOfType(InventoryAdjusted::class.java)
            .matching { it.itemId == itemId && it.reason == "DAMAGE" }
            .toArriveAndVerify { event ->
                event.quantity shouldBe BigDecimal("-20.000")
                event.extendedCost shouldBe BigDecimal("-30.0000")
                event.warehouseId shouldBe (warehouse as StockLocation.Warehouse).warehouseId
                event.businessDate shouldBe today
            }

        val level = stock.onHand(itemId, warehouse)
        level.onHand shouldBe each(100)
        level.averageCost shouldBe usd("1.50")
        val ledger = stock.movements(itemId, warehouse, PageQuery.of()).items
        ledger.map { it.quantity } shouldBe listOf(each(-20), each(120))
        ledger.map { it.type }.toSet() shouldBe setOf(MovementType.ADJUSTMENT)
        ledger.last().id shouldBe counted?.id
        ledger.first().after.onHand shouldBe level.onHand
        ledger.fold(each(0)) { sum, movement -> sum + movement.quantity } shouldBe level.onHand
        stock.onHand(warehouse, PageQuery.of()).items.map { it.itemId } shouldContain itemId
        recordedBy(ledger.first().id.value) shouldBe "counter"
    }

    @Test
    fun `receipts for the same item arriving together are all taken into stock`() {
        val itemId = item("700006")
        val together = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(together)

        val receipts =
            (1..together).map {
                pool.submit {
                    start.await()
                    receiveStock.handle(
                        ReceiveStockCommand(
                            location = warehouse,
                            businessDate = today,
                            source = MovementSource("Receipt $it", EventId.generate(clock)),
                            lines = listOf(ReceivedStock(itemId, BigDecimal(12), usd("1.50"))),
                        ),
                    )
                }
            }
        start.countDown()
        receipts.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        val level = stock.onHand(itemId, warehouse)
        level.onHand shouldBe each(12 * together)
        level.averageCost shouldBe usd("1.50")
        val ledger = stock.movements(itemId, warehouse, PageQuery.of()).items
        ledger.size shouldBe together
        ledger
            .map {
                it.after.onHand.amount
                    .toInt()
            }.sorted() shouldBe (1..together).map { it * 12 }
    }

    @Test
    fun `a direct delivery enters the store's stock and what the driver took back leaves it`(scenario: Scenario) {
        val cola = item("700010")
        val chips = item("700011")
        val storeId = (store as StockLocation.Store).storeId
        val event =
            DirectDeliveryReceived(
                eventId = EventId.generate(clock),
                occurredAt = clock.instant(),
                businessDate = today,
                delivery =
                    DirectDeliveryFacts(
                        DirectDeliveryId.generate(clock),
                        "100077",
                        VendorId.generate(clock),
                        storeId,
                        "T-1",
                    ),
                currency = "USD",
                lines =
                    listOf(
                        DirectDeliveryLine(1, cola, 20, 3, BigDecimal("1.2500")),
                        DirectDeliveryLine(2, chips, 0, 5, BigDecimal("0.8000")),
                    ),
            )

        scenario
            .publish(event)
            .andWaitForStateChange(
                Supplier { Optional.of(stock.onHand(chips, store)).filter { !it.onHand.isZero } },
            ).andVerify { it.isPresent shouldBe true }

        stock.onHand(cola, store).onHand shouldBe each(17)
        stock.onHand(cola, store).averageCost shouldBe usd("1.25")
        stock.onHand(chips, store).onHand shouldBe each(-5)
        val movements = stock.movements(cola, store, PageQuery.of()).items
        movements.map { it.type } shouldBe listOf(MovementType.VENDOR_RETURN, MovementType.RECEIPT)
        movements.first().source.reference shouldBe "Receipt 100077"
    }

    @Test
    fun `each location keeps its own quantity and cost`() {
        val itemId = item("700003")
        recordCycleCount.handle(RecordCycleCountCommand(itemId, warehouse, each(50), usd("2.00")))
        recordCycleCount.handle(RecordCycleCountCommand(itemId, store, each(6), usd("2.10")))

        stock.onHand(itemId, warehouse).value shouldBe usd("100.00")
        stock.onHand(itemId, store).value shouldBe usd("12.60")
        stock.movements(itemId, store, PageQuery.of()).total shouldBe 1
    }

    @Test
    fun `the ledger cannot be rewritten`() {
        val itemId = item("700004")
        val movement = recordCycleCount.handle(RecordCycleCountCommand(itemId, warehouse, each(5), usd("1.00")))
        val id = movement?.id?.value

        shouldThrow<DataAccessException> {
            jdbc.sql("UPDATE inventory.inventory_movement SET quantity = 500 WHERE id = :id").param("id", id).update()
        }
        shouldThrow<DataAccessException> {
            jdbc.sql("DELETE FROM inventory.inventory_movement WHERE id = :id").param("id", id).update()
        }
    }

    @Test
    fun `stock needs a known item, an existing location, enough on hand, and a cost`() {
        val itemId = item("700005")
        val nowhere = StockLocation.Warehouse(WarehouseId.generate(clock))

        shouldThrow<ItemNotKnownToInventory> {
            recordCycleCount.handle(RecordCycleCountCommand(ItemId.generate(clock), warehouse, each(1), usd("1.00")))
        }
        shouldThrow<UnknownStockLocation> {
            recordCycleCount.handle(RecordCycleCountCommand(itemId, nowhere, each(1), usd("1.00")))
        }
        shouldThrow<UnknownStockLocation> {
            stock.onHand(itemId, StockLocation.Store(StoreId.generate(clock)))
        }
        shouldThrow<CostNotKnown> { recordCycleCount.handle(RecordCycleCountCommand(itemId, warehouse, each(1), null)) }
        shouldThrow<AdjustmentExceedsOnHand> {
            adjustInventory.handle(AdjustInventoryCommand(itemId, warehouse, each(-1), AdjustmentReason.SHRINK, null))
        }
    }

    @Test
    fun `only inventory control may change stock`() {
        actingAs("buyer", Roles.BUYER)
        val command = AdjustInventoryCommand(ItemId.generate(clock), warehouse, each(-1), AdjustmentReason.SHRINK, null)

        shouldThrow<AccessDeniedException> { adjustInventory.handle(command) }
        shouldThrow<AccessDeniedException> {
            recordCycleCount.handle(RecordCycleCountCommand(command.itemId, warehouse, each(1), null))
        }
    }

    private fun item(number: String): ItemId =
        ItemId.generate(clock).also { registerStockedItem.handle(StockedItem(it, number, UnitOfMeasure.EACH)) }

    private fun recordedBy(movementId: java.util.UUID): String =
        jdbc
            .sql("SELECT recorded_by FROM inventory.inventory_movement WHERE id = :id")
            .param("id", movementId)
            .query(String::class.java)
            .single()

    private fun actingAs(
        user: String,
        vararg roles: String,
    ) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user, "n/a", roles.map(::SimpleGrantedAuthority))
    }

    private fun each(amount: Int) = Quantity.of(amount, UnitOfMeasure.EACH)

    private fun usd(amount: String) = Money.of(amount, "USD")
}

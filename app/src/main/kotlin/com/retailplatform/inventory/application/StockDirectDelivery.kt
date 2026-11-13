package com.retailplatform.inventory.application

import com.retailplatform.inventory.domain.MovementSource
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Quantity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock

/** [unitsDelivered] of an item put on the shelf and [unitsReturned] taken back, in base units at [unitCost]. */
data class DeliveredStock(
    val itemId: ItemId,
    val unitsDelivered: Int,
    val unitsReturned: Int,
    val unitCost: Money,
)

data class StockDirectDeliveryCommand(
    val store: StockLocation.Store,
    val businessDate: BusinessDate,
    val source: MovementSource,
    val lines: List<DeliveredStock>,
)

/**
 * Use case: bring a vendor's direct delivery into a store's stock at agreed cost, and take out what the vendor's
 * driver took back.
 */
@Service
class StockDirectDelivery(
    private val stockLevels: StockLevels,
    private val posting: StockPosting,
    private val clock: Clock,
) {
    @Transactional
    fun handle(command: StockDirectDeliveryCommand) {
        // Items are taken in a fixed order, so two deliveries sharing items never wait on each other in a circle.
        command.lines.sortedBy { it.itemId.value }.forEach { line ->
            if (line.unitsDelivered > 0) {
                val level = stockLevels.forChange(line.itemId, command.store)
                val quantity = Quantity.of(BigDecimal(line.unitsDelivered), level.onHand.unit)
                posting.post(level, level.receive(quantity, line.unitCost, command.businessDate, command.source, clock))
            }
            if (line.unitsReturned > 0) {
                // Read again: the delivery above has changed the stored level and its version.
                val level = stockLevels.forChange(line.itemId, command.store)
                val quantity = Quantity.of(BigDecimal(line.unitsReturned), level.onHand.unit)
                posting.post(
                    level,
                    level.returnToVendor(quantity, line.unitCost, command.businessDate, command.source, clock),
                )
            }
        }
    }
}

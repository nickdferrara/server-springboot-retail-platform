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

/** [baseUnits] of an item accepted into stock at [unitCost] per base unit. */
data class ReceivedStock(
    val itemId: ItemId,
    val baseUnits: BigDecimal,
    val unitCost: Money,
)

data class ReceiveStockCommand(
    val location: StockLocation,
    val businessDate: BusinessDate,
    val source: MovementSource,
    val lines: List<ReceivedStock>,
)

/** Use case: take received goods into a location's stock at their purchase cost, moving its average cost. */
@Service
class ReceiveStock(
    private val stockLevels: StockLevels,
    private val posting: StockPosting,
    private val clock: Clock,
) {
    @Transactional
    fun handle(command: ReceiveStockCommand) {
        command.lines.forEach { line ->
            val level = stockLevels.at(line.itemId, command.location)
            val quantity = Quantity.of(line.baseUnits, level.onHand.unit)
            val movement = level.receive(quantity, line.unitCost, command.businessDate, command.source, clock)
            posting.post(level, movement)
        }
    }
}

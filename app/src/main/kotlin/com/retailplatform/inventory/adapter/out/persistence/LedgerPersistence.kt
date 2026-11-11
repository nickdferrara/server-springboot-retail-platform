package com.retailplatform.inventory.adapter.out.persistence

import com.retailplatform.inventory.application.InventoryLedger
import com.retailplatform.inventory.domain.AdjustmentReason
import com.retailplatform.inventory.domain.InventoryMovement
import com.retailplatform.inventory.domain.MovementSource
import com.retailplatform.inventory.domain.MovementType
import com.retailplatform.inventory.domain.StockLocation
import com.retailplatform.inventory.domain.StockPosition
import com.retailplatform.platform.security.CurrentUser
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.EventId
import com.retailplatform.shared.InventoryMovementId
import com.retailplatform.shared.ItemId
import com.retailplatform.shared.Money
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.Quantity
import com.retailplatform.shared.UnitOfMeasure
import org.springframework.data.annotation.Id
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

@Table(schema = "inventory", name = "inventory_movement")
data class InventoryMovementEntity(
    @Id val id: UUID,
    val itemId: UUID,
    val locationType: String,
    val locationId: UUID,
    val movementType: String,
    val reason: String?,
    val quantity: BigDecimal,
    val unit: String,
    val unitCost: BigDecimal,
    val extendedCost: BigDecimal,
    val currency: String,
    val onHandAfter: BigDecimal,
    val averageCostAfter: BigDecimal?,
    val businessDate: LocalDate,
    val occurredAt: Instant,
    val sourceReference: String?,
    val sourceEventId: UUID?,
    val recordedBy: String,
)

/** Read-only on purpose: entries are inserted through [JdbcAggregateTemplate] and never updated or deleted. */
interface InventoryMovementJdbcRepository : Repository<InventoryMovementEntity, UUID> {
    fun findByItemIdAndLocationTypeAndLocationId(
        itemId: UUID,
        locationType: String,
        locationId: UUID,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<InventoryMovementEntity>
}

@org.springframework.stereotype.Repository
class JdbcInventoryLedger(
    private val movements: InventoryMovementJdbcRepository,
    private val template: JdbcAggregateTemplate,
    private val currentUser: CurrentUser,
) : InventoryLedger {
    override fun append(movement: InventoryMovement) {
        template.insert(
            InventoryMovementEntity(
                id = movement.id.value,
                itemId = movement.itemId.value,
                locationType = movement.location.type,
                locationId = movement.location.id,
                movementType = movement.type.name,
                reason = movement.reason?.name,
                quantity = movement.quantity.amount,
                unit = movement.quantity.unit.code,
                unitCost = movement.unitCost.amount,
                extendedCost = movement.extendedCost.amount,
                currency = movement.unitCost.currency.currencyCode,
                onHandAfter = movement.after.onHand.amount,
                averageCostAfter = movement.after.averageCost?.amount,
                businessDate = movement.businessDate.value,
                occurredAt = movement.occurredAt,
                sourceReference = movement.source.reference,
                sourceEventId = movement.source.eventId?.value,
                recordedBy = currentUser.name,
            ),
        )
    }

    override fun findPage(
        itemId: ItemId,
        location: StockLocation,
        query: PageQuery,
    ): Page<InventoryMovement> {
        // Identifiers are time-ordered, so the newest entry comes first.
        val newestFirst = PageRequest.of(query.page, query.size, Sort.by(Sort.Direction.DESC, "id"))
        val page =
            movements.findByItemIdAndLocationTypeAndLocationId(itemId.value, location.type, location.id, newestFirst)
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun InventoryMovementEntity.toDomain(): InventoryMovement {
        val unitOfMeasure = UnitOfMeasure.of(unit)
        return InventoryMovement(
            id = InventoryMovementId.of(id),
            itemId = ItemId.of(itemId),
            location = StockLocation.of(locationType, locationId),
            type = MovementType.valueOf(movementType),
            reason = reason?.let(AdjustmentReason::valueOf),
            quantity = Quantity.of(quantity, unitOfMeasure),
            unitCost = Money.of(unitCost, Currency.getInstance(currency)),
            after = StockPosition(Quantity.of(onHandAfter, unitOfMeasure), money(averageCostAfter, currency)),
            businessDate = BusinessDate(businessDate),
            occurredAt = occurredAt,
            source = MovementSource(sourceReference, sourceEventId?.let(EventId::of)),
        )
    }
}

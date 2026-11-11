package com.retailplatform.shared

import java.util.UUID

// Identifiers that many modules refer to. An identifier used by a single module is declared in that module.

/** Identifies one published domain event; listeners use it to process each event once. */
@JvmInline
value class EventId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<EventId>(::EventId)
}

@JvmInline
value class ItemId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<ItemId>(::ItemId)
}

@JvmInline
value class StoreId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<StoreId>(::StoreId)
}

@JvmInline
value class VendorId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<VendorId>(::VendorId)
}

@JvmInline
value class WarehouseId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<WarehouseId>(::WarehouseId)
}

/** Identifies a delivery route. */
@JvmInline
value class RouteId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<RouteId>(::RouteId)
}

/** Identifies a purchase order to a vendor. */
@JvmInline
value class PurchaseOrderId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<PurchaseOrderId>(::PurchaseOrderId)
}

/** Identifies one entry in the inventory movement ledger. */
@JvmInline
value class InventoryMovementId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<InventoryMovementId>(::InventoryMovementId)
}

/** Identifies a receipt of goods against a purchase order. */
@JvmInline
value class ReceiptId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<ReceiptId>(::ReceiptId)
}

/** Identifies an accrual for goods received and not yet invoiced. */
@JvmInline
value class AccrualId private constructor(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object : TypedIdFactory<AccrualId>(::AccrualId)
}

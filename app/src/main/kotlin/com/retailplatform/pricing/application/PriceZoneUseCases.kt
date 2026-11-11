package com.retailplatform.pricing.application

import com.retailplatform.platform.security.RequiresRole
import com.retailplatform.platform.security.Roles
import com.retailplatform.pricing.domain.PriceZone
import com.retailplatform.pricing.domain.PriceZoneCode
import com.retailplatform.pricing.domain.PriceZoneCodeAlreadyInUse
import com.retailplatform.pricing.domain.StoreNotKnownToPricing
import com.retailplatform.pricing.domain.StorePriceZone
import com.retailplatform.pricing.domain.UnknownPriceZone
import com.retailplatform.shared.StoreId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Use case: add a price zone. */
@Service
class CreatePriceZone(
    private val zones: PriceZoneRepository,
) {
    @RequiresRole(Roles.PRICING_ANALYST)
    @Transactional
    fun handle(zone: PriceZone) {
        if (zones.exists(zone.code)) throw PriceZoneCodeAlreadyInUse(zone.code)
        zones.add(zone)
    }
}

data class AssignStoreToPriceZoneCommand(
    val storeId: StoreId,
    val zone: PriceZoneCode,
)

/** Use case: move a store to a price zone. */
@Service
class AssignStoreToPriceZone(
    private val zones: PriceZoneRepository,
    private val assignments: StorePriceZoneRepository,
) {
    @RequiresRole(Roles.PRICING_ANALYST)
    @Transactional
    fun handle(command: AssignStoreToPriceZoneCommand) {
        if (!zones.exists(command.zone)) throw UnknownPriceZone(command.zone)
        assignments.findByStore(command.storeId) ?: throw StoreNotKnownToPricing(command.storeId)

        assignments.save(StorePriceZone(command.storeId, command.zone))
    }
}

/** Queries: price zones and the zone of a store. */
@Service
@Transactional(readOnly = true)
class PriceZoneQueries(
    private val zones: PriceZoneRepository,
    private val assignments: StorePriceZoneRepository,
) {
    fun zones(): List<PriceZone> = zones.findAll()

    fun zoneOf(storeId: StoreId): StorePriceZone =
        assignments.findByStore(storeId) ?: throw StoreNotKnownToPricing(storeId)
}

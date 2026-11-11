package com.retailplatform.pricing.domain

import com.retailplatform.shared.BusinessRuleViolation

/** A group of stores that share retail prices. */
@ConsistentCopyVisibility
data class PriceZone private constructor(
    val code: PriceZoneCode,
    val name: String,
) {
    companion object {
        const val MAX_NAME_LENGTH = 60

        /** @throws IllegalArgumentException if [name] is blank or longer than [MAX_NAME_LENGTH] once trimmed. */
        fun of(
            code: PriceZoneCode,
            name: String,
        ): PriceZone {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "A price zone name cannot be blank" }
            require(trimmed.length <= MAX_NAME_LENGTH) { "A price zone name has at most $MAX_NAME_LENGTH characters" }
            return PriceZone(code, trimmed)
        }
    }
}

class PriceZoneCodeAlreadyInUse(
    code: PriceZoneCode,
) : BusinessRuleViolation("pricing.price-zone-code-in-use", "Price zone code $code is already in use")

class UnknownPriceZone(
    code: PriceZoneCode,
) : BusinessRuleViolation("pricing.unknown-price-zone", "Price zone $code does not exist")

class StoreNotKnownToPricing(
    storeId: Any,
) : BusinessRuleViolation("pricing.unknown-store", "Store $storeId has not been opened")

package com.retailplatform.platform.web

import com.retailplatform.shared.Quantity
import com.retailplatform.shared.UnitOfMeasure
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

/** The JSON shape of a quantity, for example `{"amount": 24, "unit": "EA"}`. */
data class QuantityDto(
    @field:NotNull
    val amount: BigDecimal?,
    @field:NotBlank
    val unit: String?,
) {
    /** @throws IllegalArgumentException if the amount has more than three decimal places or the unit is malformed. */
    fun toDomain(): Quantity = Quantity.of(requireNotNull(amount), UnitOfMeasure.of(requireNotNull(unit)))

    companion object {
        fun from(quantity: Quantity) = QuantityDto(quantity.amount, quantity.unit.code)
    }
}

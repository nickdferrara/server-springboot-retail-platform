package com.retailplatform.platform.web

import com.retailplatform.shared.Money
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.util.Currency

/** The JSON shape of an amount of money, for example `{"amount": 12.99, "currency": "USD"}`. */
data class MoneyDto(
    @field:NotNull
    val amount: BigDecimal?,
    @field:NotBlank @field:Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO currency code")
    val currency: String?,
) {
    /** @throws IllegalArgumentException if the amount has more than four decimal places or the currency is unknown. */
    fun toDomain(): Money = Money.of(requireNotNull(amount), Currency.getInstance(requireNotNull(currency)))

    companion object {
        fun from(money: Money) = MoneyDto(money.amount, money.currency.currencyCode)
    }
}

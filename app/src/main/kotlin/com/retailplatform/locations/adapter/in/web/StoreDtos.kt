package com.retailplatform.locations.adapter.`in`.web

import com.retailplatform.locations.application.OpenStoreCommand
import com.retailplatform.locations.domain.Store
import com.retailplatform.locations.domain.StoreName
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.platform.web.mapRequest
import com.retailplatform.shared.BusinessDate
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

data class OpenStoreRequest(
    @field:NotBlank
    @field:Pattern(regexp = "[0-9]{1,6}", message = "must be one to six digits")
    val number: String?,
    @field:NotBlank
    @field:Size(max = StoreName.MAX_LENGTH)
    val name: String?,
    @field:NotBlank
    val timeZone: String?,
    @field:NotNull
    val openedOn: LocalDate?,
) {
    /** Turns the validated request into a command made of domain types. */
    fun toCommand(): OpenStoreCommand =
        mapRequest {
            OpenStoreCommand(
                number = StoreNumber.of(requireNotNull(number)),
                name = StoreName.of(requireNotNull(name)),
                timeZone = zoneOf(requireNotNull(timeZone)),
                openedOn = BusinessDate(requireNotNull(openedOn)),
            )
        }

    private fun zoneOf(id: String): ZoneId =
        try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("Not a time zone: '$id'", e)
        }
}

data class StoreResponse(
    val id: String,
    val number: String,
    val name: String,
    val timeZone: String,
    val openedOn: LocalDate,
) {
    companion object {
        fun from(store: Store): StoreResponse =
            StoreResponse(
                id = store.id.toString(),
                number = store.number.value,
                name = store.name.value,
                timeZone = store.timeZone.id,
                openedOn = store.openedOn.value,
            )
    }
}

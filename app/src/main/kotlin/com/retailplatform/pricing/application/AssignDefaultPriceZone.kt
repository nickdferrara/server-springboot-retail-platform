package com.retailplatform.pricing.application

import com.retailplatform.pricing.domain.StorePriceZone
import com.retailplatform.shared.StoreId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class AssignDefaultPriceZoneCommand(
    val storeId: StoreId,
)

/** Use case: put a new store in the default price zone, unless it already has a zone. */
@Service
class AssignDefaultPriceZone(
    private val assignments: StorePriceZoneRepository,
) {
    @Transactional
    fun handle(command: AssignDefaultPriceZoneCommand) {
        if (assignments.findByStore(command.storeId) == null) {
            assignments.save(StorePriceZone.forNewStore(command.storeId))
        }
    }
}

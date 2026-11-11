package com.retailplatform.inventory.application

import com.retailplatform.inventory.domain.StockedItem
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Use case: remember that a catalog item exists and which unit its stock is counted in. */
@Service
class RegisterStockedItem(
    private val items: StockedItemRepository,
) {
    @Transactional
    fun handle(item: StockedItem) {
        if (items.findById(item.itemId) == null) items.save(item)
    }
}

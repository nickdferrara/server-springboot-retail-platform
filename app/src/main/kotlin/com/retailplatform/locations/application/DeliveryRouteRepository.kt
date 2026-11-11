package com.retailplatform.locations.application

import com.retailplatform.locations.domain.DeliveryRoute
import com.retailplatform.locations.domain.RouteCode
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.RouteId
import com.retailplatform.shared.StoreId

/** Port: where delivery routes are kept. */
interface DeliveryRouteRepository {
    /** Inserts a new route or updates an existing one; a stale version is refused. */
    fun save(route: DeliveryRoute)

    fun findById(id: RouteId): DeliveryRoute?

    fun existsByCode(code: RouteCode): Boolean

    /** Whether any route stops at [storeId]. */
    fun existsStopAt(storeId: StoreId): Boolean

    /** Routes ordered by code. */
    fun findPage(query: PageQuery): Page<DeliveryRoute>
}

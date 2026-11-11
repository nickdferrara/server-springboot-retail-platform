package com.retailplatform.locations.adapter.out.persistence

import com.retailplatform.locations.application.DeliveryRouteRepository
import com.retailplatform.locations.domain.DeliveryRoute
import com.retailplatform.locations.domain.LocationName
import com.retailplatform.locations.domain.OrderCutoff
import com.retailplatform.locations.domain.RouteCode
import com.retailplatform.locations.domain.RouteSchedule
import com.retailplatform.locations.domain.RouteStop
import com.retailplatform.platform.persistence.AuditMetadata
import com.retailplatform.shared.Page
import com.retailplatform.shared.PageQuery
import com.retailplatform.shared.RouteId
import com.retailplatform.shared.StoreId
import com.retailplatform.shared.WarehouseId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.PagingAndSortingRepository
import org.springframework.stereotype.Repository
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Table(schema = "locations", name = "delivery_route")
data class RouteEntity(
    @Id val id: UUID,
    val code: String,
    val name: String,
    val warehouseId: UUID,
    @MappedCollection(idColumn = "route_id", keyColumn = "position") val stops: List<RouteStopEntity>,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

@Table(schema = "locations", name = "route_stop")
data class RouteStopEntity(
    val storeId: UUID,
    val deliveryDay: DayOfWeek,
    val cutoffDaysBefore: Int,
    val cutoffTime: LocalTime,
)

interface RouteJdbcRepository :
    CrudRepository<RouteEntity, UUID>,
    PagingAndSortingRepository<RouteEntity, UUID> {
    fun existsByCode(code: String): Boolean

    @Query("SELECT EXISTS (SELECT 1 FROM locations.route_stop WHERE store_id = :storeId)")
    fun existsStopAt(storeId: UUID): Boolean
}

@Repository
class JdbcDeliveryRouteRepository(
    private val routes: RouteJdbcRepository,
) : DeliveryRouteRepository {
    override fun save(route: DeliveryRoute) {
        val audit = if (route.version == 0) AuditMetadata() else routes.findById(route.id.value).orElseThrow().audit
        routes.save(
            RouteEntity(
                id = route.id.value,
                code = route.code.value,
                name = route.name.value,
                warehouseId = route.warehouseId.value,
                stops =
                    route.schedule.stops.map {
                        RouteStopEntity(it.storeId.value, it.deliveryDay, it.cutoff.daysBefore, it.cutoff.time)
                    },
                version = route.version,
                audit = audit,
            ),
        )
    }

    override fun findById(id: RouteId): DeliveryRoute? = routes.findById(id.value).getOrNull()?.toDomain()

    override fun existsByCode(code: RouteCode): Boolean = routes.existsByCode(code.value)

    override fun existsStopAt(storeId: StoreId): Boolean = routes.existsStopAt(storeId.value)

    override fun findPage(query: PageQuery): Page<DeliveryRoute> {
        val page = routes.findAll(PageRequest.of(query.page, query.size, Sort.by("code")))
        return Page(page.content.map { it.toDomain() }, query.page, query.size, page.totalElements)
    }

    private fun RouteEntity.toDomain(): DeliveryRoute =
        DeliveryRoute.rehydrate(
            id = RouteId.of(id),
            code = RouteCode.of(code),
            name = LocationName.of(name),
            warehouseId = WarehouseId.of(warehouseId),
            schedule =
                RouteSchedule.of(
                    stops.map {
                        RouteStop(
                            StoreId.of(it.storeId),
                            it.deliveryDay,
                            OrderCutoff.of(it.cutoffDaysBefore, it.cutoffTime),
                        )
                    },
                ),
            version = version,
        )
}

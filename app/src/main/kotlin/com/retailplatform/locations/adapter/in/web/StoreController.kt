package com.retailplatform.locations.adapter.`in`.web

import com.retailplatform.locations.application.FindStore
import com.retailplatform.locations.application.OpenStore
import com.retailplatform.shared.StoreId
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/stores")
class StoreController(
    private val openStore: OpenStore,
    private val findStore: FindStore,
) {
    @PostMapping
    fun open(
        @Valid @RequestBody request: OpenStoreRequest,
    ): ResponseEntity<StoreResponse> {
        val id = openStore.handle(request.toCommand())
        return ResponseEntity
            .created(URI.create("/api/v1/stores/$id"))
            .body(StoreResponse.from(findStore.handle(id)))
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ): StoreResponse = StoreResponse.from(findStore.handle(StoreId.parse(id)))
}

package com.retailplatform.locations

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.locations.api.events.StoreOpened
import com.retailplatform.locations.application.FindStore
import com.retailplatform.locations.application.OpenStore
import com.retailplatform.locations.application.OpenStoreCommand
import com.retailplatform.locations.domain.StoreName
import com.retailplatform.locations.domain.StoreNumber
import com.retailplatform.locations.domain.StoreNumberAlreadyInUse
import com.retailplatform.platform.security.Roles
import com.retailplatform.shared.BusinessDate
import com.retailplatform.shared.ResourceNotFound
import com.retailplatform.shared.StoreId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.Scenario
import org.springframework.security.test.context.support.WithMockUser
import java.time.Clock
import java.time.ZoneId

@ApplicationModuleTest
@WithMockUser(authorities = [Roles.MASTER_DATA_EDITOR])
@Import(TestcontainersConfiguration::class)
class LocationsModuleTest(
    @Autowired private val openStore: OpenStore,
    @Autowired private val findStore: FindStore,
    @Autowired private val clock: Clock,
) {
    @Test
    fun `opening a store saves it and publishes StoreOpened`(scenario: Scenario) {
        val command = command(number = "0201", name = "Harbor View")

        scenario
            .stimulate { openStore.handle(command) }
            .andWaitForEventOfType(StoreOpened::class.java)
            .matching { it.storeNumber == "0201" }
            .toArriveAndVerify { event ->
                event.storeName shouldBe "Harbor View"
                event.businessDate shouldBe command.openedOn

                val saved = findStore.handle(event.storeId)
                saved.number shouldBe command.number
                saved.timeZone shouldBe command.timeZone
            }
    }

    @Test
    fun `a store number can be used only once`() {
        openStore.handle(command(number = "0202", name = "First"))

        shouldThrow<StoreNumberAlreadyInUse> { openStore.handle(command(number = "0202", name = "Second")) }
    }

    @Test
    fun `looking up a store that does not exist fails`() {
        shouldThrow<ResourceNotFound> { findStore.handle(StoreId.generate(clock)) }
    }

    private fun command(
        number: String,
        name: String,
    ) = OpenStoreCommand(
        number = StoreNumber.of(number),
        name = StoreName.of(name),
        timeZone = ZoneId.of("America/Chicago"),
        openedOn = BusinessDate.parse("2026-12-01"),
    )
}

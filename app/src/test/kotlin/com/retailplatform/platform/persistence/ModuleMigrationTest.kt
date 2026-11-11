package com.retailplatform.platform.persistence

import com.retailplatform.TestcontainersConfiguration
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ModuleMigrationTest(
    @Autowired private val jdbc: JdbcClient,
) {
    @Test
    fun `each module migrates into its own schema with its own history`() {
        tablesIn("platform") shouldContain "flyway_schema_history"
        tablesIn("public").contains("flyway_schema_history") shouldBe false
    }

    @Test
    fun `the event publication registry lives in the platform schema`() {
        tablesIn("platform") shouldContain "event_publication"
    }

    private fun tablesIn(schema: String): List<String> =
        jdbc
            .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = :schema")
            .param("schema", schema)
            .query(String::class.java)
            .list()
            .filterNotNull()
}

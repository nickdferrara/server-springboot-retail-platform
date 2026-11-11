package com.retailplatform.platform.persistence

import com.retailplatform.TestcontainersConfiguration
import com.retailplatform.shared.UuidV7
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Embedded
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.util.UUID

@Table(schema = "platform", name = "audit_probe")
data class AuditProbe(
    @Id val id: UUID,
    val label: String,
    @Version val version: Int = 0,
    @Embedded.Empty val audit: AuditMetadata = AuditMetadata(),
)

interface AuditProbeRepository : CrudRepository<AuditProbe, UUID>

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AuditingTest(
    @Autowired private val probes: AuditProbeRepository,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val clock: Clock,
) {
    @BeforeEach
    fun createProbeTable() {
        jdbc
            .sql(
                """
                CREATE TABLE IF NOT EXISTS platform.audit_probe (
                    id UUID PRIMARY KEY,
                    label TEXT NOT NULL,
                    version INT NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_by TEXT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_by TEXT NOT NULL
                )
                """.trimIndent(),
            ).update()
    }

    @Test
    fun `saving stamps who and when, and bumps the version`() {
        val created = probes.save(AuditProbe(id = UuidV7.generate(clock), label = "first"))

        created.version shouldBe 1
        created.audit.createdBy shouldBe AuditingConfiguration.SYSTEM_USER
        created.audit.updatedBy shouldBe AuditingConfiguration.SYSTEM_USER
        created.audit.createdAt.shouldNotBeNull()

        val updated = probes.save(created.copy(label = "second"))
        val reloaded = probes.findById(created.id).orElseThrow()

        updated.version shouldBe 2
        reloaded.label shouldBe "second"
        val createdAt = reloaded.audit.createdAt.shouldNotBeNull()
        reloaded.audit.updatedAt.shouldNotBeNull() shouldBeGreaterThanOrEqualTo createdAt
    }

    @Test
    fun `a write based on a stale version is refused`() {
        val created = probes.save(AuditProbe(id = UuidV7.generate(clock), label = "first"))
        probes.save(created.copy(label = "second"))

        org.junit.jupiter.api.assertThrows<OptimisticLockingFailureException> {
            probes.save(created.copy(label = "stale"))
        }
    }
}

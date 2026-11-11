package com.retailplatform.platform.persistence

import org.flywaydb.core.Flyway
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Runs Flyway once per module instead of once for the whole application.
 *
 * Every folder under `db/migration` is named after a module. Its scripts are applied to the database schema of the
 * same name, and that schema keeps its own migration history, so a module's tables can move to another database
 * without untangling a shared history.
 */
@Configuration(proxyBeanMethods = false)
class ModuleMigrationConfiguration {
    @Bean
    fun moduleMigrationStrategy(): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            migratableModules().forEach { module ->
                Flyway
                    .configure()
                    .configuration(flyway.configuration)
                    .schemas(module)
                    .defaultSchema(module)
                    .createSchemas(true)
                    .locations("classpath:$MIGRATION_ROOT/$module")
                    .load()
                    .migrate()
            }
        }

    private fun migratableModules(): List<String> =
        PathMatchingResourcePatternResolver()
            .getResources("classpath*:$MIGRATION_ROOT/*/*.sql")
            .map { script ->
                script.url.path
                    .substringBeforeLast('/')
                    .substringAfterLast('/')
            }.distinct()
            .sorted()

    private companion object {
        const val MIGRATION_ROOT = "db/migration"
    }
}

package com.retailplatform.platform.persistence

import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import java.time.Instant

/**
 * The `created_at`, `created_by`, `updated_at`, and `updated_by` columns every aggregate table carries.
 *
 * Embed it in a persistence entity with `@Embedded.Empty val audit: AuditMetadata = AuditMetadata()`; the values are
 * filled in when the entity is saved.
 */
data class AuditMetadata(
    @CreatedDate val createdAt: Instant? = null,
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val updatedAt: Instant? = null,
    @LastModifiedBy val updatedBy: String? = null,
)

package com.retailplatform.shared

/**
 * A business rule said no: the request was well formed, but the current state does not allow it.
 *
 * Modules declare one subclass per rule. [code] is stable and machine readable (for example
 * `purchasing.approval-by-creator`); clients branch on it, so it must not change once published.
 */
abstract class BusinessRuleViolation(
    val code: String,
    message: String,
) : RuntimeException(message)

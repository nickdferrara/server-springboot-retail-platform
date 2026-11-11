package com.retailplatform.platform.security

/**
 * Lets only users holding at least one of the given roles call a use case.
 *
 * ```
 * @RequiresRole(Roles.BUYER_APPROVER)
 * fun handle(command: ApprovePurchaseOrderCommand)
 * ```
 *
 * On a class it applies to every method; an annotation on a method takes precedence.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresRole(
    vararg val value: String,
)

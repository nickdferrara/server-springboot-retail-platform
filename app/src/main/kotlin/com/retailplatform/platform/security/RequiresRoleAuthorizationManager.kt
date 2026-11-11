package com.retailplatform.platform.security

import org.aopalliance.intercept.MethodInvocation
import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.authorization.AuthorityAuthorizationManager
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.core.Authentication
import java.util.function.Supplier

/** Decides whether the current user holds one of the roles named by [RequiresRole] on the method being called. */
class RequiresRoleAuthorizationManager : AuthorizationManager<MethodInvocation> {
    override fun authorize(
        authentication: Supplier<out Authentication?>,
        invocation: MethodInvocation,
    ): AuthorizationResult {
        val roles = requiredRoles(invocation) ?: return AuthorizationDecision(true)
        return AuthorityAuthorizationManager
            .hasAnyAuthority<MethodInvocation>(*roles)
            .authorize(authentication, invocation)
    }

    private fun requiredRoles(invocation: MethodInvocation): Array<out String>? {
        val targetClass = invocation.`this`?.let(AopUtils::getTargetClass) ?: invocation.method.declaringClass
        val method = AopUtils.getMostSpecificMethod(invocation.method, targetClass)
        val annotation =
            AnnotatedElementUtils.findMergedAnnotation(method, RequiresRole::class.java)
                ?: AnnotatedElementUtils.findMergedAnnotation(targetClass, RequiresRole::class.java)
        return annotation?.value
    }
}

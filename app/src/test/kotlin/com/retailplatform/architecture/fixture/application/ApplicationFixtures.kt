package com.retailplatform.architecture.fixture.application

import com.retailplatform.architecture.fixture.adapter.out.OutboundAdapter
import com.retailplatform.architecture.fixture.domain.WellBehavedDomain
import org.springframework.beans.factory.annotation.Autowired

// Deliberately wrong (and one right) use cases used by ArchitectureRulesTest.

class WellBehavedUseCase {
    fun handle(domain: WellBehavedDomain): WellBehavedDomain = domain
}

class UseCaseCallingAdapter {
    @Autowired
    var adapter: OutboundAdapter? = null
}

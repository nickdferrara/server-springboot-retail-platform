package com.retailplatform.architecture.fixture.adapter.`in`

import com.retailplatform.architecture.fixture.adapter.out.OutboundAdapter

class InboundAdapterCallingOutboundAdapter(
    val adapter: OutboundAdapter,
)

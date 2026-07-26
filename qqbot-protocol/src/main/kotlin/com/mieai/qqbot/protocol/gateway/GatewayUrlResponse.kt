package com.mieai.qqbot.protocol.gateway

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/** Gateway discovery response. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GatewayUrlResponse(val url: String?)

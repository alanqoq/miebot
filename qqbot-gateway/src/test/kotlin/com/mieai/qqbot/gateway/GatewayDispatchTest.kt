package com.mieai.qqbot.gateway

import com.mieai.qqbot.protocol.event.QqEventModels
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GatewayDispatchTest {
    @Test
    fun exposesTypedKnownEventsWithoutChangingTheRawPayload() {
        val raw = """
            {"op":0,"s":7,"t":"FRIEND_ADD","id":"event-1","d":{
              "openid":"user-1","timestamp":1699240328
            }}
        """.trimIndent()
        val dispatch = GatewayDispatch(7L, "FRIEND_ADD", raw)

        val event = requireNotNull(dispatch.decodeKnownEvent()) as QqEventModels.UserLifecycle

        assertThat(event.openId).isEqualTo("user-1")
        assertThat(dispatch.rawPayload).isEqualTo(raw)
        assertThat(dispatch.platformEventId).isEqualTo("event-1")
    }

    @Test
    fun preservesFutureEventsAsOpaqueDispatches() {
        val dispatch = GatewayDispatch(
            8L,
            "FUTURE_EVENT",
            "{\"op\":0,\"s\":8,\"t\":\"FUTURE_EVENT\",\"d\":{}}",
        )

        assertThat(dispatch.decodeKnownEvent()).isNull()
    }
}

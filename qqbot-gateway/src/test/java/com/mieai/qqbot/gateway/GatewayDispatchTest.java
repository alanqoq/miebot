package com.mieai.qqbot.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.protocol.event.QqEventModels;
import org.junit.jupiter.api.Test;

class GatewayDispatchTest {
    @Test
    void exposesTypedKnownEventsWithoutChangingTheRawPayload() {
        String raw = """
                {"op":0,"s":7,"t":"FRIEND_ADD","id":"event-1","d":{
                  "openid":"user-1","timestamp":1699240328
                }}
                """;
        GatewayDispatch dispatch = new GatewayDispatch(7L, "FRIEND_ADD", raw);

        QqEventModels.UserLifecycle event = (QqEventModels.UserLifecycle)
                dispatch.decodeKnownEvent().orElseThrow();

        assertThat(event.openId()).isEqualTo("user-1");
        assertThat(dispatch.rawPayload()).isEqualTo(raw);
        assertThat(dispatch.platformEventId()).isEqualTo("event-1");
    }

    @Test
    void preservesFutureEventsAsOpaqueDispatches() {
        GatewayDispatch dispatch = new GatewayDispatch(
                8L, "FUTURE_EVENT", "{\"op\":0,\"s\":8,\"t\":\"FUTURE_EVENT\",\"d\":{}}");

        assertThat(dispatch.decodeKnownEvent()).isEmpty();
    }
}

package com.mieai.qqbot.plugin.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GroupMemberRoleTest {
    @Test
    fun mapsOfficialValuesAndLeavesUnknownValuesUnclassified() {
        assertThat(GroupMemberRole.fromPlatformValue("member")).isEqualTo(GroupMemberRole.MEMBER)
        assertThat(GroupMemberRole.fromPlatformValue("ADMIN")).isEqualTo(GroupMemberRole.ADMIN)
        assertThat(GroupMemberRole.fromPlatformValue(" owner ")).isEqualTo(GroupMemberRole.OWNER)
        assertThat(GroupMemberRole.fromPlatformValue("future-role")).isNull()
        assertThat(GroupMemberRole.fromPlatformValue(null)).isNull()
    }
}

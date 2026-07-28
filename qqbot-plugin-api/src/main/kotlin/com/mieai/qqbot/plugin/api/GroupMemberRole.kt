package com.mieai.qqbot.plugin.api

/** Stable ordinary-group roles supplied by QQ for a message author. */
enum class GroupMemberRole(val platformValue: String) {
    MEMBER("member"),
    ADMIN("admin"),
    OWNER("owner"),
    ;

    companion object {
        fun fromPlatformValue(value: String?): GroupMemberRole? = when (value?.trim()?.lowercase()) {
            "member" -> MEMBER
            "admin" -> ADMIN
            "owner" -> OWNER
            else -> null
        }
    }
}

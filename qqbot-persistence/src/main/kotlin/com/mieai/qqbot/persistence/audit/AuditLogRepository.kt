package com.mieai.qqbot.persistence.audit

interface AuditLogRepository {
    fun append(log: AuditLog)

    fun query(limit: Int, cursor: String?, actor: String?, action: String?): AuditLogPage
}

package com.mieai.qqbot.admin.database

import jakarta.validation.constraints.Positive

data class ReloadDatabaseRequest(@field:Positive val expectedRevision: Long)

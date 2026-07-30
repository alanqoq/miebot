package com.mieai.qqbot.admin.plugins

import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreatePluginBindingRequest(
    @field:NotBlank @field:Size(max = 128) val pluginId: String,
    @field:NotBlank @field:Size(max = 36) val botId: String,
    @field:Size(max = 65_536) val configJson: String? = null,
    val enabled: Boolean,
    @field:Size(max = 65_536) val configContent: String? = null,
) {
    fun suppliedConfiguration(): String? = configContent ?: configJson

    fun hasConflictingConfigurationFields(): Boolean =
        configContent != null && configJson != null && configContent != configJson
}

data class UpdatePluginBindingRequest(
    @field:PositiveOrZero val expectedRevision: Long,
    val enabled: Boolean,
)

data class PluginBindingResponse(
    val id: UUID,
    val pluginId: String,
    val botId: UUID,
    val enabled: Boolean,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val runtimeState: String,
    val runtimeError: String?,
) {
    companion object {
        fun from(binding: BotPluginBinding) = PluginBindingResponse(
            binding.id,
            binding.pluginId,
            binding.botId.value,
            binding.enabled,
            binding.revision,
            binding.createdAt,
            binding.updatedAt,
            binding.runtimeState.name,
            binding.runtimeError,
        )
    }
}

data class PluginFileEntryResponse(
    val name: String,
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Instant,
    val contentType: String?,
)

data class PluginFileListingResponse(
    val path: String,
    val entries: List<PluginFileEntryResponse>,
)

data class PluginFileContentResponse(
    val path: String,
    val content: String,
    val sha256: String,
    val modifiedAt: Instant,
)

data class CreatePluginFileEntryRequest(
    @field:NotBlank @field:Size(max = 1_024) val path: String,
    val directory: Boolean,
)

data class UpdatePluginFileContentRequest(
    @field:NotBlank @field:Size(max = 1_024) val path: String,
    @field:Size(max = 2_097_152) val content: String,
    @field:Size(max = 64) val expectedSha256: String?,
)

data class PluginFileDownload(
    val path: java.nio.file.Path,
    val fileName: String,
    val contentType: String?,
)

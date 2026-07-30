package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class PluginConfigurationFormat {
    JSON,
    YAML,
}

data class PluginConfigurationDescriptor(
    val format: PluginConfigurationFormat,
    val fileName: String,
) {
    companion object {
        private val BINDING_FILES = mapOf(
            "config.json" to PluginConfigurationFormat.JSON,
            "config.yml" to PluginConfigurationFormat.YAML,
            "config.yaml" to PluginConfigurationFormat.YAML,
        )

        fun fromDefaultResource(resourcePath: String): PluginConfigurationDescriptor {
            val lower = resourcePath.lowercase(Locale.ROOT)
            return when {
                lower.endsWith(".json") -> PluginConfigurationDescriptor(PluginConfigurationFormat.JSON, "config.json")
                lower.endsWith(".yml") -> PluginConfigurationDescriptor(PluginConfigurationFormat.YAML, "config.yml")
                lower.endsWith(".yaml") -> PluginConfigurationDescriptor(PluginConfigurationFormat.YAML, "config.yaml")
                else -> throw IllegalArgumentException(
                    "Plugin default configuration must use a .json, .yml, or .yaml extension",
                )
            }
        }

        fun fromBindingFileName(fileName: String): PluginConfigurationDescriptor {
            val normalized = fileName.lowercase(Locale.ROOT)
            val format = BINDING_FILES[normalized]
                ?: throw IllegalArgumentException("Unsupported plugin configuration file name: $fileName")
            return PluginConfigurationDescriptor(format, normalized)
        }

        fun isBindingFileName(fileName: String): Boolean = BINDING_FILES.containsKey(fileName.lowercase(Locale.ROOT))

        fun bindingFileNames(): Set<String> = BINDING_FILES.keys
    }
}

data class PluginConfigurationDocument(
    val content: String,
    val fileName: String,
) {
    val format: PluginConfigurationFormat = PluginConfigurationDescriptor.fromBindingFileName(fileName).format

    init {
        require(content.isNotBlank()) { "content must not be blank" }
    }
}

/** Parses configuration only for host validation; the original text is always passed to plugins unchanged. */
class PluginConfigurationCodec {
    private val json = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val yaml = ObjectMapper(YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    fun parse(content: String, format: PluginConfigurationFormat): JsonNode {
        val mapper = if (format == PluginConfigurationFormat.JSON) json else yaml
        mapper.createParser(content).use { parser ->
            val value: JsonNode? = mapper.readTree(parser)
            require(value != null) { "Plugin configuration is empty" }
            require(parser.nextToken() == null) { "Plugin configuration must contain exactly one document" }
            return value
        }
    }

    fun parseObject(content: String, format: PluginConfigurationFormat): JsonNode {
        val value = parse(content, format)
        require(value.isObject) { "Plugin configuration must be an object" }
        return value
    }

    fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

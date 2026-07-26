package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.databind.JsonNode
import java.util.ArrayList
import java.util.HashSet
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Deterministic JSON Schema subset for operator-facing plugin configuration validation. */
class PluginConfigurationValidator {
    fun validate(schema: JsonNode, value: JsonNode): List<String> {
        val errors = ArrayList<String>()
        validateNode(schema, value, "$", errors)
        return errors.toList()
    }

    private fun validateNode(schema: JsonNode, value: JsonNode, path: String, errors: MutableList<String>) {
        if (errors.size >= MAX_ERRORS) return
        val type = if (schema.path("type").isTextual) schema.path("type").textValue() else null
        if (type != null && !matchesType(type, value)) {
            errors.add("$path must be $type")
            return
        }
        val constant = schema.get("const")
        if (constant != null && constant != value) errors.add("$path must equal the declared constant")
        val enumeration = schema.get("enum")
        if (enumeration != null && enumeration.isArray) {
            var match = false
            for (candidate in enumeration) {
                if (candidate == value) match = true
            }
            if (!match) errors.add("$path is not one of the allowed values")
        }
        if (value.isObject) validateObject(schema, value, path, errors)
        if (value.isArray) validateArray(schema, value, path, errors)
        if (value.isTextual) validateString(schema, value.textValue(), path, errors)
        if (value.isNumber) validateNumber(schema, value, path, errors)
    }

    private fun validateObject(schema: JsonNode, value: JsonNode, path: String, errors: MutableList<String>) {
        val required = schema.get("required")
        if (required != null && required.isArray) {
            for (name in required) {
                if (name.isTextual && !value.has(name.textValue())) {
                    errors.add("$path.${name.textValue()} is required")
                }
            }
        }
        val properties = schema.path("properties")
        val declared = HashSet<String>()
        if (properties.isObject) {
            properties.fieldNames().forEachRemaining(declared::add)
            for (name in declared) {
                if (value.has(name)) validateNode(properties.get(name), value.get(name), "$path.$name", errors)
            }
        }
        if (schema.path("additionalProperties").isBoolean &&
            !schema.path("additionalProperties").booleanValue()
        ) {
            val names = value.fieldNames()
            while (names.hasNext()) {
                val name = names.next()
                if (!declared.contains(name)) errors.add("$path.$name is not allowed")
            }
        }
    }

    private fun validateArray(schema: JsonNode, value: JsonNode, path: String, errors: MutableList<String>) {
        if (schema.path("minItems").canConvertToInt() && value.size() < schema.path("minItems").intValue()) {
            errors.add("$path has too few items")
        }
        if (schema.path("maxItems").canConvertToInt() && value.size() > schema.path("maxItems").intValue()) {
            errors.add("$path has too many items")
        }
        val itemSchema = schema.get("items")
        if (itemSchema != null && itemSchema.isObject) {
            for (index in 0 until value.size()) {
                validateNode(itemSchema, value.get(index), "$path[$index]", errors)
            }
        }
    }

    private fun validateString(schema: JsonNode, value: String, path: String, errors: MutableList<String>) {
        val characters = value.codePointCount(0, value.length)
        if (schema.path("minLength").canConvertToInt() && characters < schema.path("minLength").intValue()) {
            errors.add("$path is too short")
        }
        if (schema.path("maxLength").canConvertToInt() && characters > schema.path("maxLength").intValue()) {
            errors.add("$path is too long")
        }
        if (schema.path("pattern").isTextual) {
            try {
                if (!Pattern.compile(schema.path("pattern").textValue()).matcher(value).find()) {
                    errors.add("$path does not match the required pattern")
                }
            } catch (exception: PatternSyntaxException) {
                throw IllegalArgumentException("Plugin configuration schema contains an invalid pattern", exception)
            }
        }
    }

    private fun validateNumber(schema: JsonNode, value: JsonNode, path: String, errors: MutableList<String>) {
        if (schema.path("minimum").isNumber &&
            value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0
        ) {
            errors.add("$path is below the minimum")
        }
        if (schema.path("maximum").isNumber &&
            value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0
        ) {
            errors.add("$path is above the maximum")
        }
    }

    private fun matchesType(type: String, value: JsonNode): Boolean = when (type) {
        "object" -> value.isObject
        "array" -> value.isArray
        "string" -> value.isTextual
        "integer" -> value.isIntegralNumber
        "number" -> value.isNumber
        "boolean" -> value.isBoolean
        "null" -> value.isNull
        else -> throw IllegalArgumentException("Unsupported plugin configuration schema type: $type")
    }

    companion object {
        private const val MAX_ERRORS = 32
    }
}

package com.mieai.qqbot.plugin.host;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Deterministic JSON Schema subset for operator-facing plugin configuration validation. */
final class PluginConfigurationValidator {
    private static final int MAX_ERRORS = 32;

    List<String> validate(JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        validateNode(schema, value, "$", errors);
        return List.copyOf(errors);
    }

    private void validateNode(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (errors.size() >= MAX_ERRORS) return;
        String type = schema.path("type").isTextual() ? schema.path("type").textValue() : null;
        if (type != null && !matchesType(type, value)) {
            errors.add(path + " must be " + type);
            return;
        }
        JsonNode constant = schema.get("const");
        if (constant != null && !constant.equals(value)) errors.add(path + " must equal the declared constant");
        JsonNode enumeration = schema.get("enum");
        if (enumeration != null && enumeration.isArray()) {
            boolean match = false;
            for (JsonNode candidate : enumeration) if (candidate.equals(value)) match = true;
            if (!match) errors.add(path + " is not one of the allowed values");
        }
        if (value.isObject()) validateObject(schema, value, path, errors);
        if (value.isArray()) validateArray(schema, value, path, errors);
        if (value.isTextual()) validateString(schema, value.textValue(), path, errors);
        if (value.isNumber()) validateNumber(schema, value, path, errors);
    }

    private void validateObject(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode required = schema.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                if (name.isTextual() && !value.has(name.textValue())) errors.add(path + "." + name.textValue() + " is required");
            }
        }
        JsonNode properties = schema.path("properties");
        Set<String> declared = new HashSet<>();
        if (properties.isObject()) {
            properties.fieldNames().forEachRemaining(declared::add);
            for (String name : declared) {
                if (value.has(name)) validateNode(properties.get(name), value.get(name), path + "." + name, errors);
            }
        }
        if (schema.path("additionalProperties").isBoolean()
                && !schema.path("additionalProperties").booleanValue()) {
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!declared.contains(name)) errors.add(path + "." + name + " is not allowed");
            }
        }
    }

    private void validateArray(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema.path("minItems").canConvertToInt() && value.size() < schema.path("minItems").intValue())
            errors.add(path + " has too few items");
        if (schema.path("maxItems").canConvertToInt() && value.size() > schema.path("maxItems").intValue())
            errors.add(path + " has too many items");
        JsonNode itemSchema = schema.get("items");
        if (itemSchema != null && itemSchema.isObject()) {
            for (int index = 0; index < value.size(); index++) validateNode(itemSchema, value.get(index), path + "[" + index + "]", errors);
        }
    }

    private void validateString(JsonNode schema, String value, String path, List<String> errors) {
        int characters = value.codePointCount(0, value.length());
        if (schema.path("minLength").canConvertToInt() && characters < schema.path("minLength").intValue())
            errors.add(path + " is too short");
        if (schema.path("maxLength").canConvertToInt() && characters > schema.path("maxLength").intValue())
            errors.add(path + " is too long");
        if (schema.path("pattern").isTextual()) {
            try {
                if (!Pattern.compile(schema.path("pattern").textValue()).matcher(value).find())
                    errors.add(path + " does not match the required pattern");
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException("Plugin configuration schema contains an invalid pattern", exception);
            }
        }
    }

    private void validateNumber(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema.path("minimum").isNumber() && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0)
            errors.add(path + " is below the minimum");
        if (schema.path("maximum").isNumber() && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0)
            errors.add(path + " is above the maximum");
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> throw new IllegalArgumentException("Unsupported plugin configuration schema type: " + type);
        };
    }
}

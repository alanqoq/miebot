package com.mieai.qqbot.onebot11.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** OneBot array/CQ-code conversion without accepting implementation-specific segments. */
public final class OneBotMessageCodec {
    private static final int MAX_SEGMENTS = 64;
    private static final int MAX_MESSAGE_CHARACTERS = 32_768;

    private final ObjectMapper objectMapper;

    public OneBotMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public List<OneBotSegment> parse(JsonNode message, boolean autoEscape) {
        Objects.requireNonNull(message, "message must not be null");
        List<OneBotSegment> result;
        if (message.isTextual()) {
            String value = message.textValue();
            requireBounded(value);
            result = autoEscape ? List.of(text(value)) : parseCq(value);
        } else if (message.isArray()) {
            result = parseArray(message);
        } else {
            throw new IllegalArgumentException("message must be a string or segment array");
        }
        if (result.isEmpty() || result.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("message segment count is invalid");
        }
        return List.copyOf(result);
    }

    public ArrayNode toArray(List<OneBotSegment> segments) {
        ArrayNode array = objectMapper.createArrayNode();
        for (OneBotSegment segment : segments) {
            ObjectNode value = array.addObject();
            value.put("type", segment.type());
            ObjectNode data = value.putObject("data");
            segment.data().forEach(data::put);
        }
        return array;
    }

    public String toRawMessage(List<OneBotSegment> segments) {
        StringBuilder result = new StringBuilder();
        for (OneBotSegment segment : segments) {
            if (segment.type().equals("text")) {
                result.append(escapeText(segment.data().getOrDefault("text", "")));
                continue;
            }
            result.append("[CQ:").append(segment.type());
            segment.data().forEach((key, value) -> result.append(',').append(key).append('=')
                    .append(escapeParameter(value)));
            result.append(']');
        }
        return result.toString();
    }

    public static OneBotSegment text(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new OneBotSegment("text", Map.of("text", value));
    }

    private List<OneBotSegment> parseArray(JsonNode message) {
        if (message.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("message has too many segments");
        }
        List<OneBotSegment> result = new ArrayList<>();
        int characters = 0;
        for (JsonNode value : message) {
            if (!value.isObject() || !value.path("type").isTextual()) {
                throw new IllegalArgumentException("message segment is invalid");
            }
            JsonNode dataNode = value.path("data");
            if (!dataNode.isObject()) {
                throw new IllegalArgumentException("message segment data must be an object");
            }
            Map<String, String> data = new LinkedHashMap<>();
            for (var field : dataNode.properties()) {
                if (!field.getKey().matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")
                        || !field.getValue().isValueNode()) {
                    throw new IllegalArgumentException("message segment data is invalid");
                }
                String text = field.getValue().asText();
                characters += text.length();
                data.put(field.getKey(), text);
            }
            result.add(new OneBotSegment(value.path("type").textValue(), data));
        }
        if (characters > MAX_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException("message is too long");
        }
        return result;
    }

    private List<OneBotSegment> parseCq(String value) {
        List<OneBotSegment> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < value.length()) {
            int start = value.indexOf("[CQ:", cursor);
            if (start < 0) {
                appendText(result, decode(value.substring(cursor)));
                break;
            }
            if (start > cursor) {
                appendText(result, decode(value.substring(cursor, start)));
            }
            int end = value.indexOf(']', start + 4);
            if (end < 0) {
                appendText(result, decode(value.substring(start)));
                break;
            }
            String body = value.substring(start + 4, end);
            String[] parts = body.split(",", -1);
            if (parts.length == 0 || !parts[0].matches("[a-z][a-z0-9_]{0,63}")) {
                appendText(result, decode(value.substring(start, end + 1)));
                cursor = end + 1;
                continue;
            }
            Map<String, String> data = new LinkedHashMap<>();
            for (int index = 1; index < parts.length; index++) {
                int equals = parts[index].indexOf('=');
                if (equals < 1) {
                    throw new IllegalArgumentException("CQ code parameter is invalid");
                }
                String key = parts[index].substring(0, equals);
                if (!key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}")) {
                    throw new IllegalArgumentException("CQ code parameter name is invalid");
                }
                data.put(key, decode(parts[index].substring(equals + 1)));
            }
            result.add(new OneBotSegment(parts[0], data));
            cursor = end + 1;
            if (result.size() > MAX_SEGMENTS) {
                throw new IllegalArgumentException("message has too many segments");
            }
        }
        if (value.isEmpty()) {
            result.add(text(""));
        }
        return result;
    }

    private static void appendText(List<OneBotSegment> target, String value) {
        if (!value.isEmpty()) {
            target.add(text(value));
        }
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("[", "&#91;").replace("]", "&#93;");
    }

    private static String escapeParameter(String value) {
        return escapeText(value).replace(",", "&#44;");
    }

    private static String decode(String value) {
        return value.replace("&#44;", ",").replace("&#91;", "[")
                .replace("&#93;", "]").replace("&amp;", "&");
    }

    private static void requireBounded(String value) {
        Objects.requireNonNull(value, "message must not be null");
        if (value.length() > MAX_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException("message is too long");
        }
    }
}

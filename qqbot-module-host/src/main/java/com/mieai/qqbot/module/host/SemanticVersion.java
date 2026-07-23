package com.mieai.qqbot.module.host;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

record SemanticVersion(long major, long minor, long patch) implements Comparable<SemanticVersion> {
    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?$");

    static SemanticVersion parse(String value) {
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + value);
        }
        try {
            return new SemanticVersion(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Semantic version component is too large: " + value, exception);
        }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Long.compare(major, other.major);
        if (result == 0) result = Long.compare(minor, other.minor);
        if (result == 0) result = Long.compare(patch, other.patch);
        return result;
    }
}

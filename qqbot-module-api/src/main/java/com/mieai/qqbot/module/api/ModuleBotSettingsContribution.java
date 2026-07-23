package com.mieai.qqbot.module.api;

/** A Web Component contributed to the settings area of each existing bot. */
public record ModuleBotSettingsContribution(
        String id,
        String label,
        int order,
        String assetPath,
        String customElement) {

    public ModuleBotSettingsContribution {
        id = ModuleValidation.requireId(id, "id");
        label = ModuleValidation.requireText(label, "label", 64);
        if (order < 0 || order > 100_000) {
            throw new IllegalArgumentException("order is invalid");
        }
        assetPath = requireAssetPath(assetPath);
        customElement = requireCustomElement(customElement);
    }

    private static String requireAssetPath(String value) {
        String path = ModuleValidation.requireText(value, "assetPath", 240);
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException("assetPath is invalid");
        }
        return path;
    }

    private static String requireCustomElement(String value) {
        String element = ModuleValidation.requireText(value, "customElement", 128);
        if (!element.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)+")) {
            throw new IllegalArgumentException("customElement is invalid");
        }
        return element;
    }
}

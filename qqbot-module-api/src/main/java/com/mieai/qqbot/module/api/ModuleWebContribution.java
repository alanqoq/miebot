package com.mieai.qqbot.module.api;

/** A navigation route and renderer contributed to the administration Web shell. */
public record ModuleWebContribution(
        String id,
        String label,
        String route,
        String icon,
        int order,
        String componentKey,
        String assetPath,
        String customElement) {

    public ModuleWebContribution {
        id = ModuleValidation.requireId(id, "id");
        label = ModuleValidation.requireText(label, "label", 64);
        route = requireRoute(route);
        icon = ModuleValidation.requireId(icon, "icon");
        if (order < 0 || order > 100_000) {
            throw new IllegalArgumentException("order is invalid");
        }
        boolean builtIn = componentKey != null && !componentKey.isBlank();
        boolean external = assetPath != null && !assetPath.isBlank()
                && customElement != null && !customElement.isBlank();
        if (builtIn == external) {
            throw new IllegalArgumentException(
                    "a Web contribution must declare exactly one renderer");
        }
        if (builtIn) {
            componentKey = ModuleValidation.requireId(componentKey, "componentKey");
            assetPath = null;
            customElement = null;
        } else {
            componentKey = null;
            assetPath = requireAssetPath(assetPath);
            customElement = requireCustomElement(customElement);
        }
    }

    public static ModuleWebContribution builtIn(
            String id, String label, String route, String icon, int order, String componentKey) {
        return new ModuleWebContribution(id, label, route, icon, order, componentKey, null, null);
    }

    public static ModuleWebContribution webComponent(
            String id, String label, String route, String icon, int order,
            String assetPath, String customElement) {
        return new ModuleWebContribution(id, label, route, icon, order, null, assetPath, customElement);
    }

    private static String requireRoute(String value) {
        String route = ModuleValidation.requireText(value, "route", 160);
        if (!route.startsWith("/") || route.length() == 1 || route.contains("..")
                || route.contains("?") || route.contains("#") || route.contains("\\")) {
            throw new IllegalArgumentException("route is invalid");
        }
        return route;
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

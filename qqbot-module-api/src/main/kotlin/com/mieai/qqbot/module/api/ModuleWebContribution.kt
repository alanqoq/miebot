package com.mieai.qqbot.module.api


/** A navigation route and renderer contributed to the administration Web shell. */
data class ModuleWebContribution(
    val id: String,
    val label: String,
    val route: String,
    val icon: String,
    val order: Int,
    val componentKey: String?,
    val assetPath: String?,
    val customElement: String?,
) {
    init {
        ModuleValidation.requireId(id, "id")
        ModuleValidation.requireText(label, "label", 64)
        requireRoute(route)
        ModuleValidation.requireId(icon, "icon")
        requireOrder(order)
        validateRenderer(componentKey, assetPath, customElement)
    }

    companion object {
        fun builtIn(
            id: String,
            label: String,
            route: String,
            icon: String,
            order: Int,
            componentKey: String,
        ): ModuleWebContribution = ModuleWebContribution(id, label, route, icon, order, componentKey, null, null)

        fun webComponent(
            id: String,
            label: String,
            route: String,
            icon: String,
            order: Int,
            assetPath: String,
            customElement: String,
        ): ModuleWebContribution = ModuleWebContribution(id, label, route, icon, order, null, assetPath, customElement)

        private fun requireRoute(value: String): String {
            val route = ModuleValidation.requireText(value, "route", 160)
            require(route.startsWith("/") && route.length > 1 && !route.contains("..") &&
                !route.contains("?") && !route.contains("#") && !route.contains("\\")) {
                "route is invalid"
            }
            return route
        }

        private fun requireOrder(value: Int): Int {
            require(value in 0..100_000) { "order is invalid" }
            return value
        }

        private fun validateRenderer(
            componentKey: String?,
            assetPath: String?,
            customElement: String?,
        ) {
            require(componentKey == null || componentKey.isNotBlank()) { "componentKey must not be blank" }
            require(assetPath == null || assetPath.isNotBlank()) { "assetPath must not be blank" }
            require(customElement == null || customElement.isNotBlank()) { "customElement must not be blank" }
            val builtIn = !componentKey.isNullOrBlank()
            val external = !assetPath.isNullOrBlank() && !customElement.isNullOrBlank()
            require(builtIn != external) { "a Web contribution must declare exactly one renderer" }
            if (builtIn) {
                ModuleValidation.requireId(componentKey, "componentKey")
            } else {
                requireAssetPath(assetPath)
                requireCustomElement(customElement)
            }
        }

        private fun requireAssetPath(value: String?): String {
            val path = ModuleValidation.requireText(value, "assetPath", 240)
            require(!path.startsWith("/") && !path.contains("..") && !path.contains("\\")) {
                "assetPath is invalid"
            }
            return path
        }

        private fun requireCustomElement(value: String?): String {
            val element = ModuleValidation.requireText(value, "customElement", 128)
            require(element.matches(Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)+"))) {
                "customElement is invalid"
            }
            return element
        }
    }

}

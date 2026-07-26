package com.mieai.qqbot.module.api


/** A Web Component contributed to the settings area of each existing bot. */
data class ModuleBotSettingsContribution(
    val id: String,
    val label: String,
    val order: Int,
    val assetPath: String,
    val customElement: String,
) {
    init {
        ModuleValidation.requireId(id, "id")
        ModuleValidation.requireText(label, "label", 64)
        requireOrder(order)
        requireAssetPath(assetPath)
        requireCustomElement(customElement)
    }

    companion object {
        private fun requireOrder(value: Int): Int {
            require(value in 0..100_000) { "order is invalid" }
            return value
        }

        private fun requireAssetPath(value: String): String {
            val path = ModuleValidation.requireText(value, "assetPath", 240)
            require(!path.startsWith("/") && !path.contains("..") && !path.contains("\\")) {
                "assetPath is invalid"
            }
            return path
        }

        private fun requireCustomElement(value: String): String {
            val element = ModuleValidation.requireText(value, "customElement", 128)
            require(element.matches(Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)+"))) {
                "customElement is invalid"
            }
            return element
        }
    }
}

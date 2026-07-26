package com.mieai.qqbot.modules.runtime


/** Supplies robot plugin artifact hashes for multi-instance lease admission. */
fun interface RobotPluginArtifactHashes {
    fun current(): Map<String, String>

    companion object {
        fun empty(): RobotPluginArtifactHashes = RobotPluginArtifactHashes { emptyMap() }
    }
}

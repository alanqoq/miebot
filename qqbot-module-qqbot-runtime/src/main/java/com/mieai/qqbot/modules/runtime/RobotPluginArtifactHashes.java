package com.mieai.qqbot.modules.runtime;

import java.util.Map;

/** Supplies robot plugin artifact hashes for multi-instance lease admission. */
@FunctionalInterface
public interface RobotPluginArtifactHashes {
    Map<String, String> current();

    static RobotPluginArtifactHashes empty() {
        return Map::of;
    }
}

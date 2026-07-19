package com.mieai.qqbot.persistence.plugin;

import java.util.List;
import java.util.Optional;

public interface PluginArtifactRepository {
    void upsert(PluginArtifact artifact);
    Optional<PluginArtifact> findById(String pluginId);
    List<PluginArtifact> findAll();
}

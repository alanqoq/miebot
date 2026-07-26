package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.api.ModuleDescriptor
import java.nio.file.Path

data class ModuleArtifact(
    val path: Path,
    val descriptor: ModuleDescriptor,
    val sha256: String,
) {
    init {
        require(path.isAbsolute && path == path.normalize()) { "path must be absolute and normalized" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 is invalid" }
    }

    companion object {
        fun from(path: Path, descriptor: ModuleDescriptor, sha256: String): ModuleArtifact =
            ModuleArtifact(path.toAbsolutePath().normalize(), descriptor, sha256)
    }
}

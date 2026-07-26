package com.mieai.qqbot.app.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class OnboardingStateStore(
    file: Path,
    private val objectMapper: ObjectMapper,
) {
    val file: Path = file
        .toAbsolutePath()
        .normalize()
    private val writer: ObjectWriter = objectMapper.writerWithDefaultPrettyPrinter()

    fun load(): OnboardingFileState? {
        if (!Files.exists(file)) return null
        return try {
            objectMapper.readValue(file.toFile(), OnboardingFileState::class.java)
        } catch (error: IOException) {
            throw IllegalStateException("Unable to read onboarding state file", error)
        } catch (error: RuntimeException) {
            throw IllegalStateException("Unable to read onboarding state file", error)
        }
    }

    fun save(state: OnboardingFileState) {
        val parent = file.parent
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            temporary = Files.createTempFile(parent, file.fileName.toString(), ".pending")
            writer.writeValue(temporary.toFile(), state)
            moveAtomically(temporary, file)
            temporary = null
        } catch (error: IOException) {
            throw IllegalStateException("Unable to save onboarding state file", error)
        } finally {
            temporary?.let { pending ->
                try {
                    Files.deleteIfExists(pending)
                } catch (_: IOException) {
                    // A stale pending file does not affect the committed state.
                }
            }
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

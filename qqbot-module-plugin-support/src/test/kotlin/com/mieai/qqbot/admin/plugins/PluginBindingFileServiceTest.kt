package com.mieai.qqbot.admin.plugins

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PluginBindingFileServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val bindings = mock(BotPluginBindingRepository::class.java)
    private val host = mock(Pf4jPluginHost::class.java)
    private val runtime = mock(PluginRuntimeService::class.java)
    private val binding = BotPluginBinding(
        UUID.fromString("770e8400-e29b-41d4-a716-446655440001"),
        "echo",
        BotId.parse("550e8400-e29b-41d4-a716-446655440001"),
        true,
        0L,
        Instant.parse("2026-07-23T00:00:00Z"),
        Instant.parse("2026-07-23T00:00:00Z"),
    )
    private lateinit var dataRoot: Path
    private lateinit var root: Path
    private lateinit var service: PluginBindingFileService

    @BeforeEach
    fun setUp() {
        dataRoot = temporaryDirectory.resolve("data")
        root = dataRoot.resolve("bot/echo")
        val touched = BotPluginBinding(
            binding.id(),
            binding.pluginId(),
            binding.botId(),
            true,
            1L,
            binding.createdAt(),
            binding.updatedAt().plusSeconds(1),
        )
        `when`(bindings.findById(binding.id())).thenReturn(Optional.of(binding))
        `when`(bindings.touch(org.mockito.ArgumentMatchers.eq(binding.id()), anyLong(), any(Instant::class.java)))
            .thenReturn(touched)
        `when`(host.bindingDataDirectory(any(BotPluginBinding::class.java))).thenReturn(root)
        `when`(host.pluginDataRoot()).thenReturn(dataRoot)
        `when`(host.configurationFile(any(BotPluginBinding::class.java))).thenReturn(root.resolve("config.json"))
        `when`(host.defaultConfiguration("echo")).thenReturn("{}")
        `when`(host.validateConfiguration(org.mockito.ArgumentMatchers.eq("echo"), any(String::class.java)))
            .thenReturn(emptyList())
        service = PluginBindingFileService(bindings, host, runtime, ObjectMapper())
    }

    @Test
    fun `initializes isolated configuration and manages json files`() {
        service.initialize(binding, "{\"enabled\":true}")

        assertThat(root.resolve("config.json")).hasContent("{\"enabled\":true}")
        service.createEntry(binding.id(), CreatePluginFileEntryRequest("notes.json", false))
        service.saveContent(
            binding.id(),
            UpdatePluginFileContentRequest("notes.json", "{\"value\":1}", null),
        )

        val listing = service.list(binding.id(), "")
        assertThat(listing.entries.map { it.name }).containsExactly("config.json", "notes.json")
        assertThat(service.content(binding.id(), "notes.json").content).isEqualTo("{\"value\":1}")
        verify(runtime, org.mockito.Mockito.atLeastOnce()).beforeBindingMutation(binding.id())
        verify(runtime, org.mockito.Mockito.atLeastOnce()).bindingMutationCompleted(binding.id())
    }

    @Test
    fun `rejects traversal and invalid binding configuration`() {
        service.initialize(binding, "{}")

        val traversal = assertThrows<PluginAdministrationException> {
            service.list(binding.id(), "../other")
        }
        assertThat(traversal.code()).isEqualTo("PLUGIN_FILE_PATH_INVALID")
        val invalidConfiguration = assertThrows<PluginAdministrationException> {
            service.saveContent(
                binding.id(),
                UpdatePluginFileContentRequest("config.json", "[]", null),
            )
        }
        assertThat(invalidConfiguration.code()).isEqualTo("INVALID_PLUGIN_CONFIG")
    }

    @Test
    fun `rejects creating the root configuration as a directory`() {
        service.initialize(binding, "{}")
        Files.delete(root.resolve("config.json"))

        val failure = assertThrows<PluginAdministrationException> {
            service.createEntry(binding.id(), CreatePluginFileEntryRequest("config.json", true))
        }

        assertThat(failure.code()).isEqualTo("PLUGIN_CONFIG_MUST_BE_FILE")
        assertThat(root.resolve("config.json")).doesNotExist()
    }

    @Test
    fun `creates defaults for bindings discovered at startup`() {
        `when`(bindings.findAll()).thenReturn(listOf(binding))

        service.initializeMissingBindings()

        assertThat(Files.readString(root.resolve("config.json"))).isEqualTo("{}")
    }

    @Test
    fun `rejects a symbolic link used as the bot directory`() {
        val outside = temporaryDirectory.resolve("outside")
        Files.createDirectories(dataRoot)
        Files.createDirectories(outside)
        val linkCreated = runCatching {
            Files.createSymbolicLink(root.parent, outside)
            true
        }.getOrDefault(false)
        assumeTrue(linkCreated, "Symbolic links are not available to the test process")

        val failure = assertThrows<PluginAdministrationException> {
            service.list(binding.id(), "")
        }

        assertThat(failure.code()).isEqualTo("PLUGIN_DATA_DIRECTORY_INVALID")
        assertThat(outside.resolve("echo")).doesNotExist()
    }

    @Test
    fun `revision conflict leaves existing file unchanged`() {
        Files.createDirectories(root)
        val target = root.resolve("notes.txt")
        Files.writeString(target, "before")
        `when`(bindings.touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            org.mockito.ArgumentMatchers.eq(binding.revision()),
            any(Instant::class.java),
        )).thenThrow(com.mieai.qqbot.persistence.plugin.PluginBindingOptimisticLockException(binding.id(), binding.revision()))

        val failure = assertThrows<PluginAdministrationException> {
            service.saveContent(binding.id(), UpdatePluginFileContentRequest("notes.txt", "after", null))
        }

        assertThat(failure.code()).isEqualTo("REVISION_CONFLICT")
        assertThat(target).hasContent("before")
        verify(host, never()).invalidate(binding.id())
    }

    @Test
    fun `rejects multibyte text and json over two MiB without changing files or revision`() {
        Files.createDirectories(root)
        val textFile = root.resolve("notes.txt")
        val jsonFile = root.resolve("notes.json")
        Files.writeString(textFile, "text-before")
        Files.writeString(jsonFile, "{\"value\":\"json-before\"}")
        val multibyteText = "界".repeat(2_097_152 / 3 + 1)
        val multibyteJson = "{\"value\":\"${"界".repeat(2_097_152 / 3)}\"}"

        val textFailure = assertThrows<PluginAdministrationException> {
            service.saveContent(binding.id(), UpdatePluginFileContentRequest("notes.txt", multibyteText, null))
        }
        val jsonFailure = assertThrows<PluginAdministrationException> {
            service.saveContent(binding.id(), UpdatePluginFileContentRequest("notes.json", multibyteJson, null))
        }

        assertThat(textFailure.code()).isEqualTo("PLUGIN_FILE_CONTENT_TOO_LARGE")
        assertThat(jsonFailure.code()).isEqualTo("PLUGIN_FILE_CONTENT_TOO_LARGE")
        assertThat(textFile).hasContent("text-before")
        assertThat(jsonFile).hasContent("{\"value\":\"json-before\"}")
        verify(bindings, never()).touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            anyLong(),
            any(Instant::class.java),
        )
        verify(host, never()).invalidate(binding.id())
    }

    @Test
    fun `keeps config json at 64 KiB for multibyte content without changing file or revision`() {
        service.initialize(binding, "{\"value\":\"before\"}")
        val oversizedConfiguration = "{\"value\":\"${"界".repeat(65_536 / 3)}\"}"

        val failure = assertThrows<PluginAdministrationException> {
            service.saveContent(
                binding.id(),
                UpdatePluginFileContentRequest("config.json", oversizedConfiguration, null),
            )
        }

        assertThat(failure.code()).isEqualTo("PLUGIN_CONFIG_TOO_LARGE")
        assertThat(root.resolve("config.json")).hasContent("{\"value\":\"before\"}")
        verify(bindings, never()).touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            anyLong(),
            any(Instant::class.java),
        )
        verify(host, never()).invalidate(binding.id())
    }

    @Test
    fun `upload creates new files but requires explicit overwrite confirmation`() {
        service.initialize(binding, "{}")
        val target = root.resolve("asset.txt")

        service.upload(
            binding.id(),
            "",
            MockMultipartFile("file", "asset.txt", "text/plain", "first".toByteArray()),
        )
        val duplicate = assertThrows<PluginAdministrationException> {
            service.upload(
                binding.id(),
                "",
                MockMultipartFile("file", "asset.txt", "text/plain", "second".toByteArray()),
            )
        }

        assertThat(duplicate.code()).isEqualTo("PLUGIN_FILE_EXISTS")
        assertThat(target).hasContent("first")
        verify(bindings, times(1)).touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            anyLong(),
            any(Instant::class.java),
        )
    }

    @Test
    fun `confirmed upload can compare sha before replacing an existing file`() {
        service.initialize(binding, "{}")
        val target = root.resolve("asset.txt")
        Files.writeString(target, "first")
        val currentHash = service.content(binding.id(), "asset.txt").sha256

        val stale = assertThrows<PluginAdministrationException> {
            service.upload(
                binding.id(),
                "",
                MockMultipartFile("file", "asset.txt", "text/plain", "stale".toByteArray()),
                overwrite = true,
                expectedSha256 = "0".repeat(64),
            )
        }
        service.upload(
            binding.id(),
            "",
            MockMultipartFile("file", "asset.txt", "text/plain", "second".toByteArray()),
            overwrite = true,
            expectedSha256 = currentHash,
        )

        assertThat(stale.code()).isEqualTo("PLUGIN_FILE_CHANGED")
        assertThat(target).hasContent("second")
        verify(bindings, times(1)).touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            anyLong(),
            any(Instant::class.java),
        )
    }

    @Test
    fun `file mutations quiesce before rechecking sha and publishing a revision`() {
        Files.createDirectories(root)
        val target = root.resolve("notes.txt")
        Files.writeString(target, "before")
        val staleHash = service.content(binding.id(), "notes.txt").sha256
        doAnswer {
            Files.writeString(target, "callback-finished")
            null
        }.`when`(runtime).beforeBindingMutation(binding.id())

        val failure = assertThrows<PluginAdministrationException> {
            service.saveContent(
                binding.id(),
                UpdatePluginFileContentRequest("notes.txt", "administrator", staleHash),
            )
        }

        assertThat(failure.code()).isEqualTo("PLUGIN_FILE_CHANGED")
        assertThat(target).hasContent("callback-finished")
        verify(bindings, never()).touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            anyLong(),
            any(Instant::class.java),
        )
        verify(runtime).bindingMutationAborted(binding.id())
    }

    @Test
    fun `rejects invalid utf8 previews and configuration uploads without changing revision`() {
        service.initialize(binding, "{}")
        val invalid = byteArrayOf(0xC3.toByte(), 0x28)
        Files.write(root.resolve("broken.json"), invalid)

        val previewFailure = assertThrows<PluginAdministrationException> {
            service.content(binding.id(), "broken.json")
        }
        val uploadFailure = assertThrows<PluginAdministrationException> {
            service.upload(
                binding.id(),
                "",
                MockMultipartFile("file", "config.json", "application/json", invalid),
                overwrite = true,
            )
        }

        assertThat(previewFailure.code()).isEqualTo("PLUGIN_FILE_NOT_UTF8")
        assertThat(uploadFailure.code()).isEqualTo("PLUGIN_CONFIG_ENCODING_INVALID")
        assertThat(root.resolve("config.json")).hasContent("{}")
        verify(bindings, never()).touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            anyLong(),
            any(Instant::class.java),
        )
    }

    @Test
    fun `quiescence timeout fails closed before touching files or revision`() {
        Files.createDirectories(root)
        val target = root.resolve("notes.txt")
        Files.writeString(target, "before")
        doThrow(IllegalStateException("busy")).`when`(runtime).beforeBindingMutation(binding.id())

        val failure = assertThrows<PluginAdministrationException> {
            service.saveContent(binding.id(), UpdatePluginFileContentRequest("notes.txt", "after", null))
        }

        assertThat(failure.code()).isEqualTo("PLUGIN_BINDING_BUSY")
        assertThat(target).hasContent("before")
        verify(bindings, never()).touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            anyLong(),
            any(Instant::class.java),
        )
        verify(runtime).bindingMutationAborted(binding.id())
    }

    @Test
    fun `serializes touch and file write for the same binding`() {
        Files.createDirectories(root)
        val target = root.resolve("notes.txt")
        Files.writeString(target, "initial")
        val current = AtomicReference(binding)
        val firstReserved = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val reservations = AtomicInteger()
        `when`(bindings.findById(binding.id())).thenAnswer { Optional.of(current.get()) }
        `when`(bindings.touch(
            org.mockito.ArgumentMatchers.eq(binding.id()),
            anyLong(),
            any(Instant::class.java),
        )).thenAnswer { invocation ->
            val expected = invocation.getArgument<Long>(1)
            val existing = current.get()
            if (existing.revision() != expected) {
                throw com.mieai.qqbot.persistence.plugin.PluginBindingOptimisticLockException(binding.id(), expected)
            }
            val next = BotPluginBinding(
                existing.id(), existing.pluginId(), existing.botId(), existing.enabled(), expected + 1,
                existing.createdAt(), existing.updatedAt().plusSeconds(1), existing.runtimeState(), existing.runtimeError(),
            )
            current.set(next)
            if (reservations.incrementAndGet() == 1) {
                firstReserved.countDown()
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue()
            }
            next
        }

        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<PluginFileContentResponse> {
                service.saveContent(binding.id(), UpdatePluginFileContentRequest("notes.txt", "first", null))
            }
            assertThat(firstReserved.await(5, TimeUnit.SECONDS)).isTrue()
            val second = executor.submit<PluginFileContentResponse> {
                service.saveContent(binding.id(), UpdatePluginFileContentRequest("notes.txt", "second", null))
            }

            assertThrows<TimeoutException> { second.get(200, TimeUnit.MILLISECONDS) }
            verify(bindings, times(1)).touch(
                org.mockito.ArgumentMatchers.eq(binding.id()),
                anyLong(),
                any(Instant::class.java),
            )
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertThat(target).hasContent("second")
            assertThat(current.get().revision()).isEqualTo(2L)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `restores data directory when database deletion action fails`() {
        service.initialize(binding, "{\"value\":1}")
        Files.writeString(root.resolve("notes.txt"), "preserve")

        assertThrows<IllegalStateException> {
            service.withDataDirectoryTombstoned(binding) { throw IllegalStateException("database unavailable") }
        }

        assertThat(root.resolve("config.json")).hasContent("{\"value\":1}")
        assertThat(root.resolve("notes.txt")).hasContent("preserve")
        Files.list(dataRoot).use { paths ->
            assertThat(paths.map { it.fileName.toString() }.filter { it.startsWith(".tombstone-") }.toList()).isEmpty()
        }
    }

    @Test
    fun `restores a live binding tombstone before applying startup defaults`() {
        service.initialize(binding, "{\"custom\":true}")
        val tombstone = dataRoot.resolve(".tombstone-${binding.id()}-${UUID.randomUUID()}")
        Files.move(root, tombstone, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        `when`(bindings.findAll()).thenReturn(listOf(binding))

        service.initializeMissingBindings()

        assertThat(root.resolve("config.json")).hasContent("{\"custom\":true}")
        assertThat(tombstone).doesNotExist()
    }

}

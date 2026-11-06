package com.internetdeco.decocontrol

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic bytes and fake HTTPS connections only: no GitHub or router connections. */
class RamImageRepositoryTest {
    private lateinit var root: File
    private lateinit var cache: File
    private val body = ByteArray(100_003) { (it % 251).toByte() }
    private val address = "https://github.com/owner/release/image.itb"
    private val sha get() = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
    private val descriptor get() = RamImageRepository.ImageDescriptor("image.itb", address, body.size.toLong(), sha)
    private val target get() = File(cache, "image.itb")

    @Before fun prepare() {
        root = Files.createTempDirectory("deco-ram-image-test-").toFile()
        cache = File(root, "firmware").apply { mkdir() }
    }

    @After fun cleanup() {
        Thread.interrupted()
        root.deleteRecursively()
    }

    private class Reply(
        address: URL,
        private val status: Int = 200,
        private val bytes: ByteArray = ByteArray(0),
        private val length: Long = bytes.size.toLong(),
        private val headers: Map<String, String> = emptyMap(),
        private val streamFactory: (() -> InputStream)? = null,
    ) : HttpURLConnection(address) {
        var closed = false
        var streamOpened = false
        var largestReadRequest = 0
        private var stream: InputStream? = null
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream(): InputStream {
            streamOpened = true
            val source = streamFactory?.invoke() ?: ByteArrayInputStream(bytes)
            return object : InputStream() {
                override fun read() = source.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    largestReadRequest = maxOf(largestReadRequest, len)
                    return source.read(b, off, len)
                }
                override fun close() = source.close()
            }.also { stream = it }
        }
        override fun disconnect() { closed = true; stream?.close() }
    }

    private fun repository(
        available: (File) -> Long = { Long.MAX_VALUE },
        publish: (File, File) -> Unit = { partial, final ->
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
        directory: File = cache,
        factory: (URL) -> HttpURLConnection,
    ) = RamImageRepository(directory, descriptor, RamImageRepository.ConnectionFactory(factory), available, publish)

    private fun expectFailure(message: String? = null, action: () -> Unit): IOException {
        try {
            action()
            fail("Expected an image repository failure")
        } catch (e: IOException) {
            if (message != null) assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(message, ignoreCase = true))
            return e
        }
        error("unreachable")
    }

    private fun ownPartials() = cache.listFiles().orEmpty().filter { it.name.startsWith(".image.itb.download-") }

    @Test fun productionDescriptorPinsTheOriginalPublicRelease() {
        assertEquals(13_056_916L, RamImageRepository.SIZE)
        assertEquals("ce2c49c0fb6dadea9e6430cba9a39ca03f833bb6b20df7d63a9d0ddefeec4ee2", RamImageRepository.SHA256)
        assertEquals("openwrt-qualcommax-ipq50xx-tplink_deco-x50-v1-initramfs-uImage.itb", RamImageRepository.FILE_NAME)
        assertEquals("https://github.com/Oein/Deco-X50-Firmware-Unpack/releases/download/deco-x50-v1-r37324.2/${RamImageRepository.FILE_NAME}", RamImageRepository.URL)
    }

    @Test fun verifiedCacheUsesNoNetworkAndWorksWithNoFreeSpace() {
        target.writeBytes(body)
        val repository = repository(factory = { error("A verified cache must not contact the server") }, available = { error("No space needed for cache reuse") })
        assertEquals(target, repository.verifiedFile())
        assertEquals(target, repository.download())
        assertArrayEquals(body, target.readBytes())
    }

    @Test fun corruptCacheIsReportedWithoutDeletingIt() {
        val wrong = body.clone().also { it[12] = (it[12].toInt() xor 1).toByte() }
        target.writeBytes(wrong)
        val repository = repository { error("No network for cache verification") }
        assertNull(repository.verifiedFile())
        assertArrayEquals(wrong, target.readBytes())
    }

    @Test fun completeDownloadIsStreamedVerifiedAndPublishedOnlyAtTheEnd() {
        val reply = Reply(URL(address), bytes = body)
        val progress = mutableListOf<Long>()
        var publications = 0
        val repository = repository(factory = { reply }, publish = { partial, final ->
            publications++
            assertArrayEquals(body, partial.readBytes())
            assertFalse(final.exists())
            Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        })
        assertEquals(target, repository.download { count, total ->
            assertEquals(body.size.toLong(), total)
            assertTrue(count in 0..total)
            progress += count
        })
        assertEquals(1, publications)
        assertEquals(0L, progress.first())
        assertEquals(body.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        assertEquals(32_768, reply.largestReadRequest)
        assertEquals(20_000, reply.connectTimeout)
        assertEquals(45_000, reply.readTimeout)
        assertFalse(reply.instanceFollowRedirects)
        assertEquals("identity", reply.getRequestProperty("Accept-Encoding"))
        assertTrue(reply.closed)
        assertTrue(ownPartials().isEmpty())
        assertArrayEquals(body, target.readBytes())
    }

    @Test fun allowedHttpsRedirectChainIsFollowedExplicitly() {
        val urls = listOf(address, "https://release-assets.githubusercontent.com/asset?signature=fixture", "https://objects.githubusercontent.com/file")
        val opened = mutableListOf<String>()
        val replies = mutableListOf<Reply>()
        val repository = repository { current ->
            opened += current.toExternalForm()
            when (opened.size) {
                1 -> Reply(current, 302, headers = mapOf("Location" to urls[1]))
                2 -> Reply(current, 307, headers = mapOf("Location" to urls[2]))
                else -> Reply(current, bytes = body)
            }.also { replies += it }
        }
        repository.download()
        assertEquals(urls, opened)
        assertTrue(replies.all { it.closed })
        assertFalse(replies[0].streamOpened)
        assertFalse(replies[1].streamOpened)
    }

    @Test fun unsafeRedirectsAreRejectedBeforeOpeningTheirConnection() {
        for (location in listOf("http://github.com/file", "https://github.com.evil.invalid/file", "https://evil.invalid/file", "https://user:password@github.com/file", "https://github.com:444/file", "https://github.com/file#fragment")) {
            var opened = 0
            val repository = repository { current ->
                opened++
                Reply(current, 302, headers = mapOf("Location" to location))
            }
            expectFailure("unsafe redirect") { repository.download() }
            assertEquals(1, opened)
            assertFalse(target.exists())
            assertTrue(ownPartials().isEmpty())
        }
    }

    @Test fun relativeRedirectsRemainOnTheAllowedHttpsHost() {
        val opened = mutableListOf<String>()
        val repository = repository { current ->
            opened += current.toExternalForm()
            if (opened.size == 1) Reply(current, 302, headers = mapOf("Location" to "/next.itb"))
            else Reply(current, bytes = body)
        }
        repository.download()
        assertEquals(listOf(address, "https://github.com/next.itb"), opened)
    }

    @Test fun redirectLoopsAndLongChainsAreBounded() {
        var opened = 0
        val loop = repository { current -> opened++; Reply(current, 302, headers = mapOf("Location" to address)) }
        expectFailure("unsafe redirect") { loop.download() }
        assertEquals(1, opened)
        opened = 0
        val chain = repository { current -> opened++; Reply(current, 302, headers = mapOf("Location" to "/redirect-$opened")) }
        expectFailure("redirects") { chain.download() }
        assertEquals(6, opened)
        assertTrue(ownPartials().isEmpty())
    }

    @Test fun wrongDeclaredLengthFailsBeforeReadingTheBody() {
        val reply = Reply(URL(address), bytes = body, length = body.size.toLong() - 1)
        expectFailure("file size") { repository { reply }.download() }
        assertFalse(reply.streamOpened)
        assertFalse(target.exists())
        assertTrue(ownPartials().isEmpty())
    }

    @Test fun unknownLengthStillRequiresExactBytesAndHash() {
        val reply = Reply(URL(address), bytes = body, length = -1)
        repository { reply }.download()
        assertArrayEquals(body, target.readBytes())
    }

    @Test fun overlongAndTruncatedBodiesCannotBePublished() {
        for (wrong in listOf(body + byteArrayOf(9), body.copyOf(body.size - 1))) {
            val repository = repository { current -> Reply(current, bytes = wrong, length = -1) }
            expectFailure { repository.download() }
            assertFalse(target.exists())
            assertTrue(ownPartials().isEmpty())
        }
    }

    @Test fun corruptedPayloadPreservesTheCacheAndForeignPartialFiles() {
        val old = "previous cache".toByteArray()
        target.writeBytes(old)
        val foreign = File(cache, ".image.itb.download-owner.part").apply { writeText("owner file") }
        val other = File(cache, "unrelated.part").apply { writeText("unrelated") }
        val wrong = body.clone().also { it[5] = (it[5].toInt() xor 1).toByte() }
        expectFailure("SHA-256") { repository { Reply(it, bytes = wrong) }.download() }
        assertArrayEquals(old, target.readBytes())
        assertEquals("owner file", foreign.readText())
        assertEquals("unrelated", other.readText())
        assertEquals(listOf(foreign), ownPartials())
    }

    @Test fun lowDiskSpacePreventsNetworkAndTemporaryWrites() {
        val repository = repository(factory = { error("Must check free space before opening network") }, available = { body.size + 16L * 1024 * 1024 - 1 })
        expectFailure("storage") { repository.download() }
        assertFalse(target.exists())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun publishedGoodCacheIsKeptIfItAppearsDuringTransfer() {
        var publications = 0
        val repository = repository(factory = { Reply(it, bytes = body) }, publish = { _, _ -> publications++ })
        repository.download { count, _ -> if (count == 0L) target.writeBytes(body) }
        assertEquals(0, publications)
        assertArrayEquals(body, target.readBytes())
        assertTrue(ownPartials().isEmpty())
    }

    @Test fun publicationFailurePreservesPreviousBytesAndSanitizesErrors() {
        target.writeText("original")
        val repository = repository(factory = { Reply(it, bytes = body) }, publish = { _, _ -> throw IOException("https://assets.invalid/?private=secret") })
        val failure = expectFailure { repository.download() }
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertNull(failure.cause)
        assertEquals("original", target.readText())
        assertTrue(ownPartials().isEmpty())
    }

    @Test fun connectionErrorsDoNotExposeSignedUrlsOrTheirCauses() {
        val repository = repository { throw IOException("Failed URL https://release-assets.githubusercontent.com/file?token=secret") }
        val failure = expectFailure { repository.download() }
        assertFalse(failure.message.orEmpty().contains("token"))
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertNull(failure.cause)
        assertFalse(target.exists())
        assertTrue(ownPartials().isEmpty())
    }

    @Test fun httpErrorsAndUnexpectedEncodingCannotBeSaved() {
        for (reply in listOf(Reply(URL(address), 404), Reply(URL(address), bytes = body, headers = mapOf("Content-Encoding" to "gzip")))) {
            expectFailure { repository { reply }.download() }
            assertFalse(reply.streamOpened)
            assertTrue(reply.closed)
            assertFalse(target.exists())
        }
    }

    @Test fun readTimeoutReportsAUsefulSafeMessage() {
        val repository = repository { current -> Reply(current, length = -1, streamFactory = { throw SocketTimeoutException("timeout for signed URL secret") }) }
        val failure = expectFailure("timed out") { repository.download() }
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertNull(failure.cause)
        assertTrue(ownPartials().isEmpty())
    }

    @Test fun cancellationClosesConnectionAndOnlyRemovesTheOwnedPartial() {
        val reply = Reply(URL(address), bytes = body)
        val foreign = File(cache, "keep.part").apply { writeText("keep") }
        val repository = repository { reply }
        val failure = expectFailure("cancelled") {
            repository.download { count, _ -> if (count > 0) repository.cancelDownload() }
        }
        assertTrue(failure is InterruptedIOException)
        assertTrue(reply.closed)
        assertFalse(target.exists())
        assertTrue(ownPartials().isEmpty())
        assertEquals("keep", foreign.readText())
    }

    @Test fun anAlreadyInterruptedThreadDoesNotStartAConnection() {
        Thread.currentThread().interrupt()
        try {
            val failure = expectFailure("cancelled") { repository { error("Must not connect") }.download() }
            assertTrue(failure is InterruptedIOException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test fun twoRepositoryInstancesSerializeAndReuseOnePublishedDownload() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val problem = AtomicReference<Throwable?>()
        val factory: (URL) -> HttpURLConnection = { current ->
            opens.incrementAndGet()
            Reply(current, length = body.size.toLong(), streamFactory = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                ByteArrayInputStream(body)
            })
        }
        val first = repository(factory = factory)
        val second = repository(factory = factory)
        val threads = listOf(first, second).map { repository -> Thread {
            try { repository.download() } catch (failure: Throwable) { problem.set(failure) }
        } }
        try {
            threads[0].start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            threads[1].start()
            threads[1].join(100)
            assertTrue(threads[1].isAlive)
            assertEquals(1, opens.get())
        } finally {
            release.countDown()
            threads.forEach { it.join(5_000) }
        }
        assertNull(problem.get())
        assertTrue(threads.none { it.isAlive })
        assertEquals(1, opens.get())
        assertArrayEquals(body, target.readBytes())
    }

    @Test fun interruptingAQueuedDownloadDoesNotCancelTheOtherInstance() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstProblem = AtomicReference<Throwable?>()
        val secondProblem = AtomicReference<Throwable?>()
        val first = repository { current -> Reply(current, length = body.size.toLong(), streamFactory = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ByteArrayInputStream(body)
        }) }
        val second = repository { error("Queued request must not open a connection") }
        val a = Thread { try { first.download() } catch (failure: Throwable) { firstProblem.set(failure) } }
        val b = Thread { try { second.download() } catch (failure: Throwable) { secondProblem.set(failure) } }
        try {
            a.start(); assertTrue(entered.await(5, TimeUnit.SECONDS)); b.start()
            b.interrupt(); b.join(5_000)
            assertFalse(b.isAlive)
            assertTrue(secondProblem.get() is InterruptedIOException)
        } finally { release.countDown(); a.join(5_000); b.join(5_000) }
        assertNull(firstProblem.get())
        assertArrayEquals(body, target.readBytes())
    }

    @Test fun cacheSymlinksAreNeverFollowedOrReplaced() {
        val other = File(root, "owner-file").apply { writeText("owner bytes") }
        Files.createSymbolicLink(target.toPath(), other.toPath())
        val repository = repository { Reply(it, bytes = body) }
        assertNull(repository.verifiedFile())
        expectFailure("unsafe") { repository.download() }
        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertEquals("owner bytes", other.readText())
        assertTrue(ownPartials().isEmpty())
    }

    @Test fun aSymlinkedCacheDirectoryCannotBeUsedEvenWithMatchingBytes() {
        val outside = File(root, "other").apply { mkdir() }
        File(outside, "image.itb").writeBytes(body)
        cache.delete()
        Files.createSymbolicLink(cache.toPath(), outside.toPath())
        val repository = repository { error("Unsafe directory must not open a connection") }
        assertNull(repository.verifiedFile())
        expectFailure("unsafe") { repository.download() }
        assertArrayEquals(body, File(outside, "image.itb").readBytes())
        Files.delete(cache.toPath())
    }
}

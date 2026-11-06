package com.internetdeco.decocontrol

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL as NetworkUrl
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection

/** Downloads the original public RAM image. This class never contacts a router. */
class RamImageRepository internal constructor(
    private val directory: File,
    private val image: ImageDescriptor,
    private val connections: ConnectionFactory,
    private val freeBytes: (File) -> Long,
    private val publish: (File, File) -> Unit,
) {
    constructor(directory: File) : this(directory, ORIGINAL, HTTPS_CONNECTIONS, { it.usableSpace }, ::atomicPublish)

    companion object {
        const val FILE_NAME = "openwrt-qualcommax-ipq50xx-tplink_deco-x50-v1-initramfs-uImage.itb"
        const val URL = "https://github.com/Oein/Deco-X50-Firmware-Unpack/releases/download/deco-x50-v1-r37324.2/$FILE_NAME"
        const val SIZE = 13_056_916L
        const val SHA256 = "ce2c49c0fb6dadea9e6430cba9a39ca03f833bb6b20df7d63a9d0ddefeec4ee2"

        private const val BUFFER_BYTES = 32_768
        private const val DISK_RESERVE_BYTES = 16L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private val HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val LOCKS = ConcurrentHashMap<String, ReentrantLock>()
        internal val ORIGINAL = ImageDescriptor(FILE_NAME, URL, SIZE, SHA256)
        private val HTTPS_CONNECTIONS = ConnectionFactory { address ->
            address.openConnection() as? HttpsURLConnection ?: throw Failure("A secure image connection is required.")
        }

        private fun atomicPublish(partial: File, target: File) {
            // Same-directory atomic rename never exposes a truncated cache file.
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun safeAddress(address: NetworkUrl): Boolean =
            address.protocol.equals("https", ignoreCase = true) &&
                address.host.lowercase(Locale.ROOT) in HOSTS &&
                (address.port == -1 || address.port == 443) &&
                address.userInfo == null && address.ref == null
    }

    internal data class ImageDescriptor(val fileName: String, val url: String, val size: Long, val sha256: String) {
        init {
            require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")))
            require(size in 1..(64L * 1024 * 1024))
            require(sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    internal fun interface ConnectionFactory {
        fun open(address: NetworkUrl): HttpURLConnection
    }

    private class Failure(message: String) : IOException(message)
    private class Attempt {
        val cancelled = AtomicBoolean(false)
        @Volatile var connection: HttpURLConnection? = null
    }

    private val lock = LOCKS.computeIfAbsent(directory.canonicalPath) { ReentrantLock() }
    @Volatile private var activeAttempt: Attempt? = null
    private val target: File get() = File(directory, image.fileName)

    /** Returns only a complete file whose bytes match the pinned image. */
    fun verifiedFile(): File? = locked {
        try {
            if (Files.isSymbolicLink(directory.toPath())) null else target.takeIf { verified(it, null) }
        } catch (e: InterruptedIOException) {
            throw e
        } catch (_: IOException) {
            null
        }
    }

    /** Blocking I/O: call from a worker. Progress is downloaded bytes / exact expected bytes. */
    fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): File = locked {
        val attempt = Attempt()
        activeAttempt = attempt
        var partial: File? = null
        try {
            checkCancelled(attempt)
            if (Files.isSymbolicLink(directory.toPath())) throw Failure("The private firmware cache is unsafe.")
            if (verified(target, attempt)) {
                onProgress(image.size, image.size)
                return@locked target
            }
            if ((!directory.isDirectory && !directory.mkdirs()) || Files.isSymbolicLink(directory.toPath())) {
                throw Failure("The private firmware cache is unavailable.")
            }
            if (freeBytes(directory) < image.size + DISK_RESERVE_BYTES) {
                throw Failure("Not enough storage. Free space for the image and another 16 MB.")
            }
            onProgress(0, image.size)
            partial = File.createTempFile(".${image.fileName}.download-", ".part", directory)
            val digest = MessageDigest.getInstance("SHA-256")
            transfer(attempt, partial, digest, onProgress)
            if (digest.digest().hex() != image.sha256) {
                throw Failure("The downloaded image failed its SHA-256 check. Please download it again.")
            }
            checkCancelled(attempt)
            // Another process may have populated the cache; keep any verified copy.
            if (verified(target, attempt)) return@locked target
            if (Files.isSymbolicLink(target.toPath())) throw Failure("The firmware cache file is unsafe.")
            publish(partial, target)
            partial = null
            syncDirectory()
            if (!verified(target, attempt)) throw Failure("The stored image failed verification.")
            onProgress(image.size, image.size)
            target
        } catch (e: Failure) {
            throw e
        } catch (e: InterruptedIOException) {
            if (attempt.cancelled.get() || Thread.currentThread().isInterrupted) {
                throw cancelled()
            }
            if (e is SocketTimeoutException) throw Failure("The image download timed out. Please try again.")
            throw Failure("The image transfer was interrupted. Please try again.")
        } catch (_: IOException) {
            checkCancelled(attempt)
            // HTTP exceptions can contain signed redirect URLs. Never retain their text or cause.
            throw Failure("The image could not be downloaded or saved. Please try again.")
        } catch (_: RuntimeException) {
            checkCancelled(attempt)
            throw Failure("The image download stopped. Please try again.")
        } finally {
            runCatching { attempt.connection?.disconnect() }
            attempt.connection = null
            activeAttempt = null
            // Only this invocation's exclusive temporary file is owned here.
            partial?.delete()
        }
    }

    /** Can be called by the UI cancellation action; interrupts also cancel queued/active work. */
    fun cancelDownload() {
        activeAttempt?.let {
            it.cancelled.set(true)
            runCatching { it.connection?.disconnect() }
        }
    }

    private fun transfer(attempt: Attempt, partial: File, digest: MessageDigest, progress: (Long, Long) -> Unit) {
        var address = NetworkUrl(image.url)
        val visited = HashSet<String>()
        for (redirect in 0..MAX_REDIRECTS) {
            checkCancelled(attempt)
            if (!safeAddress(address) || !visited.add(address.toExternalForm())) {
                throw Failure("The image server returned an unsafe redirect.")
            }
            val connection = connections.open(address)
            attempt.connection = connection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 45_000
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("User-Agent", "Deco-Control")
                checkCancelled(attempt)
                val status = connection.responseCode
                if (status in REDIRECTS) {
                    val location = connection.getHeaderField("Location")
                    if (redirect == MAX_REDIRECTS || location.isNullOrBlank() || location.length > 8192) {
                        throw Failure("The image server returned too many or invalid redirects.")
                    }
                    address = NetworkUrl(address, location)
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) throw Failure("The image server returned HTTP $status. Please try again.")
                val length = connection.contentLengthLong
                if (length >= 0 && length != image.size) throw Failure("The image server returned an unexpected file size.")
                val encoding = connection.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrBlank() && !encoding.equals("identity", ignoreCase = true)) {
                    throw Failure("The image server returned an unexpected file encoding.")
                }
                var received = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            checkCancelled(attempt)
                            val count = input.read(buffer)
                            checkCancelled(attempt)
                            if (count < 0) break
                            if (count == 0) throw Failure("The image transfer stopped unexpectedly.")
                            if (count > image.size - received) throw Failure("The image server sent more data than expected.")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            received += count
                            progress(received, image.size)
                        }
                        if (received != image.size) throw Failure("The image download was incomplete. Please try again.")
                        output.flush()
                        output.fd.sync()
                    }
                }
                return
            } finally {
                runCatching { connection.disconnect() }
                attempt.connection = null
            }
        }
        throw Failure("The image server returned too many redirects.")
    }

    private fun verified(file: File, attempt: Attempt?): Boolean {
        checkCancelled(attempt)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() != image.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkCancelled(attempt)
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0 || read > image.size - count) return false
                digest.update(buffer, 0, read)
                count += read
            }
        }
        checkCancelled(attempt)
        return count == image.size && digest.digest().hex() == image.sha256
    }

    private fun syncDirectory() {
        // Private app cache is recoverable. Some Android providers reject directory fsync;
        // the image itself was fsynced before its atomic publication in either case.
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun checkCancelled(attempt: Attempt?) {
        if (Thread.currentThread().isInterrupted || attempt?.cancelled?.get() == true) throw cancelled()
    }

    private fun cancelled() = InterruptedIOException("Image download cancelled.")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private inline fun <T> locked(block: () -> T): T {
        try {
            lock.lockInterruptibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw cancelled()
        }
        return try { block() } finally { lock.unlock() }
    }
}

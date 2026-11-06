package com.internetdeco.decocontrol

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class FileTransferPolicyTest {
    private fun receive(path: Path, data: ByteArray): Int {
        val sha = MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }
        val p = ProcessBuilder("/bin/sh", "-c", FileTransferPolicy.uploadCommand(path.toString(), sha, "a".repeat(32))).redirectErrorStream(true).start()
        runCatching { p.outputStream.use { it.write(data) } }; p.inputStream.readBytes()
        return p.waitFor()
    }
    @Test fun arbitraryNamesAreQuotedAndExistingFilesArePreserved() {
        val dir = Files.createTempDirectory(Path.of("/tmp"), "deco-upload-test-")
        try {
            val file = dir.resolve("it's a file.txt"); val first = "fixture one\n".toByteArray()
            assertEquals(0, receive(file, first)); assertArrayEquals(first, Files.readAllBytes(file))
            assertNotEquals(0, receive(file, "replacement".toByteArray())); assertArrayEquals(first, Files.readAllBytes(file))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun parentSymlinkOutsideWritableTreesIsRejected() {
        val dir = Files.createTempDirectory(Path.of("/tmp"), "deco-upload-test-")
        val protected = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), "deco-protected-test-")
        try {
            val link = dir.resolve("outside"); Files.createSymbolicLink(link, protected)
            assertNotEquals(0, receive(link.resolve("value.txt"), "fixture".toByteArray()))
            assertFalse(Files.exists(protected.resolve("value.txt")))
        } finally { dir.toFile().deleteRecursively(); protected.toFile().deleteRecursively() }
    }
}

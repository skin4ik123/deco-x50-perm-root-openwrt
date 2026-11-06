package com.internetdeco.decocontrol

/** The receiver resolves parents before writing and publishes without overwriting a file. */
object FileTransferPolicy {
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    fun uploadCommand(path: String, sha1: String, nonce: String): String {
        require(path == FileNavigation.normalize(path) && FileNavigation.writable(path)) { "Directory not allowed" }
        require(sha1.matches(Regex("[0-9a-f]{40}")) && nonce.matches(Regex("[0-9a-f]{32}")))
        val parent = FileNavigation.parent(path)
        val name = path.substringAfterLast('/')
        return """
            set -eu
            umask 077
            id_parent=${'$'}(readlink -f ${quote(parent)})
            case "${'$'}id_parent" in /tmp|/tmp/*|/tp_data/user_data|/tp_data/user_data/*) ;; *) exit 73 ;; esac
            test -d "${'$'}id_parent"
            id_target="${'$'}id_parent"/${quote(name)}
            id_partial="${'$'}id_parent"/${quote("$name.deco-$nonce.part")}
            test ! -e "${'$'}id_target" && test ! -L "${'$'}id_target"
            (set -C; cat > "${'$'}id_partial")
            test "${'$'}(sha1sum "${'$'}id_partial" | cut -d ' ' -f 1)" = $sha1
            ln "${'$'}id_partial" "${'$'}id_target"
            rm "${'$'}id_partial"
        """.trimIndent()
    }
}

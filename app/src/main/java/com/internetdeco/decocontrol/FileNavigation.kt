package com.internetdeco.decocontrol

/** Per-device directory history. Back restores the previous folder. */
class FileNavigation(initial: String = "/tmp") {
    var path: String = normalize(initial)
        private set
    private val history = ArrayDeque<String>()
    val canGoBack: Boolean get() = history.isNotEmpty() || path != "/"

    fun go(destination: String): Boolean {
        val next = normalize(destination)
        if (next == path) return false
        history.addLast(path)
        if (history.size > 100) history.removeFirst()
        path = next
        return true
    }

    fun back(): Boolean {
        if (history.isNotEmpty()) {
            path = history.removeLast()
            return true
        }
        if (path == "/") return false
        path = parent(path)
        return true
    }

    companion object {
        fun normalize(value: String): String {
            require(value.startsWith('/') && !value.contains('\u0000')) { "Invalid directory path" }
            val parts = ArrayDeque<String>()
            for (part in value.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
            return "/" + parts.joinToString("/")
        }
        fun parent(path: String): String = normalize(path).substringBeforeLast('/', "").ifBlank { "/" }
        fun writable(path: String): Boolean = normalize(path).let {
            it.startsWith("/tmp/") || it.startsWith("/tp_data/user_data/")
        }
        fun writableDirectory(path: String): Boolean = normalize(path).let {
            it == "/tmp" || it == "/tp_data/user_data" || writable(it)
        }
    }
}

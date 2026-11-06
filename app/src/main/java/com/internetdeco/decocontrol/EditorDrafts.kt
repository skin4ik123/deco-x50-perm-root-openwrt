package com.internetdeco.decocontrol

import java.util.concurrent.ConcurrentHashMap

/** Private, in-memory drafts survive Activity recreation; they are never sent until Save. */
class EditorDraft {
    var original: String? = null
    var text: String = ""
    var saving: Boolean = false
}
object EditorDrafts {
    private val drafts = ConcurrentHashMap<Pair<String, String>, EditorDraft>()
    fun get(host: String, path: String) = drafts.getOrPut(host to path) { EditorDraft() }
    fun remove(host: String, path: String) { drafts.remove(host to path) }
}

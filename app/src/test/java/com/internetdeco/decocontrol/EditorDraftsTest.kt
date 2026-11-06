package com.internetdeco.decocontrol

import org.junit.Assert.*
import org.junit.Test

class EditorDraftsTest {
    @Test fun activityRecreationRetainsUnsavedTextAndPendingSave() {
        val draft = EditorDrafts.get("10.20.30.40", "/tmp/rotation-test.txt")
        try {
            draft.original = "original"; draft.text = "unsaved edit"; draft.saving = true
            val restored = EditorDrafts.get("10.20.30.40", "/tmp/rotation-test.txt")
            assertEquals("unsaved edit", restored.text); assertEquals("original", restored.original); assertTrue(restored.saving)
            assertEquals("", EditorDrafts.get("10.20.30.41", "/tmp/rotation-test.txt").text)
        } finally { EditorDrafts.remove("10.20.30.40", "/tmp/rotation-test.txt"); EditorDrafts.remove("10.20.30.41", "/tmp/rotation-test.txt") }
    }
    @Test fun closingTheEditorDiscardsItsPrivateDraft() {
        EditorDrafts.get("10.20.30.40", "/tmp/closed-test.txt").text = "private edit"
        EditorDrafts.remove("10.20.30.40", "/tmp/closed-test.txt")
        assertEquals("", EditorDrafts.get("10.20.30.40", "/tmp/closed-test.txt").text)
        EditorDrafts.remove("10.20.30.40", "/tmp/closed-test.txt")
    }
}

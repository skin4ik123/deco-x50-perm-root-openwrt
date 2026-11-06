package com.internetdeco.decocontrol

import org.junit.Assert.*
import org.junit.Test

class FileNavigationTest {
    @Test fun androidBackReturnsThroughDirectoryHistoryThenParent() {
        val nav = FileNavigation("/tmp")
        nav.go("/tmp/logs"); nav.go("/tp_data/user_data")
        assertTrue(nav.back()); assertEquals("/tmp/logs", nav.path)
        assertTrue(nav.back()); assertEquals("/tmp", nav.path)
        assertTrue(nav.back()); assertEquals("/", nav.path)
        assertFalse(nav.back()); assertFalse(nav.canGoBack)
    }
    @Test fun eachDeviceHasIndependentHistory() {
        val one = FileNavigation("/tmp"); val two = FileNavigation("/tp_data/user_data")
        one.go("/etc/config"); assertEquals("/tp_data/user_data", two.path)
        one.back(); assertEquals("/tmp", one.path)
    }
    @Test fun canonicalLexicalPathsCannotEscapeWritableTrees() {
        assertEquals("/etc/config", FileNavigation.normalize("/tmp/../etc/./config//"))
        assertFalse(FileNavigation.writable("/tmp/../etc/config"))
        assertFalse(FileNavigation.writable("/tp_data/user_data-other/file"))
        assertTrue(FileNavigation.writableDirectory("/tmp")); assertTrue(FileNavigation.writable("/tp_data/user_data/file"))
    }
}

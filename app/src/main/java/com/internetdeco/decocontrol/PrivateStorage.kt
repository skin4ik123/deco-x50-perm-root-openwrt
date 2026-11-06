package com.internetdeco.decocontrol

import android.content.Context

/** Android's internal files/ directory can initially be group writable. */
internal object PrivateStorage {
    @Synchronized
    fun prepare(context: Context, host: String? = null) {
        val error = Native.prepareStorage(context.filesDir.path, host.orEmpty())
        check(error.isEmpty()) { error }
    }
}

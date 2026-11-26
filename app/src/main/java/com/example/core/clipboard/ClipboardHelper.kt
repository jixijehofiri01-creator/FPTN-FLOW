package com.example.core.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Type-safe clipboard utility eliminating duplicated unsafe `as ClipboardManager` casts across screens.
 */
object ClipboardHelper {

    /**
     * Copies [text] to the system clipboard under [label].
     *
     * @return `true` if the clipboard service accepted the clip, `false` otherwise.
     */
    fun copyText(context: Context, label: String, text: String): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        return runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)
    }

    /**
     * Reads plain text from the primary system clipboard item, or returns an empty string if absent.
     */
    fun readText(context: Context): String {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return ""
        return runCatching {
            manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        }.getOrDefault("")
    }
}

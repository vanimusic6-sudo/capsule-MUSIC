/*
 * Capsule Music — one localized, validated copy-link action for More menus.
 * Original implementation; GPL-3.0.
 */
package com.nikhil.yt.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.nikhil.yt.R

/**
 * Copies an actual HTTPS media link. Invalid and absent URLs are ignored:
 * a local-only playlist never produces a fake "?list=null" link.
 */
internal fun copyCapsuleMenuLink(context: Context, raw: String?): Boolean {
    val value = raw?.trim()?.takeIf { it.length in 1..4096 } ?: return false
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.host.isNullOrBlank()) return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.copy_link), value))
    Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
    return true
}

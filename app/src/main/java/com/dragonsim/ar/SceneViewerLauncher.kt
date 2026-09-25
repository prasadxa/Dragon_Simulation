package com.dragonsim.ar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a creature in Google Scene Viewer — the AR viewer built into Google Play
 * Services for AR (the same one Search/Chrome use for 3D files). Google handles
 * placement and tracking there. Needs the model at a public URL + internet.
 */
object SceneViewerLauncher {
    fun open(context: Context, creature: CreatureModel): Boolean {
        val url = creature.webUrl ?: return false
        val uri = Uri.parse("https://arvr.google.com/scene-viewer/1.0").buildUpon()
            .appendQueryParameter("file", url)
            .appendQueryParameter("mode", "ar_preferred")
            .appendQueryParameter("title", creature.label)
            .appendQueryParameter("resizable", "true")
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.ar.core")
            .putExtra("browser_fallback_url", url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

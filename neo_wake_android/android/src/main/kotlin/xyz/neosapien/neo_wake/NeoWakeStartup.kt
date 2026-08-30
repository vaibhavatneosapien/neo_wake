package xyz.neosapien.neo_wake

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Zero-side-effect [ContentProvider] used purely as an engine-independent
 * startup hook (U8 / KTD9) — mirrors neo_ble's own `NativeBleStartup`
 * exactly, for the same reason: Android instantiates registered
 * ContentProviders very early in process lifecycle, before any Activity
 * (and before a Flutter engine ever attaches), which is what makes this the
 * one hook that also runs on a headless FGS restart / process resurrection
 * with no Dart involved.
 *
 * `onCreate` just calls [NeoWakeAttach.bootstrap] — idempotent, so a Flutter
 * `register()` later firing `arm()` again (or a second engine spawn) never
 * duplicates the listener/session (KTD9).
 */
class NeoWakeStartup : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return true
        try {
            NeoWakeAttach.bootstrap(ctx)
        } catch (t: Throwable) {
            // Startup hooks must never crash the host process.
            Log.w("NeoWakeStartup", "bootstrap threw", t)
        }
        return true
    }

    // No-op CRUD surface — this provider exists solely for its onCreate.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}

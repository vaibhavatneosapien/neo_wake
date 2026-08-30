package xyz.neosapien.neo_wake

import android.content.Context

/**
 * Persisted, owned, versioned arm record (U8 / KTD8) — replaces
 * [NeoWakePlugin]'s log-only `arm`/`disarm` stub. Written natively
 * (SharedPreferences here; UserDefaults on iOS) so the engine-independent
 * bootstrap ([NeoWakeStartup] / a headless FGS restart) can self-arm with NO
 * Dart engine ever having booted.
 *
 * **Fail-closed is the whole point.** [resolveArmed] is the ONLY path
 * anything in this plugin may use to decide "should I attach and start
 * detecting" — never [armed] read directly off a raw decode. A missing,
 * corrupt, schema-mismatched, or owner-unattributed record must default to
 * NOT armed: this plugin runs headless with no Dart around to sanity-check
 * it, so there is no capture after sign-out or on a shared/restored device
 * with a stale record left over from a different account.
 */
data class NeoWakeArmRecord(
    val armed: Boolean,
    val ownerUid: String?,
    val modelVersion: String,
    val threshold: Double,
    val lagMs: Int,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    fun encode(): String = listOf(
        armed.toString(),
        ownerUid ?: "",
        modelVersion,
        threshold.toString(),
        lagMs.toString(),
        schemaVersion.toString(),
    ).joinToString("\n")

    companion object {
        /** Bump on any incompatible field change — a record written by an
         * older/newer schema must fail closed, not be half-decoded. */
        const val SCHEMA_VERSION = 1

        /**
         * Decode a persisted record, or `null` if it's missing/short/corrupt.
         * Fail-safe on corrupt (mirrors `NeoAmbientSuppressionRecord.decode`
         * in neo_ble): a record that can't prove it's a real, complete write
         * must be dropped like a missing one, never half-decoded with
         * fabricated defaults (a truncated write mid-crash must not resolve
         * to some accidentally-armed shape).
         */
        fun decode(raw: String?): NeoWakeArmRecord? {
            if (raw.isNullOrEmpty()) return null
            val lines = raw.split("\n")
            if (lines.size < 6) return null
            val armed = lines[0].toBooleanStrictOrNull() ?: return null
            val threshold = lines[3].toDoubleOrNull() ?: return null
            val lagMs = lines[4].toIntOrNull() ?: return null
            val schemaVersion = lines[5].toIntOrNull() ?: return null
            return NeoWakeArmRecord(
                armed = armed,
                ownerUid = lines[1].takeIf { it.isNotEmpty() },
                modelVersion = lines[2],
                threshold = threshold,
                lagMs = lagMs,
                schemaVersion = schemaVersion,
            )
        }
    }
}

/**
 * Fail-closed resolution (KTD8). Returns the record ONLY when it is safe to
 * self-arm from: `armed == true`, current schema, and a non-empty owner UID
 * that matches [currentUid]. Every other case — null record, `armed ==
 * false`, an unattributed record (`ownerUid` empty — never written by a
 * genuine arm() call), a schema mismatch, or a UID that doesn't match the
 * signed-in user right now — resolves to `null`, i.e. "do not attach".
 *
 * [currentUid] is deliberately nullable and un-trusted-by-default: a caller
 * that could not determine who is signed in (headless, no Firebase reachable
 * yet, cross-plugin read failed) MUST pass `null`, which this function
 * always fails closed against — never "trust the record because we
 * couldn't check".
 */
fun NeoWakeArmRecord?.resolveArmed(currentUid: String?): NeoWakeArmRecord? {
    val record = this ?: return null
    if (!record.armed) return null
    if (record.schemaVersion != NeoWakeArmRecord.SCHEMA_VERSION) return null
    val owner = record.ownerUid
    if (owner.isNullOrEmpty()) return null
    if (currentUid.isNullOrEmpty()) return null
    if (owner != currentUid) return null
    return record
}

/**
 * SharedPreferences-backed store for one [NeoWakeArmRecord] (KTD8). Thin
 * persistence shim, mirrors `NeoAmbientSuppressionJournal`'s
 * tmp-then-`renameTo`-free-but-still-atomic-enough style: SharedPreferences'
 * own `apply()`/`commit()` already durable-writes a single file, so no extra
 * tmp-file dance is needed here (unlike the raw-file journal it mirrors).
 */
object NeoWakeArmStore {
    private const val PREFS_NAME = "neo_wake_arm"
    private const val KEY_RECORD = "record"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Persist [record], replacing whatever was stored before. */
    fun persist(context: Context, record: NeoWakeArmRecord) {
        prefs(context).edit().putString(KEY_RECORD, record.encode()).commit()
    }

    /** Read the stored record, or `null` if none exists / it's corrupt. */
    fun read(context: Context): NeoWakeArmRecord? =
        NeoWakeArmRecord.decode(prefs(context).getString(KEY_RECORD, null))

    /** Clear the stored record (disarm). Idempotent. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECORD).commit()
    }
}

package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM unit tests for the KTD8 fail-closed arm record (U8, plan
 * 2026-08-30-0038). [NeoWakeArmRecord.resolveArmed] is the ONLY path
 * anything in this plugin may use to decide "attach and start detecting" —
 * these tests are the load-bearing proof that missing, corrupt,
 * schema-mismatched, and owner-mismatched state ALL resolve to "do not
 * attach", per plan step 3.
 */
class NeoWakeArmRecordTest {

    private fun armedRecord(
        ownerUid: String? = "uid-1",
        schemaVersion: Int = NeoWakeArmRecord.SCHEMA_VERSION,
        armed: Boolean = true,
    ) = NeoWakeArmRecord(
        armed = armed,
        ownerUid = ownerUid,
        modelVersion = "neo_sim_sim_encore",
        threshold = 0.5,
        lagMs = 250,
        schemaVersion = schemaVersion,
    )

    // ---- encode/decode round trip -----------------------------------

    @Test
    fun encodeDecode_roundTripsExactly() {
        val record = armedRecord()
        val decoded = NeoWakeArmRecord.decode(record.encode())
        assertEquals(record, decoded)
    }

    @Test
    fun decode_nullRaw_returnsNull() {
        assertNull(NeoWakeArmRecord.decode(null))
    }

    @Test
    fun decode_emptyRaw_returnsNull() {
        assertNull(NeoWakeArmRecord.decode(""))
    }

    @Test
    fun decode_shortTruncatedWrite_returnsNull() {
        // Fewer than 6 lines — a write torn mid-crash, e.g. cut after
        // ownerUid — must not half-decode into a plausible-looking record.
        assertNull(NeoWakeArmRecord.decode("true\nuid-1\nneo_sim_sim_encore"))
    }

    @Test
    fun decode_garbageNumericField_returnsNull() {
        val raw = listOf("true", "uid-1", "neo_sim_sim_encore", "not-a-double", "250", "1").joinToString("\n")
        assertNull(NeoWakeArmRecord.decode(raw))
    }

    // ---- resolveArmed: the fail-closed gate ---------------------------

    @Test
    fun resolveArmed_validRecordMatchingUid_resolvesArmed() {
        val record = armedRecord(ownerUid = "uid-1")
        assertEquals(record, record.resolveArmed("uid-1"))
    }

    @Test
    fun resolveArmed_nullRecord_failsClosed() {
        val record: NeoWakeArmRecord? = null
        assertNull(record.resolveArmed("uid-1"))
    }

    @Test
    fun resolveArmed_notArmed_failsClosed() {
        val record = armedRecord(armed = false)
        assertNull(record.resolveArmed("uid-1"))
    }

    @Test
    fun resolveArmed_missingOwnerUid_failsClosed() {
        // An unattributed record (never written by a genuine arm() call, or
        // written when no uid was resolvable) must never self-arm.
        val record = armedRecord(ownerUid = null)
        assertNull(record.resolveArmed("uid-1"))
    }

    @Test
    fun resolveArmed_emptyOwnerUid_failsClosed() {
        val record = armedRecord(ownerUid = "")
        assertNull(record.resolveArmed("uid-1"))
    }

    @Test
    fun resolveArmed_currentUidUnresolvable_failsClosed() {
        // The bootstrap caller couldn't determine who is signed in right
        // now — must fail closed, never "trust the record because we
        // couldn't check".
        val record = armedRecord(ownerUid = "uid-1")
        assertNull(record.resolveArmed(null))
        assertNull(record.resolveArmed(""))
    }

    @Test
    fun resolveArmed_ownerMismatch_failsClosed() {
        // Sign-out then a different user signs in: neo_ble's persisted uid
        // changes, and the OLD record's ownerUid no longer matches — must
        // not capture for the new user on a shared/restored device.
        val record = armedRecord(ownerUid = "uid-1")
        assertNull(record.resolveArmed("uid-2"))
    }

    @Test
    fun resolveArmed_schemaMismatch_failsClosed() {
        val record = armedRecord(schemaVersion = NeoWakeArmRecord.SCHEMA_VERSION + 1)
        assertNull(record.resolveArmed("uid-1"))
    }

    @Test
    fun resolveArmed_corruptDecode_failsClosed() {
        // The realistic end-to-end path: a torn/garbage on-disk write
        // decodes to null, and null.resolveArmed must also fail closed.
        val decoded = NeoWakeArmRecord.decode("true\nuid-1")
        assertNull(decoded)
        assertNull(decoded.resolveArmed("uid-1"))
    }
}

package xyz.neosapien.neo_wake

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure JVM unit test for the Fix 6 bootstrap-time canary. `neo_ble`'s
 * `BleEventSinks` is never on this module's standalone test classpath (by
 * design — see [NeoBleAudioBridge]'s doc), so the canary is proven against
 * the real "class missing" case it exists to catch, not a mock.
 */
class NeoBleAudioBridgeTest {
    @Test
    fun verifyBridgeAvailable_withNoNeoBleOnTheClasspath_reportsFalse() {
        assertFalse(NeoBleAudioBridge.verifyBridgeAvailable())
    }
}

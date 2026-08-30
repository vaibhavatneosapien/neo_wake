package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM test for the U2 low-power session config (plan R6).
 *
 * This module's `testOptions.unitTests.returnDefaultValues = true` exists
 * because a plain JVM unit test cannot load ORT's native `.so` (only an
 * Android device/emulator can) — so this test only covers the config
 * constants [NeoWakeSessionConfig] applies, not [NeoWakeSessionConfig.newLowPowerSessionOptions]
 * itself, which touches [ai.onnxruntime.OrtSession.SessionOptions] and would
 * throw `UnsatisfiedLinkError` here. The real, options-object-touching
 * assertion is [NeoWakeSessionsInstrumentedTest] (androidTest, device-gated).
 */
class NeoWakeSessionConfigTest {
    @Test
    fun `intra and inter op thread counts are both one`() {
        assertEquals(1, NeoWakeSessionConfig.INTRA_OP_NUM_THREADS)
        assertEquals(1, NeoWakeSessionConfig.INTER_OP_NUM_THREADS)
    }

    @Test
    fun `spin config keys match the ORT session config key names`() {
        assertEquals("session.intra_op.allow_spinning", NeoWakeSessionConfig.INTRA_OP_ALLOW_SPINNING_KEY)
        assertEquals("session.inter_op.allow_spinning", NeoWakeSessionConfig.INTER_OP_ALLOW_SPINNING_KEY)
    }

    @Test
    fun `spinning is disabled`() {
        assertEquals("0", NeoWakeSessionConfig.ALLOW_SPINNING_DISABLED_VALUE)
    }
}

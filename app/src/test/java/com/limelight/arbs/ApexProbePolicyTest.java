package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ARBS A1-3b: GetInfo 재시도 상태 전이 단위 테스트. */
public class ApexProbePolicyTest {

    @Test
    public void tenFailuresNeededBeforeUnsupported() {
        ApexProbePolicy p = new ApexProbePolicy();
        p.begin();
        for (int i = 1; i <= 9; i++) {
            assertEquals(i, p.nextAttempt());
            assertTrue("attempt " + i + " must allow retry", p.onAttemptFailed());
            assertFalse(p.isUnsupported());
        }
        assertEquals(10, p.nextAttempt());
        assertFalse("10th failure gives up", p.onAttemptFailed());
        assertTrue(p.isUnsupported());
        assertFalse(p.isReady());
    }

    @Test
    public void successOnLaterAttemptIsReady() {
        ApexProbePolicy p = new ApexProbePolicy();
        p.begin();
        p.nextAttempt();
        assertTrue(p.onAttemptFailed());
        p.nextAttempt();
        assertTrue(p.onAttemptFailed());
        p.nextAttempt();
        p.onReady();
        assertTrue(p.isReady());
        assertFalse(p.isUnsupported());
    }

    @Test
    public void definitiveUnsupportedDoesNotRetry() {
        ApexProbePolicy p = new ApexProbePolicy();
        p.begin();
        p.nextAttempt();
        p.onDefinitiveUnsupported();
        assertTrue(p.isUnsupported());
    }

    @Test
    public void manualRetryOnlyFromUnsupportedAndRestartsAtOne() {
        ApexProbePolicy p = new ApexProbePolicy();
        p.begin();
        assertFalse("PROBING: nothing to retry", p.manualRetry());
        for (int i = 0; i < 10; i++) {
            p.nextAttempt();
            p.onAttemptFailed();
        }
        assertTrue(p.isUnsupported());
        assertTrue(p.manualRetry());
        assertFalse(p.isUnsupported());
        assertEquals(0, p.attempt());
        assertEquals(1, p.nextAttempt());
    }

    @Test
    public void inputGapRestartsOnlyWhenUnsupported() {
        ApexProbePolicy p = new ApexProbePolicy();
        p.resetAll();
        assertFalse(p.inputSeen());
        assertFalse(p.onInput(1000));
        assertTrue(p.inputSeen());
        assertFalse("not unsupported: no restart even after a gap", p.onInput(9000));
        p.begin();
        for (int i = 0; i < 10; i++) {
            p.nextAttempt();
            p.onAttemptFailed();
        }
        assertTrue(p.isUnsupported());
        assertFalse("reports continue without a gap", p.onInput(9500));
        assertFalse(p.onInput(10000));
        assertTrue("2s silence then input -> restart", p.onInput(12100));
        assertFalse(p.isUnsupported());
        assertEquals(0, p.attempt());
    }

    @Test
    public void resetAllForgetsInput() {
        ApexProbePolicy p = new ApexProbePolicy();
        p.onInput(100);
        assertTrue(p.inputSeen());
        p.resetAll();
        assertFalse(p.inputSeen());
    }
}

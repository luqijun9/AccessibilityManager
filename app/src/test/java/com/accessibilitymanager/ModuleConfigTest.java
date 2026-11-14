package com.accessibilitymanager;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModuleConfigTest {

    @Test
    public void isLoadedValueValid_matchesCurrentBootCount() {
        assertTrue(ModuleConfig.isLoadedValueValid("15|1", 15));
        assertFalse(ModuleConfig.isLoadedValueValid("14|1", 15));
    }

    @Test
    public void isLoadedValueValid_fallsBackWhenRomHasNoBootCount() {
        assertTrue(ModuleConfig.isLoadedValueValid("-1|1", -1));
        assertTrue(ModuleConfig.isLoadedValueValid("10|1", -1));
    }

    @Test
    public void isLoadedValueValid_rejectsNullEmptyOrMalformed() {
        assertFalse(ModuleConfig.isLoadedValueValid(null, 10));
        assertFalse(ModuleConfig.isLoadedValueValid("", 10));
        assertFalse(ModuleConfig.isLoadedValueValid("invalid|1", 10));
    }

    @Test
    public void parseCircuitConfig_returnsDefaultsAndClampsRange() {
        assertArrayEquals(new int[] { 3, 0, 2 }, ModuleConfig.parseCircuitConfig(null));
        assertArrayEquals(new int[] { 3, 0, 2 }, ModuleConfig.parseCircuitConfig(""));
        assertArrayEquals(new int[] { 5, 0, 4 }, ModuleConfig.parseCircuitConfig("5|0|4"));
        assertArrayEquals(new int[] { 20, 1440, 10 }, ModuleConfig.parseCircuitConfig("99|9999|50"));
        assertArrayEquals(new int[] { 1, 0, 1 }, ModuleConfig.parseCircuitConfig("0|-5|0"));
    }

    @Test
    public void hasUnconsumedGapInSecure_detectsTrimmedHeadOrSessionChange() {
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure(null, "s1", 10L));
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure("", "s1", 10L));
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure("s1|15|1000|msg", "", 0L));
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure("s1|10|1000|msg\ns1|11|1001|msg2", "s1", 10L));
        assertFalse(ModuleConfig.hasUnconsumedGapInSecure("s1|11|1001|msg2", "s1", 10L));
        assertTrue(ModuleConfig.hasUnconsumedGapInSecure("s1|15|1000|msg", "s1", 10L));
        assertTrue(ModuleConfig.hasUnconsumedGapInSecure("s2|1|1000|msg", "s1", 10L));
    }
}


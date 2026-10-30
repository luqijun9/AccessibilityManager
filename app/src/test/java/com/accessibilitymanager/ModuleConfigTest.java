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
        assertArrayEquals(new int[] { 3, 10, 2 }, ModuleConfig.parseCircuitConfig(null));
        assertArrayEquals(new int[] { 3, 10, 2 }, ModuleConfig.parseCircuitConfig(""));
        assertArrayEquals(new int[] { 5, 0, 4 }, ModuleConfig.parseCircuitConfig("5|0|4"));
        assertArrayEquals(new int[] { 20, 1440, 10 }, ModuleConfig.parseCircuitConfig("99|9999|50"));
        assertArrayEquals(new int[] { 1, 0, 1 }, ModuleConfig.parseCircuitConfig("0|-5|0"));
    }
}


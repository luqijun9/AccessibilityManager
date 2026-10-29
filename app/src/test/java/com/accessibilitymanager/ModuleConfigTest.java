package com.accessibilitymanager;

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
}


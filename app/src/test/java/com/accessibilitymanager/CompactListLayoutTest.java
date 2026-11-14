package com.accessibilitymanager;

import static org.junit.Assert.assertEquals;

import android.view.View;

import org.junit.Test;

public class CompactListLayoutTest {

    @Test
    public void resolveItemViewType_returnsExpectedTypeForStandardAndCompact() {
        assertEquals(MainActivity.TYPE_ITEM,
                MainActivity.resolveItemViewType(0, 3, true, false));
        assertEquals(MainActivity.TYPE_ITEM_COMPACT,
                MainActivity.resolveItemViewType(0, 3, true, true));
        assertEquals(MainActivity.TYPE_ITEM,
                MainActivity.resolveItemViewType(2, 3, false, false));
        assertEquals(MainActivity.TYPE_ITEM_COMPACT,
                MainActivity.resolveItemViewType(2, 3, false, true));
    }

    @Test
    public void resolveItemViewType_returnsFooterOnlyAtEndWhenHasFooter() {
        assertEquals(MainActivity.TYPE_FOOTER,
                MainActivity.resolveItemViewType(3, 3, true, false));
        assertEquals(MainActivity.TYPE_FOOTER,
                MainActivity.resolveItemViewType(3, 3, true, true));
        assertEquals(MainActivity.TYPE_ITEM,
                MainActivity.resolveItemViewType(3, 3, false, false));
        assertEquals(MainActivity.TYPE_ITEM_COMPACT,
                MainActivity.resolveItemViewType(3, 3, false, true));
    }

    @Test
    public void resolveLockVisibility_usesGoneWhenDisabledInCompactMode() {
        assertEquals(View.VISIBLE, MainActivity.resolveLockVisibility(true, false));
        assertEquals(View.VISIBLE, MainActivity.resolveLockVisibility(true, true));
        assertEquals(View.INVISIBLE, MainActivity.resolveLockVisibility(false, false));
        assertEquals(View.GONE, MainActivity.resolveLockVisibility(false, true));
    }
}


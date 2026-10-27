package com.accessibilitymanager;

import org.junit.Test;
import static org.junit.Assert.*;

public class PinyinUtilsTest {

    @Test
    public void testGetPinyin_nullOrEmpty() {
        assertEquals("", PinyinUtils.getPinyin(null));
        assertEquals("", PinyinUtils.getPinyin(""));
    }

    @Test
    public void testGetPinyin_chinese() {
        assertEquals("NIHAO", PinyinUtils.getPinyin("你好"));
        assertEquals("CESHI", PinyinUtils.getPinyin("测试"));
    }

    @Test
    public void testGetPinyin_mixed() {
        assertEquals("WECHAT123", PinyinUtils.getPinyin("WeChat123"));
        assertEquals("WXWEIXIN", PinyinUtils.getPinyin("wx微信"));
    }
}

package com.accessibilitymanager;

import net.sourceforge.pinyin4j.PinyinHelper;

public class PinyinUtils {
    public static String getPinyin(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Match Chinese characters
            if (Character.toString(c).matches("[\\u4E00-\\u9FA5]+")) {
                String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c);
                if (pinyinArray != null && pinyinArray.length > 0) {
                    sb.append(pinyinArray[0].replaceAll("\\d", "").toUpperCase());
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }
}

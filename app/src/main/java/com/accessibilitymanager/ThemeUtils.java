package com.accessibilitymanager;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

public class ThemeUtils {
    public static final String PREF_THEME = "app_theme_color";
    public static final String THEME_BLUE = "blue";
    public static final String THEME_GREEN = "green";
    public static final String THEME_PURPLE = "purple";
    public static final String THEME_DYNAMIC = "dynamic";

    public static void applyTheme(Activity activity) {
        SharedPreferences sp = activity.getSharedPreferences("Main", Context.MODE_PRIVATE);
        String theme = sp.getString(PREF_THEME, THEME_BLUE);

        switch (theme) {
            case THEME_GREEN:
                activity.setTheme(R.style.AppTheme_Green);
                break;
            case THEME_PURPLE:
                activity.setTheme(R.style.AppTheme_Purple);
                break;
            case THEME_BLUE:
                activity.setTheme(R.style.AppTheme_Blue);
                break;
            case THEME_DYNAMIC:
            default:
                activity.setTheme(R.style.AppTheme_Blue); // Fallback
                com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(activity);
                break;
        }
    }
}

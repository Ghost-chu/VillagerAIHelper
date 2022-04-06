package com.ghostchu.villageraihelper;

import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Util {
    @Nullable
    private static String resolveString(String str){
        if (str == null) {
            return null;
        }
        if (str.startsWith("MemorySection")) {
            return  null;
        }
        str = parseColours(str);
       return str;
    }

    /**
     * Parse colors for the Text.
     *
     * @param text the text
     * @return parsed text
     */
    @NotNull
    public static String parseColours(@Nullable String text) {
        if (StringUtils.isEmpty(text)) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}

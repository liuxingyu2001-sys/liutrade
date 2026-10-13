package com.liuxingyu.liutrade;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

/** 颜色与文本工具。 */
final class Msg {

    private Msg() {
    }

    static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    static List<String> color(List<String> lines) {
        List<String> out = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                out.add(color(line));
            }
        }
        return out;
    }

    static String plain(String text) {
        return text == null ? "" : ChatColor.stripColor(color(text));
    }
}

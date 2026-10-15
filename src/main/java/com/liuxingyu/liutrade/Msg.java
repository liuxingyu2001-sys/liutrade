package com.liuxingyu.liutrade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    /** 转成 Adventure 文本组件（Sparrow 界面标题用）。 */
    static Component component(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(color(text));
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

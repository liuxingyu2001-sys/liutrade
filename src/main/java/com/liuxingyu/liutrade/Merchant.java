package com.liuxingyu.liutrade;

import java.util.List;

/** 一个交易商店（对应一个虚拟村民）. */
final class Merchant {

    final String id;
    String name;
    String title;
    String npc = "";
    String permission = "";
    ItemSpec icon;
    List<Trade> trades;

    Merchant(String id) {
        this.id = id;
    }

    String displayName() {
        return name != null && !name.isBlank() ? name : id;
    }

    String displayTitle() {
        return title != null && !title.isBlank() ? title : displayName();
    }

    /** 按 YAML 键 / 1 开始的序号 / 显示名查找一笔交易（用于 /liutrade give）。 */
    Trade findTrade(String token) {
        if (token == null || token.isBlank() || trades == null) {
            return null;
        }
        String text = token.trim();
        for (Trade trade : trades) {
            if (trade.key != null && trade.key.equalsIgnoreCase(text)) {
                return trade;
            }
        }
        try {
            int index = Integer.parseInt(text);
            if (index >= 1 && index <= trades.size()) {
                return trades.get(index - 1);
            }
            return null;
        } catch (NumberFormatException ignored) {
            // 不是序号，继续按名字匹配
        }
        for (Trade trade : trades) {
            if (trade.name != null && Msg.plain(trade.name).equalsIgnoreCase(text)) {
                return trade;
            }
        }
        return null;
    }
}

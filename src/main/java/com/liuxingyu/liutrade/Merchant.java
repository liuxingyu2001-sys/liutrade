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
}

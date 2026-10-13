package com.liuxingyu.liutrade;

import java.util.List;

/** 一笔交易：1~2 个输入 → 1 个产出（显式或经 SoulSpace 转换）。 */
final class Trade {

    final String id;
    String name;
    /** 界面 lore 里自定义的说明文字（在名字下方）。 */
    List<String> description = java.util.Collections.emptyList();
    List<ItemSpec> inputs;
    /** 显式产出；为 null 表示用 {@link #convert} 走 SoulSpace 转换规则。 */
    List<ItemSpec> outputs;
    boolean convert;
    String permission = "";

    Trade(String id) {
        this.id = id;
    }
}

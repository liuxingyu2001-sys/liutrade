package com.liuxingyu.liutrade;

import java.util.List;

/** 一笔交易：1~N 个输入（自定义界面最多 {@link #MAX_INPUTS}）→ 1 个或多个产出。 */
final class Trade {

    /** 自定义界面一屏输入槽上限（原生村民界面只能 2 个）。 */
    static final int MAX_INPUTS = 6;

    final String id;
    /** 交易名（聊天提示/默认图标名）。 */
    String name;
    /** 仅用于交易列表图标的名字（为空则用 {@link #name}）。 */
    String displayName;
    /** 仅用于交易列表图标的物品（为空则用输入[0]的显示样品）。 */
    ItemSpec display;
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

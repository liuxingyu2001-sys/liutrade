package com.liuxingyu.liutrade;

import java.util.List;

/** 一笔交易：1~N 个输入（自定义界面最多 {@link #MAX_INPUTS}）→ 1 个或多个产出。 */
final class Trade {

    /** 自定义界面一屏输入槽上限（原生村民界面只能 2 个）。 */
    static final int MAX_INPUTS = 6;

    final String id;
    /** 交易在 YAML 里的键（用于 /liutrade give 按键定位）。 */
    String key = "";
    /** 交易名（聊天提示/默认图标名）。 */
    String name;
    /** 仅用于交易列表图标的名字（为空则用 {@link #name}）。 */
    String displayName;
    /** 仅用于交易列表图标的物品（为空则用首个产出的显示样品）。 */
    ItemSpec display;
    /** 界面 lore 里自定义的说明文字（在名字下方）。 */
    List<String> description = java.util.Collections.emptyList();
    List<ItemSpec> inputs;
    /** 显式产出；为 null 表示用 {@link #convert} 走 SoulSpace 转换规则。 */
    List<ItemSpec> outputs;
    /** 产出时以控制台身份执行的指令（{@code %player%}/{@code %uuid%}/{@code %times%} 可替换）；可为空。 */
    List<String> commands = java.util.Collections.emptyList();
    /** 纯指令产出图标的名字（取指令条目上的 {@code display_name}/{@code label}）；为空时显示原生指令。 */
    String commandDisplayName;
    boolean convert;
    String permission = "";

    Trade(String id) {
        this.id = id;
    }

    /** 是否有物品类产出（显式 {@code outputs} 或 {@code convert} 转换）。 */
    boolean hasItemOutput() {
        return convert || (outputs != null && !outputs.isEmpty());
    }

    /** 是否配置了指令产出。 */
    boolean hasCommands() {
        return commands != null && !commands.isEmpty();
    }

    /**
     * 指令产出图标显示的名字：条目上配置了 {@code display_name}/{@code label} 就用它，
     * 否则直接显示第一条原生指令（让玩家看到实际会执行什么）。
     */
    String commandName() {
        if (commandDisplayName != null && !commandDisplayName.isBlank()) {
            return commandDisplayName;
        }
        if (hasCommands()) {
            String first = commands.get(0);
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
        }
        return "指令产出";
    }
}

package com.liuxingyu.liutrade;

import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.NormalWindow;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易配方查看界面（Sparrow UI 箱子窗口）：展示这笔交易<b>自己配置的输入 → 输出</b>
 * （不是原版工作台配方）。
 *
 * <p>左侧 3×3 摆输入（含数量与「按规则匹配」提示），中间箭头，右侧摆产出（显式 outputs 或
 * {@code convert} 转换预览），有指令产出时额外显示指令块。作为交易界面的下一扇打开时，
 * ESC 由 Sparrow 会话原生返回；从原生村民界面打开时是根窗，结束后由 {@code onEnd} 重开村民界面。</p>
 */
final class RecipeGui {

    /** 输入摆放位（3×3 网格）。 */
    private static final int[] INPUT_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    /** 产出摆放位（首个在产出格，其余向下）。 */
    private static final int[] OUTPUT_SLOTS = {25, 34, 43, 52};
    private static final int SLOT_ARROW = 24;
    private static final int SLOT_COMMAND = 16;
    private static final int SLOT_BACK = 49;

    private final Player player;
    private final NormalWindow window;

    private RecipeGui(LiuTrade plugin, Player player, Trade trade, Runnable onEnd) {
        this.player = player;
        this.window = NormalWindow.builder()
                .setTitleSupplier(() -> Msg.component("&8交易配方：&f" + tradeName(trade)))
                .setUpperPane(buildPane(plugin, trade))
                .setBackOnPlayerClose(true)
                .addSessionEndHandler(reason -> {
                    if (onEnd != null && player.isOnline()) {
                        onEnd.run();
                    }
                })
                .build(player);
    }

    /** 构造一扇作为交易界面下一级的配方窗（ESC 由 Sparrow 会话原生返回）。 */
    static RecipeGui child(LiuTrade plugin, Player player, Trade trade) {
        return new RecipeGui(plugin, player, trade, null);
    }

    /** 作为根窗打开（原生村民界面用），关闭后回调 {@code onEnd}。 */
    static void openRoot(LiuTrade plugin, Player player, Trade trade, Runnable onEnd) {
        new RecipeGui(plugin, player, trade, onEnd).window.open();
    }

    NormalWindow window() {
        return window;
    }

    private NormalPane buildPane(LiuTrade plugin, Trade trade) {
        NormalPane pane = Pane.empty(9, 6);
        pane.setBackgroundItem(border());

        // 输入
        int index = 0;
        for (ItemSpec spec : trade.inputs) {
            if (index >= INPUT_SLOTS.length) {
                break;
            }
            pane.setElement(INPUT_SLOTS[index++], Element.item(Item.simple(inputIcon(plugin, spec))));
        }

        // 产出：显式 outputs 或 convert 转换预览
        List<ItemStack> outputs = plugin.service().previewAll(player, trade);

        // 箭头
        pane.setElement(SLOT_ARROW, Element.item(Item.simple(arrow(outputs.size(), trade.hasCommands()))));

        for (int i = 0; i < outputs.size() && i < OUTPUT_SLOTS.length; i++) {
            pane.setElement(OUTPUT_SLOTS[i], Element.item(Item.simple(outputIcon(outputs.get(i)))));
        }
        if (outputs.isEmpty() && !trade.hasCommands()) {
            pane.setElement(OUTPUT_SLOTS[0], Element.item(Item.simple(
                    staticItem(Material.BARRIER, "&c无产出", List.of("&7这笔交易没有配置产出")))));
        }

        // 指令产出
        if (trade.hasCommands()) {
            pane.setElement(SLOT_COMMAND, Element.item(Item.simple(commandIcon(trade))));
        }

        // 返回
        pane.setElement(SLOT_BACK, Element.item(Item.builder()
                .setItemProviderConstant(staticItem(Material.BARRIER, "&c返回 &7(ESC)", List.of("&7回到交易界面")))
                .addClickHandler(click -> click.window().backOrClose())
                .build()));
        return pane;
    }

    private ItemStack inputIcon(LiuTrade plugin, ItemSpec spec) {
        ItemStack sample = spec.iconSample(plugin.soulSpace());
        ItemStack icon = sample == null || sample.getType().isAir()
                ? new ItemStack(Material.PAPER)
                : sample.clone();
        icon.setAmount(Math.min(spec.amount, icon.getMaxStackSize()));
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color("&f" + plugin.specName(spec)));
            List<String> lore = new ArrayList<>();
            lore.add(Msg.color("&7需要 &f" + spec.amount + " &7个"));
            if (!spec.nativeRepresentable()) {
                lore.add(Msg.color("&8（按规则匹配）"));
            }
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack outputIcon(ItemStack stack) {
        ItemStack icon = stack.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null
                    ? new ArrayList<>(meta.getLore())
                    : new ArrayList<>();
            lore.add(Msg.color("&7产出 &f" + stack.getAmount() + " &7个"));
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack arrow(int outputCount, boolean commands) {
        List<String> lore = new ArrayList<>();
        lore.add(Msg.color("&8左侧：需要放入的物品"));
        lore.add(Msg.color("&8右侧：可以换到的产出"));
        if (outputCount > OUTPUT_SLOTS.length) {
            lore.add(Msg.color("&8（共 " + outputCount + " 个产出，只展示前 " + OUTPUT_SLOTS.length + " 个）"));
        } else if (commands && outputCount == 0) {
            lore.add(Msg.color("&8产出为指令（见上方命令块）"));
        }
        return staticItem(Material.ARROW, "&7→ &f输入换产出", lore);
    }

    private ItemStack commandIcon(Trade trade) {
        List<String> lore = new ArrayList<>();
        lore.add(Msg.color("&7交易成功后以控制台执行："));
        for (String command : trade.commands) {
            lore.add(Msg.color("  &8· &f" + command));
        }
        return staticItem(Material.COMMAND_BLOCK, "&a指令产出", lore);
    }

    private ItemStack border() {
        return staticItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
    }

    private static String tradeName(Trade trade) {
        return trade.displayName != null ? trade.displayName : trade.name;
    }

    private static ItemStack staticItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color(name));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(Msg.color(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}

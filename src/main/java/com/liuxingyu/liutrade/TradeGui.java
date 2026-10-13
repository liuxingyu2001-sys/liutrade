package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 村民式交易界面（54 格）：
 * <pre>
 *   左 5 列 x 5 行 = 交易列表（每页 25 笔，点击自动放入所需物品）
 *   右侧  23 = 输入1，24 = 输入2，25 = 产出（点击完成交易）
 *   底行  45 = 上一页，49 = 关闭，53 = 下一页
 * </pre>
 * 输入槽为真实槽位：玩家放置的物品就放在这里，产出槽实时显示能换到什么。
 */
final class TradeGui implements InventoryHolder {

    static final int[] TRADE_SLOTS = {
            0, 1, 2, 3, 4,
            9, 10, 11, 12, 13,
            18, 19, 20, 21, 22,
            27, 28, 29, 30, 31,
            36, 37, 38, 39, 40
    };
    static final int SLOT_IN1 = 23;
    static final int SLOT_IN2 = 24;
    static final int SLOT_OUT = 25;
    static final int SLOT_PREV = 45;
    static final int SLOT_CLOSE = 49;
    static final int SLOT_NEXT = 53;

    private final LiuTrade plugin;
    private final Player player;
    private final Merchant merchant;
    private final int page;
    private Inventory inventory;
    private int selected = -1;
    // 会话内缓存：CE 物品解析、产出预览只算一次（render 频繁发生时避免重复解析）。
    private final Map<Integer, ItemStack> iconCache = new java.util.HashMap<>();
    private final Map<Integer, List<ItemStack>> previewCache = new java.util.HashMap<>();
    private final Map<ItemSpec, String> specNameCache = new java.util.IdentityHashMap<>();

    TradeGui(LiuTrade plugin, Player player, Merchant merchant, int page) {
        this.plugin = plugin;
        this.player = player;
        this.merchant = merchant;
        this.page = Math.max(0, Math.min(page, pageCount()));
    }

    void open() {
        this.inventory = Bukkit.createInventory(this, 54, Msg.color(merchant.displayTitle()));
        int start = page * TRADE_SLOTS.length;
        for (int i = 0; i < TRADE_SLOTS.length; i++) {
            int index = start + i;
            if (index >= merchant.trades.size()) {
                break;
            }
            inventory.setItem(TRADE_SLOTS[i], tradeIcon(merchant.trades.get(index), index));
        }
        player.openInventory(inventory);
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    Merchant merchant() {
        return merchant;
    }

    Player player() {
        return player;
    }

    int page() {
        return page;
    }

    int pageCount() {
        int pages = (merchant.trades.size() + TRADE_SLOTS.length - 1) / TRADE_SLOTS.length;
        return Math.max(1, Math.min(pages, plugin.config().maxPages));
    }

    // ---------------- 渲染 ----------------

    void render() {
        ItemStack filler = filler();
        for (int slot = 0; slot < 54; slot++) {
            if (isInput(slot) || slot == SLOT_OUT || isTradeSlot(slot)
                    || slot == SLOT_PREV || slot == SLOT_NEXT || slot == SLOT_CLOSE) {
                continue;
            }
            inventory.setItem(slot, filler);
        }
        // 交易按钮（当前页之外的用填充物盖掉）
        int start = page * TRADE_SLOTS.length;
        for (int i = 0; i < TRADE_SLOTS.length; i++) {
            int index = start + i;
            inventory.setItem(TRADE_SLOTS[i], index < merchant.trades.size()
                    ? tradeIcon(merchant.trades.get(index), index)
                    : filler);
        }
        inventory.setItem(SLOT_PREV, page > 0
                ? button(Material.SPECTRAL_ARROW, "&e上一页", "&7第 " + page + " / " + pageCount() + " 页")
                : null);
        inventory.setItem(SLOT_NEXT, page < pageCount() - 1
                ? button(Material.SPECTRAL_ARROW, "&e下一页", "&7第 " + (page + 2) + " / " + pageCount() + " 页")
                : null);
        inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, "&c关闭", "&7点击关闭界面"));
        inventory.setItem(SLOT_OUT, outputIcon());
    }

    /** 当前要展示/执行的交易：选中优先，否则取第一个可完成的。 */
    Trade currentTrade() {
        if (selected >= 0 && selected < merchant.trades.size()) {
            return merchant.trades.get(selected);
        }
        for (Trade trade : merchant.trades) {
            if (batches(trade) > 0) {
                return trade;
            }
        }
        return null;
    }

    private ItemStack outputIcon() {
        Trade trade = currentTrade();
        if (trade == null) {
            boolean empty = isEmpty(SLOT_IN1) && isEmpty(SLOT_IN2);
            return button(empty ? Material.BOOK : Material.BARRIER,
                    empty ? "&e请放入交易物品" : "&c没有匹配的交易",
                    empty ? "&7把物品放入左侧两个输入槽" : "&7左键点击列表中的交易可自动放入");
        }
        int batches = batches(trade);
        if (batches <= 0) {
            return button(Material.BARRIER, "&c无法完成交易", "&7物品数量不足");
        }
        List<ItemStack> previews = previews(trade);
        if (previews.isEmpty()) {
            return button(Material.BARRIER, "&c无法完成交易", "&7物品数量不足，或转换规则未命中");
        }
        ItemStack result = previews.get(0);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            for (int i = 1; i < previews.size(); i++) {
                ItemStack extra = previews.get(i);
                lore.add(Msg.color("&7额外产出：&f" + plugin.itemName(extra) + " &7x" + extra.getAmount()));
            }
            lore.add("");
            lore.add(Msg.color("&e左键交易 1 次"));
            if (batches > 1) {
                lore.add(Msg.color("&eShift+左键交易全部 &7(" + batches + " 次)"));
            } else {
                lore.add(Msg.color("&7当前可交易 " + batches + " 次"));
            }
            meta.setLore(lore);
            result.setItemMeta(meta);
        }
        return result;
    }

    private ItemStack tradeIcon(Trade trade, int index) {
        ItemSpec first = trade.inputs.isEmpty() ? null : trade.inputs.get(0);
        ItemStack icon = baseIcon(trade, index).clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        meta.setDisplayName(Msg.color("&f" + trade.name));
        List<String> lore = new ArrayList<>();
        for (String line : trade.description) {
            lore.add(Msg.color(line));
        }
        if (!trade.description.isEmpty()) {
            lore.add("");
        }
        if (trade.inputs.size() > 1) {
            lore.add(Msg.color("&7需求（全部）："));
        } else {
            lore.add(Msg.color("&7需求："));
        }
        for (ItemSpec input : trade.inputs) {
            lore.add(Msg.color("  &8- &f" + specName(input) + " &7x" + input.amount));
        }
        lore.add(Msg.color("&7产出："));
        List<ItemStack> previews = previews(trade);
        if (!previews.isEmpty()) {
            for (ItemStack pv : previews) {
                lore.add(Msg.color("  &8- &f" + plugin.itemName(pv) + " &7x" + pv.getAmount()
                        + (trade.convert ? " &8(转换)" : "")));
            }
        } else {
            lore.add(Msg.color("  &8- &c" + (trade.convert ? "转换规则未命中" : "无法解析")));
        }
        lore.add("");
        int batches = batches(trade);
        lore.add(Msg.color(batches > 0 ? "&7当前可交易 &f" + batches + " &7次" : "&c物品不足，无法交易"));
        lore.add(Msg.color("&e左键：自动放入所需物品"));
        if (first != null && !first.nativeRepresentable()) {
            lore.add(Msg.color("&8按规则匹配（需自定义界面）"));
        }
        meta.setLore(lore);
        if (index == selected) {
            meta.setEnchantmentGlintOverride(true);
        }
        icon.setItemMeta(meta);
        return icon;
    }

    /** 交易列表图标底物（缓存）：输入[0]的显示样品 → 商店图标 → 箱子。 */
    private ItemStack baseIcon(Trade trade, int index) {
        ItemStack cached = iconCache.get(index);
        if (cached != null) {
            return cached;
        }
        ItemSpec first = trade.inputs.isEmpty() ? null : trade.inputs.get(0);
        ItemStack sample = first == null ? null : first.iconSample(plugin.soulSpace());
        if (sample == null && merchant.icon != null) {
            sample = merchant.icon.iconSample(plugin.soulSpace());
        }
        ItemStack icon = sample != null ? sample.clone() : new ItemStack(Material.CHEST);
        icon.setAmount(1);
        iconCache.put(index, icon);
        return icon;
    }

    /** 产出预览（缓存，避免每次 render 重复跑转换规则）。 */
    private List<ItemStack> previews(Trade trade) {
        int index = merchant.trades.indexOf(trade);
        if (index < 0) {
            return plugin.service().previewAll(player, trade);
        }
        return previewCache.computeIfAbsent(index, k -> plugin.service().previewAll(player, trade));
    }

    /** 物品规格显示名（缓存，避免重复解析 CE 物品）。 */
    private String specName(ItemSpec spec) {
        if (spec == null) {
            return "?";
        }
        String cached = specNameCache.get(spec);
        if (cached != null) {
            return cached;
        }
        String name = plugin.specName(spec);
        specNameCache.put(spec, name);
        return name;
    }

    private ItemStack filler() {
        return button(Material.GRAY_STAINED_GLASS_PANE, " ", null);
    }

    private static ItemStack button(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color(name));
            if (lore != null) {
                meta.setLore(List.of(Msg.color(lore)));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // ---------------- 输入/匹配 ----------------

    /** 当前输入槽能满足该交易的份数（0 = 不匹配）。 */
    int batches(Trade trade) {
        ItemStack in1 = inventory.getItem(SLOT_IN1);
        ItemStack in2 = inventory.getItem(SLOT_IN2);
        ItemStack[] slots = {in1, in2};
        int batches = Integer.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            ItemSpec spec = i < trade.inputs.size() ? trade.inputs.get(i) : null;
            ItemStack item = slots[i];
            boolean present = item != null && !item.getType().isAir();
            if (spec == null) {
                if (present) {
                    return 0;
                }
                continue;
            }
            if (!present || !spec.matches(item, plugin.soulSpace())) {
                return 0;
            }
            batches = Math.min(batches, item.getAmount() / spec.amount);
        }
        return batches == Integer.MAX_VALUE ? 0 : batches;
    }

    /** 从玩家背包取出该交易一批所需物品，放入输入槽。 */
    void autoFill(Trade trade) {
        returnInputs();
        for (int i = 0; i < trade.inputs.size(); i++) {
            ItemSpec spec = trade.inputs.get(i);
            ItemStack taken = takeFromPlayer(spec, spec.amount);
            if (taken != null) {
                inventory.setItem(i == 0 ? SLOT_IN1 : SLOT_IN2, taken);
            }
        }
        selected = merchant.trades.indexOf(trade);
        render();
    }

    private ItemStack takeFromPlayer(ItemSpec spec, int need) {
        PlayerInventory inv = player.getInventory();
        ItemStack result = null;
        for (int i = 0; i < 36 && need > 0; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir() || !spec.matches(item, plugin.soulSpace())) {
                continue;
            }
            int take = Math.min(need, item.getAmount());
            if (result == null) {
                result = item.clone();
                result.setAmount(take);
            } else {
                result.setAmount(result.getAmount() + take);
            }
            if (take >= item.getAmount()) {
                inv.setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - take);
                inv.setItem(i, item);
            }
            need -= take;
        }
        return result;
    }

    /** 把输入槽里剩下的物品退回玩家背包（放不下则原地掉落）。 */
    void returnInputs() {
        for (int slot : new int[]{SLOT_IN1, SLOT_IN2}) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                giveOrDrop(player, item.clone());
                inventory.setItem(slot, null);
            }
        }
    }

    static void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    private boolean isEmpty(int slot) {
        ItemStack item = inventory.getItem(slot);
        return item == null || item.getType().isAir();
    }

    static boolean isInput(int slot) {
        return slot == SLOT_IN1 || slot == SLOT_IN2;
    }

    static boolean isTradeSlot(int slot) {
        for (int candidate : TRADE_SLOTS) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }
}

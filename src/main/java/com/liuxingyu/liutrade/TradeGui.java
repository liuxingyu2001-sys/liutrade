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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义交易界面（54 格），布局尽量直白：
 * <pre>
 *   第 1 行：[商店图标] [怎么用]
 *   第 2~4 行：交易列表（9 列 x 3 行 = 每页 27 笔，左键自动放入所需物品）
 *   第 5 行：[输入1][输入2][输入3]...  → [产出]   （输入数量按商店实际需要显示，最多 7 个）
 *   第 6 行：[上一页]        [关闭]        [下一页]
 * </pre>
 * 输入槽是真实槽位：玩家把物品放进去，产出槽实时显示能换到什么，点产出槽完成交易。
 */
final class TradeGui implements InventoryHolder {

    /** 交易列表：第 2~4 行（9 列 x 3 行 = 27）。 */
    static final int[] TRADE_SLOTS = {
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35
    };
    /** 输入槽：第 5 行左侧（最多 {@link Trade#MAX_INPUTS} 个，按商店实际需要显示）。 */
    static final int[] INPUT_SLOTS = {36, 37, 38, 39, 40, 41, 42};
    static final int SLOT_ICON = 0;
    static final int SLOT_HELP = 4;
    static final int SLOT_ARROW = 43;
    static final int SLOT_OUT = 44;
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
    private final Map<Integer, ItemStack> iconCache = new HashMap<>();
    private final Map<Integer, List<ItemStack>> previewCache = new HashMap<>();
    private final Map<ItemSpec, String> specNameCache = new IdentityHashMap<>();

    TradeGui(LiuTrade plugin, Player player, Merchant merchant, int page) {
        this.plugin = plugin;
        this.player = player;
        this.merchant = merchant;
        this.page = Math.max(0, Math.min(page, pageCount()));
    }

    void open() {
        this.inventory = Bukkit.createInventory(this, 54, Msg.color(merchant.displayTitle()));
        render();
        player.openInventory(inventory);
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

    /** 本商店一笔交易最多要几种物品（决定显示几个输入槽）。 */
    int inputCount() {
        int max = 1;
        for (Trade trade : merchant.trades) {
            max = Math.max(max, trade.inputs.size());
        }
        return Math.min(max, INPUT_SLOTS.length);
    }

    static int inputSlot(int index) {
        return INPUT_SLOTS[Math.max(0, Math.min(index, INPUT_SLOTS.length - 1))];
    }

    static int inputIndex(int slot) {
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            if (INPUT_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** 当前显示出来的输入槽（超出本商店需要的不算）。 */
    boolean isActiveInput(int slot) {
        int index = inputIndex(slot);
        return index >= 0 && index < inputCount();
    }

    // ---------------- 渲染 ----------------

    void render() {
        // 整屏重画（活动输入槽除外，里面是玩家真实物品）
        for (int slot = 0; slot < 54; slot++) {
            if (isActiveInput(slot)) {
                continue;
            }
            int hidden = inputIndex(slot);
            if (hidden >= 0) {
                // 本商店不需要的输入槽：里面的东西退回玩家
                ItemStack stray = inventory.getItem(slot);
                if (stray != null && !stray.getType().isAir()) {
                    giveOrDrop(player, stray);
                }
            }
            inventory.setItem(slot, null);
        }
        inventory.setItem(SLOT_ICON, shopIcon());
        inventory.setItem(SLOT_HELP, help());
        inventory.setItem(SLOT_ARROW, button(Material.ARROW, "&7→ &f产出", null));
        inventory.setItem(SLOT_OUT, outputIcon());
        inventory.setItem(SLOT_PREV, page > 0
                ? button(Material.SPECTRAL_ARROW, "&e上一页", List.of("&7第 " + (page + 1) + " / " + pageCount() + " 页"))
                : null);
        inventory.setItem(SLOT_NEXT, page < pageCount() - 1
                ? button(Material.SPECTRAL_ARROW, "&e下一页", List.of("&7第 " + (page + 2) + " / " + pageCount() + " 页"))
                : null);
        inventory.setItem(SLOT_CLOSE, button(Material.BARRIER, "&c关闭", List.of("&7点击关闭界面")));

        int start = page * TRADE_SLOTS.length;
        for (int i = 0; i < TRADE_SLOTS.length; i++) {
            int index = start + i;
            if (index >= merchant.trades.size()) {
                break;
            }
            inventory.setItem(TRADE_SLOTS[i], tradeIcon(merchant.trades.get(index), index));
        }
    }

    /** 顶部左侧的商店标识。 */
    private ItemStack shopIcon() {
        ItemStack sample = merchant.icon == null ? null : merchant.icon.iconSample(plugin.soulSpace());
        ItemStack item = sample != null ? sample.clone() : new ItemStack(Material.EMERALD);
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color("&6" + merchant.displayName()));
            meta.setLore(List.of(
                    Msg.color("&7共 &f" + merchant.trades.size() + " &7种交易"),
                    Msg.color("&7第 &f" + (page + 1) + " &7/ &f" + pageCount() + " &7页")));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 顶部「怎么用」说明书。 */
    private ItemStack help() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color("&e&l怎么用"));
            meta.setLore(List.of(
                    Msg.color("&7① &f左键 &7下方任意交易：自动放入所需物品"),
                    Msg.color("&7② 也可以自己把物品放进下面一行的输入槽"),
                    Msg.color("&7③ 右侧 &f产出格 &7出现物品后，&f左键 &7即可交易"),
                    Msg.color("&7④ &fShift+左键 &7产出格：一次交易全部"),
                    Msg.color("&7⑤ 下方 &f箭头 &7翻页")));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 产出格：没放物品/没配好时给出清楚提示，配好时显示能换到什么。 */
    private ItemStack outputIcon() {
        boolean empty = inputsEmpty();
        Trade trade = currentTrade();
        if (trade == null) {
            if (empty) {
                return button(Material.GRAY_STAINED_GLASS_PANE, "&7产出",
                        List.of(Msg.color("&7把物品放进左边一行的输入槽"),
                                Msg.color("&7或左键下方交易自动放入")));
            }
            return button(Material.BARRIER, "&c没有匹配的交易",
                    List.of(Msg.color("&7这些物品换不到东西"),
                            Msg.color("&7请对照下方交易的需求")));
        }
        int batches = batches(trade);
        List<ItemStack> previews = previews(trade);
        if (previews.isEmpty()) {
            return button(Material.BARRIER, "&c无法完成交易",
                    List.of(Msg.color("&7物品数量不足，或转换规则未命中")));
        }
        ItemStack result = previews.get(0).clone();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(Msg.color("&7需要："));
            for (ItemSpec input : trade.inputs) {
                lore.add(Msg.color("  &8· &f" + specName(input) + " &7x" + input.amount));
            }
            for (int i = 1; i < previews.size(); i++) {
                ItemStack extra = previews.get(i);
                lore.add(Msg.color("&7额外产出 &f" + plugin.itemName(extra) + " &7x" + extra.getAmount()));
            }
            lore.add("");
            lore.add(Msg.color("&a左键 &7：交易 1 次"));
            lore.add(Msg.color("&aShift+左键 &7：全部 &f" + batches + " &7次"));
            meta.setLore(lore);
            result.setItemMeta(meta);
        }
        return result;
    }

    /** 交易列表里的一笔交易。 */
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
        lore.add(Msg.color("&7需要："));
        for (ItemSpec input : trade.inputs) {
            lore.add(Msg.color("  &8· &f" + specName(input) + " &7x" + input.amount));
        }
        lore.add(Msg.color("&7获得："));
        List<ItemStack> previews = previews(trade);
        if (previews.isEmpty()) {
            lore.add(Msg.color("  &8· &c" + (trade.convert ? "转换规则未命中" : "无法解析")));
        } else {
            for (ItemStack pv : previews) {
                lore.add(Msg.color("  &8· &f" + plugin.itemName(pv) + " &7x" + pv.getAmount()
                        + (trade.convert ? " &8(转换)" : "")));
            }
        }
        int batches = batches(trade);
        lore.add("");
        lore.add(Msg.color(batches > 0 ? "&7你当前可交易 &a" + batches + " &7次" : "&c物品不足"));
        lore.add(Msg.color("&e左键：自动放入所需物品"));
        if (first != null && !first.nativeRepresentable()) {
            lore.add(Msg.color("&8（按规则匹配）"));
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

    private static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color(name));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // ---------------- 选中与匹配 ----------------

    /** 当前要展示/执行的交易：优先选中的（且输入满足），否则第一个输入满足的。 */
    Trade currentTrade() {
        if (selected >= 0 && selected < merchant.trades.size()
                && batches(merchant.trades.get(selected)) > 0) {
            return merchant.trades.get(selected);
        }
        for (Trade trade : merchant.trades) {
            if (batches(trade) > 0) {
                return trade;
            }
        }
        return null;
    }

    /** 当前输入槽能满足该交易的份数（0 = 不匹配）。 */
    int batches(Trade trade) {
        int batches = Integer.MAX_VALUE;
        for (int i = 0; i < trade.inputs.size(); i++) {
            ItemStack item = inventory.getItem(inputSlot(i));
            if (item == null || item.getType().isAir()
                    || !trade.inputs.get(i).matches(item, plugin.soulSpace())) {
                return 0;
            }
            batches = Math.min(batches, item.getAmount() / trade.inputs.get(i).amount);
        }
        return batches == Integer.MAX_VALUE ? 0 : batches;
    }

    private boolean inputsEmpty() {
        for (int i = 0; i < inputCount(); i++) {
            ItemStack item = inventory.getItem(inputSlot(i));
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    /** 从玩家背包取出该交易一批所需物品，放入输入槽。 */
    void autoFill(Trade trade) {
        returnInputs();
        for (int i = 0; i < trade.inputs.size(); i++) {
            ItemSpec spec = trade.inputs.get(i);
            ItemStack taken = takeFromPlayer(spec, spec.amount);
            if (taken != null) {
                inventory.setItem(inputSlot(i), taken);
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
        for (int slot : INPUT_SLOTS) {
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

    static boolean isTradeSlot(int slot) {
        for (int candidate : TRADE_SLOTS) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }
}

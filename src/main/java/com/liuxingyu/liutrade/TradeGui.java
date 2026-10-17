package com.liuxingyu.liutrade;

import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.WindowCloseReason;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义交易界面（Sparrow UI 箱子窗口，54 格），稀疏布局，玻璃板做边框与分隔：
 * <pre>
 *   第 1 行：  · [上一页] · [关闭] · [怎么用] · [下一页] ·
 *   第 2~4 行：· [交易] · [交易] · [交易] · [交易] ·       （每页 12 笔，每笔之间玻璃板隔开）
 *   第 5~6 行：· [输入1] · [输入2] · … · [→] · [产出] ·   （输入数量按需显示，最多 6 个）
 * </pre>
 * 输入槽是真实的虚拟库存格：玩家把物品放进去，产出槽实时显示能换到什么，点产出槽完成交易。
 *
 * <p>界面由 Sparrow UI 托管：产出/交易图标通过 Signal 驱动重渲染，右击交易经
 * {@link net.momirealms.sparrow.ui.window.Window#navigate} 打开配方窗，ESC 原生返回本界面。</p>
 */
final class TradeGui {

    /** 交易列表：第 2~4 行的第 1/3/5/7 列（每行 4 笔，共 12 笔，之间是玻璃板）。 */
    static final int[] TRADE_SLOTS = {
            10, 12, 14, 16,
            19, 21, 23, 25,
            28, 30, 32, 34
    };
    /** 输入槽：第 5~6 行的第 1/3/5/7 列（最多 {@link Trade#MAX_INPUTS} 个，按商店实际需要显示）。 */
    static final int[] INPUT_SLOTS = {37, 39, 41, 43, 46, 48};
    static final int SLOT_PREV = 1;
    static final int SLOT_HELP = 4;
    static final int SLOT_NEXT = 7;
    static final int SLOT_ARROW = 50;
    static final int SLOT_OUT = 52;
    /** 关闭按钮放在最后一个槽位。 */
    static final int SLOT_CLOSE = 53;

    private final LiuTrade plugin;
    private final Player player;
    private final Merchant merchant;
    private final int[] slotToTradeIndex;
    private final int[] slotToInputIndex;
    private final MutableSignal<Integer> page;
    /** 本商店需要的输入槽数（构造后不变，避免每次渲染重复遍历交易）。 */
    private final int activeInputs;
    /** 输入槽：一个真实可由玩家放入/取出的虚拟库存。 */
    private final VirtualInventory inputs;
    /** 玩家背包引用（下部物品栏），自动放入时直接从这里取物。 */
    private final ReferencingInventory playerInv;
    private final NormalWindow window;
    private int selected = -1;
    // 会话内缓存：CE 物品解析、产出预览只算一次（渲染频繁发生时避免重复解析）。
    private final Map<Integer, ItemStack> iconCache = new HashMap<>();
    private final Map<Integer, List<ItemStack>> previewCache = new HashMap<>();
    private final Map<ItemSpec, String> specNameCache = new IdentityHashMap<>();

    TradeGui(LiuTrade plugin, Player player, Merchant merchant, int page) {
        this.plugin = plugin;
        this.player = player;
        this.merchant = merchant;
        this.slotToTradeIndex = new int[54];
        this.slotToInputIndex = new int[54];
        java.util.Arrays.fill(this.slotToTradeIndex, -1);
        java.util.Arrays.fill(this.slotToInputIndex, -1);
        for (int i = 0; i < TRADE_SLOTS.length; i++) {
            this.slotToTradeIndex[TRADE_SLOTS[i]] = i;
        }
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            this.slotToInputIndex[INPUT_SLOTS[i]] = i;
        }
        this.page = Signal.of(clampPage(page));
        this.activeInputs = computeInputCount();
        this.inputs = new VirtualInventory(INPUT_SLOTS.length);
        // 超出本商店需要的输入槽直接拒绝一切放入/取出。
        for (int i = activeInputs; i < INPUT_SLOTS.length; i++) {
            this.inputs.setAccessRule(i, context -> false);
        }
        this.playerInv = ReferencingInventory.fromPlayerStorageContents(player.getInventory());
        this.playerInv.operationPriority(OperationCategory.ADD, Integer.MAX_VALUE);
        this.playerInv.operationPriority(OperationCategory.COLLECT, Integer.MIN_VALUE);
        this.window = buildWindow();
    }

    // ---------------- 构建 ----------------

    private NormalWindow buildWindow() {
        NormalPane pane = Pane.empty(9, 6);
        pane.setBackgroundItem(filler());

        pane.setElement(SLOT_ARROW, Element.item(Item.simple(staticItem(Material.ARROW, "&7→ &f产出", null))));
        pane.setElement(SLOT_HELP, Element.item(helpItem()));
        pane.setElement(SLOT_PREV, Element.item(pageButton(false)));
        pane.setElement(SLOT_NEXT, Element.item(pageButton(true)));
        pane.setElement(SLOT_CLOSE, Element.item(closeItem()));
        pane.setElement(SLOT_OUT, Element.item(outputItem()));

        Item tradeIcon = tradeIconItem();
        for (int slot : TRADE_SLOTS) {
            pane.setElement(slot, Element.item(tradeIcon));
        }
        int active = activeInputs;
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            pane.setElement(INPUT_SLOTS[i], i < active
                    ? Element.inventory(inputs, i)
                    : Element.empty());
        }

        Pane lower = Pane.empty(9, 4);
        for (int i = 0; i < playerInv.size(); i++) {
            lower.setElement(i, Element.inventory(playerInv, i));
        }

        NormalWindow built = NormalWindow.builder()
                .setTitleSupplier(() -> Msg.component(merchant.displayTitle()))
                .setUpperPane(pane)
                .setLowerPane(lower)
                .setBackOnPlayerClose(false)
                .addSessionEndHandler(this::onSessionEnd)
                .build(player);
        built.bind(page, win -> win.updateTitle());
        return built;
    }

    /** 打开本界面（须在玩家所属线程调用）。 */
    void open() {
        window.open();
    }

    /** 右击交易 → 跳到配方界面（由 Sparrow 会话负责 ESC 返回）。 */
    void navigateTo(RecipeGui recipe) {
        window.navigate(recipe.window());
    }

    /** 会话真正结束时把输入槽剩余物品退回玩家（或被替换/退出时清理）。 */
    private void onSessionEnd(WindowCloseReason reason) {
        try {
            settleLeftover();
        } finally {
            plugin.forget(this);
        }
    }

    /** 玩家退出/插件停用兜底：按配置退回或丢弃输入槽，避免虚拟库存随会话消失。 */
    void releaseOnQuit() {
        settleLeftover();
    }

    /** 插件停用兜底；离线玩家也会尽量退回/掉落到最后位置。 */
    void releaseOnDisable() {
        settleLeftover();
    }

    private synchronized void settleLeftover() {
        if ("void".equalsIgnoreCase(plugin.config().leftoverOnClose)) {
            clearInputs();
        } else {
            returnInputs();
        }
    }

    // ---------------- 动态元素 ----------------

    private Item tradeIconItem() {
        return Item.builder()
                .setItemProvider(context -> tradeIconAt(context.windowSlot))
                .dependsOn(page, inputs.contentSignal())
                .addClickHandler(click -> {
                    int index = tradeIndexAt(click.windowSlot());
                    if (index < 0) {
                        return;
                    }
                    Trade trade = merchant.trades.get(index);
                    if (click.clickType().isRightClick()) {
                        plugin.openRecipeFromGui(click.player(), this, trade);
                    } else {
                        autoFill(trade, 0);
                    }
                })
                .build();
    }

    private Item outputItem() {
        return Item.builder()
                .setItemProvider(context -> outputIcon())
                .dependsOn(page, inputs.contentSignal())
                .addClickHandler(click -> {
                    Trade trade = currentTrade();
                    if (trade == null) {
                        return;
                    }
                    if (click.clickType().isRightClick()) {
                        plugin.openRecipeFromGui(click.player(), this, trade);
                    } else {
                        plugin.service().execute(click.player(), this, trade,
                                click.clickType().isShiftClick() ? batches(trade) : 1);
                    }
                })
                .build();
    }

    private Item pageButton(boolean next) {
        return Item.builder()
                .setItemProvider(context -> {
                    boolean enabled = next ? page.get() < pageCount() - 1 : page.get() > 0;
                    if (!enabled) {
                        return filler();
                    }
                    int target = next ? page.get() + 2 : page.get();
                    return staticItem(Material.SPECTRAL_ARROW,
                            next ? "&e下一页" : "&e上一页",
                            List.of("&7第 " + target + " / " + pageCount() + " 页"));
                })
                .dependsOn(page)
                .addClickHandler(click -> {
                    if (next) {
                        if (page.get() < pageCount() - 1) {
                            selected = -1;
                            page.update(value -> value + 1);
                        }
                    } else if (page.get() > 0) {
                        selected = -1;
                        page.update(value -> value - 1);
                    }
                })
                .build();
    }

    private Item closeItem() {
        return Item.builder()
                .setItemProviderConstant(staticItem(Material.BARRIER, "&c关闭", List.of("&7点击关闭界面")))
                .addClickHandler(click -> click.window().close())
                .build();
    }

    private Item helpItem() {
        return Item.builder()
                .setItemProvider(context -> help())
                .dependsOn(page)
                .build();
    }

    /** 顶部「怎么用」说明书（也带商店名与页码）。 */
    private ItemStack help() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color("&e&l" + merchant.displayName() + " &7· 怎么用"));
            meta.setLore(List.of(
                    Msg.color("&8第 " + (page.get() + 1) + " / " + pageCount() + " 页 · 共 " + merchant.trades.size() + " 种交易"),
                    Msg.color("&7① &f左键 &7任意交易：自动放入所需物品"),
                    Msg.color("&7② 也可以自己把物品放进下面的输入槽"),
                    Msg.color("&7③ &f产出格 &7出现物品后，&f左键 &7即可交易"),
                    Msg.color("&7④ &fShift+左键 &7产出格：一次交易全部"),
                    Msg.color("&7⑤ &f右键 &7交易/产出：查看交易配方"),
                    Msg.color("&7⑥ 左右 &f箭头 &7翻页")));
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
                return staticItem(Material.GRAY_STAINED_GLASS_PANE, "&7产出",
                        List.of(Msg.color("&7把物品放进左边的输入槽"),
                                Msg.color("&7或左键下方交易自动放入")));
            }
            return staticItem(Material.BARRIER, "&c没有匹配的交易",
                    List.of(Msg.color("&7这些物品换不到东西"),
                            Msg.color("&7请对照交易的「需要」")));
        }
        int batches = batches(trade);
        List<ItemStack> previews = previews(trade);
        boolean commands = trade.hasCommands();
        if (previews.isEmpty()) {
            // 纯指令交易才显示“指令产出”；convert + commands 且转换失败时应显示失败，
            // 否则界面会提示可以交易，点击却被 TradeService 拒绝。
            if (commands && !trade.hasItemOutput()) {
                return commandOutputIcon(trade, batches);
            }
            return staticItem(Material.BARRIER, "&c无法完成交易",
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
            if (commands) {
                lore.add(Msg.color("&7并执行 &f" + trade.commands.size() + " &7条指令"));
            }
            lore.add("");
            lore.add(Msg.color("&a左键 &7：交易 1 次"));
            lore.add(Msg.color("&aShift+左键 &7：全部 &f" + batches + " &7次"));
            meta.setLore(lore);
            result.setItemMeta(meta);
        }
        return result;
    }

    /** 纯指令产出（无物品）时的产出格。 */
    private ItemStack commandOutputIcon(Trade trade, int batches) {
        List<String> lore = new ArrayList<>();
        lore.add(Msg.color("&7需要："));
        for (ItemSpec input : trade.inputs) {
            lore.add(Msg.color("  &8· &f" + specName(input) + " &7x" + input.amount));
        }
        lore.add(Msg.color("&7获得："));
        for (String command : trade.commands) {
            lore.add(Msg.color("  &8· &f" + command));
        }
        lore.add("");
        lore.add(Msg.color("&a左键 &7：交易 1 次"));
        lore.add(Msg.color("&aShift+左键 &7：全部 &f" + batches + " &7次"));
        return staticItem(Material.COMMAND_BLOCK, "&a指令产出", lore);
    }

    /** 交易列表某槽位当前应显示的图标。 */
    private ItemStack tradeIconAt(int windowSlot) {
        int index = tradeIndexAt(windowSlot);
        if (index < 0) {
            return filler();
        }
        return tradeIcon(merchant.trades.get(index), index);
    }

    /** 交易列表里的一笔交易。 */
    private ItemStack tradeIcon(Trade trade, int index) {
        ItemSpec first = trade.inputs.isEmpty() ? null : trade.inputs.get(0);
        ItemStack icon = baseIcon(trade, index).clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        meta.setDisplayName(Msg.color("&f" + (trade.displayName != null ? trade.displayName : trade.name)));
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
            if (trade.hasCommands() && !trade.hasItemOutput()) {
                lore.add(Msg.color("  &8· &a执行 " + trade.commands.size() + " 条指令"));
            } else {
                lore.add(Msg.color("  &8· &c" + (trade.convert ? "转换规则未命中" : "无法解析")));
            }
        } else {
            for (ItemStack pv : previews) {
                lore.add(Msg.color("  &8· &f" + plugin.itemName(pv) + " &7x" + pv.getAmount()
                        + (trade.convert ? " &8(转换)" : "")));
            }
            if (trade.hasCommands()) {
                lore.add(Msg.color("  &8· &a执行 " + trade.commands.size() + " 条指令"));
            }
        }
        int batches = batches(trade);
        lore.add("");
        lore.add(Msg.color(batches > 0 ? "&7你当前可交易 &a" + batches + " &7次" : "&c物品不足"));
        lore.add(Msg.color("&e左键：自动放入所有可用物品"));
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

    /** 交易列表图标底物（缓存）：交易自定义 display → 首个产出（显式 outputs 或 convert 预览）→ 输入[0] → 商店图标 → 箱子。 */
    private ItemStack baseIcon(Trade trade, int index) {
        ItemStack cached = iconCache.get(index);
        if (cached != null) {
            return cached;
        }
        ItemStack sample = trade.display == null ? null : trade.display.iconSample(plugin.soulSpace());
        if (sample == null) {
            // 列表图标默认展示「能换到什么」（产出），而不是放进去的东西。
            List<ItemStack> produced = previews(trade);
            if (!produced.isEmpty()) {
                sample = produced.get(0);
            }
        }
        if (sample == null) {
            ItemSpec first = trade.inputs.isEmpty() ? null : trade.inputs.get(0);
            sample = first == null ? null : first.iconSample(plugin.soulSpace());
        }
        if (sample == null && merchant.icon != null) {
            sample = merchant.icon.iconSample(plugin.soulSpace());
        }
        ItemStack icon = sample != null ? sample.clone() : new ItemStack(Material.CHEST);
        icon.setAmount(1);
        iconCache.put(index, icon);
        return icon;
    }

    /** 产出预览（缓存，避免每次渲染重复跑转换规则）。 */
    private List<ItemStack> previews(Trade trade) {
        int index = merchant.trades.indexOf(trade);
        if (index < 0) {
            return plugin.service().previewAll(player, trade);
        }
        return previewCache.computeIfAbsent(index, key -> plugin.service().previewAll(player, trade));
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
        return staticItem(Material.GRAY_STAINED_GLASS_PANE, " ", null);
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
            ItemStack item = inputs.itemAt(i);
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
            ItemStack item = inputs.itemAt(i);
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    /** 左键交易时自动放入物品。 */
    void autoFill(Trade trade) {
        autoFill(trade, 1);
    }

    /**
     * 左键交易时自动放入物品。
     *
     * @param batches 期望放入的批数；{@code <= 0} 或 {@link Integer#MAX_VALUE} 表示尽量全部放入
     */
    void autoFill(Trade trade, int batches) {
        returnInputs();
        for (int i = 0; i < trade.inputs.size(); i++) {
            ItemSpec spec = trade.inputs.get(i);
            int need = batches >= Integer.MAX_VALUE || batches <= 0
                    ? Integer.MAX_VALUE
                    : spec.amount * batches;
            ItemStack taken = takeFromPlayer(spec, need);
            if (taken != null) {
                setInput(i, taken);
            }
        }
        selected = merchant.trades.indexOf(trade);
    }

    private ItemStack takeFromPlayer(ItemSpec spec, int need) {
        ItemStack result = null;
        for (int i = 0; i < playerInv.size() && need > 0; i++) {
            ItemStack item = playerInv.itemAt(i);
            if (item == null || item.getType().isAir() || !spec.matches(item, plugin.soulSpace())) {
                continue;
            }
            if (result == null) {
                // 一个槽位最多放该物品的最大堆叠数
                need = Math.min(need, item.getMaxStackSize());
                if (need <= 0) {
                    return null;
                }
            }
            int take = Math.min(need, item.getAmount());
            if (result == null) {
                result = item.clone();
                result.setAmount(take);
            } else {
                result.setAmount(result.getAmount() + take);
            }
            if (take >= item.getAmount()) {
                playerInv.setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - take);
                playerInv.setItem(i, item);
            }
            need -= take;
        }
        return result;
    }

    // ---------------- 输入槽访问（供 TradeService 使用） ----------------

    /** 读取第 {@code index} 个输入槽的物品。 */
    ItemStack inputAt(int index) {
        return inputs.itemAt(index);
    }

    /** 写入第 {@code index} 个输入槽的物品。 */
    void setInput(int index, ItemStack item) {
        inputs.setItem(index, item);
    }

    /** 把输入槽里剩下的物品退回玩家背包（放不下则原地掉落）。 */
    synchronized void returnInputs() {
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            ItemStack item = inputs.itemAt(i);
            if (item != null && !item.getType().isAir()) {
                giveOrDrop(player, item.clone());
                inputs.setItem(i, null);
            }
        }
    }

    /** 丢弃输入槽内全部物品（不退回）。 */
    synchronized void clearInputs() {
        for (int i = 0; i < INPUT_SLOTS.length; i++) {
            inputs.setItem(i, null);
        }
    }

    static void giveOrDrop(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir()) {
            return;
        }
        if (!player.isOnline()) {
            // 退出/停服兜底时背包可能不再保存：直接掉在最后位置，避免输入槽物品凭空消失。
            if (player.getWorld() != null && player.getLocation() != null) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    // ---------------- 槽位换算 ----------------

    Merchant merchant() {
        return merchant;
    }

    Player player() {
        return player;
    }

    int page() {
        return page.get();
    }

    int pageCount() {
        int pages = (merchant.trades.size() + TRADE_SLOTS.length - 1) / TRADE_SLOTS.length;
        return Math.max(1, Math.min(pages, plugin.config().maxPages));
    }

    private int clampPage(int page) {
        return Math.max(0, Math.min(page, pageCount() - 1));
    }

    /** 本商店一笔交易最多要几种物品（决定显示几个输入槽）。 */
    int inputCount() {
        return activeInputs;
    }

    private int computeInputCount() {
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
        int index = slot >= 0 && slot < slotToInputIndex.length ? slotToInputIndex[slot] : -1;
        return index >= 0 && index < inputCount();
    }

    /** 交易列表槽位对应的全局交易下标，非交易槽返回 -1。 */
    int tradeIndexAt(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= slotToTradeIndex.length) {
            return -1;
        }
        int local = slotToTradeIndex[windowSlot];
        if (local < 0) {
            return -1;
        }
        int index = page.get() * TRADE_SLOTS.length + local;
        return index >= 0 && index < merchant.trades.size() ? index : -1;
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

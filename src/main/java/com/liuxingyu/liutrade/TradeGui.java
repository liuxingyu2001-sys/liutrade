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
 * 自定义交易界面（Sparrow UI 箱子窗口，54 格），黑色外框 + 顶部色带 + 稀疏布局：
 * <pre>
 *   第 1 行： [信息] · [上一页] · [怎么用] · [下一页] ·
 *   第 2~4 行：· [交易] · [交易] · [交易] · [交易] ·       （每页 12 笔，每笔之间玻璃板隔开）
 *   第 5 行：  [放入物品] [输入1] · [输入2] · … [→] [产出] ·
 *   第 6 行：  · [输入3] · [输入4] …  [产出说明] · [关闭]
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
    /** convert 预览缓存：同一交易 + 同一输入物品时只跑一次转换，避免一屏图标重复转换。 */
    private int convertCacheTrade = -1;
    private ItemStack convertCacheInput;
    private List<ItemStack> convertCacheOutput = List.of();

    TradeGui(LiuTrade plugin, Player player, Merchant merchant, int page) {
        this.plugin = plugin;
        this.player = player;
        this.merchant = merchant;
        this.slotToTradeIndex = new int[54];
        java.util.Arrays.fill(this.slotToTradeIndex, -1);
        for (int i = 0; i < TRADE_SLOTS.length; i++) {
            this.slotToTradeIndex[TRADE_SLOTS[i]] = i;
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
        decorate(pane);
        pane.setElement(0, Element.item(infoItem()));
        pane.setElement(36, Element.item(Item.simple(inputLabel())));
        pane.setElement(51, Element.item(Item.simple(outputLabel())));

        pane.setElement(SLOT_ARROW, Element.item(Item.simple(staticItem(Material.ARROW, "&e→ &f交易",
                List.of("&7输入满足后此处显示结果", "&7点击右侧产出格完成交易")))));
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

    /** 黑色外框 + 顶部色带 + 输入区浅蓝色分隔，提升 54 格界面的层次感。 */
    private void decorate(NormalPane pane) {
        for (int slot = 0; slot < 54; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            if (row == 0 || row == 5 || col == 0 || col == 8) {
                pane.setElement(slot, Element.item(Item.simple(
                        staticItem(Material.BLACK_STAINED_GLASS_PANE, " ", null))));
            }
        }
        for (int slot : new int[]{2, 3, 5, 6}) {
            pane.setElement(slot, Element.item(Item.simple(
                    staticItem(Material.CYAN_STAINED_GLASS_PANE, " ", null))));
        }
        for (int slot : new int[]{38, 40, 42, 44, 45, 47, 49}) {
            pane.setElement(slot, Element.item(Item.simple(
                    staticItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ", null))));
        }
    }

    /** 左上角商店信息：图标、交易数、当前页、权限与快捷操作。 */
    private Item infoItem() {
        return Item.builder()
                .setItemProvider(context -> infoIcon())
                .dependsOn(page)
                .build();
    }

    private ItemStack infoIcon() {
        ItemStack icon = merchant.icon == null ? null : merchant.icon.iconSample(plugin.soulSpace());
        if (icon == null || icon.getType().isAir()) {
            icon = new ItemStack(Material.CHEST);
        } else {
            icon = icon.clone();
        }
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Msg.color("&6&l" + merchant.displayName()));
            List<String> lore = new ArrayList<>();
            lore.add(Msg.color("&8交易商店"));
            lore.add(Msg.color("&7交易：&f" + merchant.trades.size() + " &7笔 · 每页 &f" + TRADE_SLOTS.length + " &7笔"));
            lore.add(Msg.color("&7当前页：&f" + (page.get() + 1) + " / " + pageCount()));
            lore.add(Msg.color("&7输入槽：&f" + activeInputs + " &7个"));
            if (!merchant.permission.isBlank()) {
                lore.add(Msg.color("&7需要权限：&f" + merchant.permission));
            }
            lore.add(Msg.color("&8--------------------"));
            lore.add(Msg.color("&e左键交易 &7自动放入所需物品"));
            lore.add(Msg.color("&e右键交易 &7查看交易配方"));
            lore.add(Msg.color("&7也可以手动把物品放进下方输入槽"));
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack inputLabel() {
        return staticItem(Material.HOPPER, "&b放入物品",
                List.of("&7把交易需要的物品放进来", "&8左键交易可自动放入"));
    }

    private ItemStack outputLabel() {
        return staticItem(Material.LIME_STAINED_GLASS_PANE, "&a产出",
                List.of("&7满足条件后点击右边产出格", "&8右键可查看配方"));
    }

    private void playClick() {
        playSound("ui.button.click", 1.3f);
    }

    private void playSuccess() {
        playSound("entity.villager.yes", 1.2f);
    }

    private void playFail() {
        playSound("entity.villager.no", 1.0f);
    }

    /** 界面音效；玩家已离线（会话收尾）时静默跳过。 */
    private void playSound(String key, float pitch) {
        if (!player.isOnline()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), key, 0.6f, pitch);
        } catch (Throwable ignored) {
            // 音效资源名在不同版本可能缺失，不能因此中断点击逻辑。
        }
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
                        playClick();
                        plugin.openRecipeFromGui(click.player(), this, trade);
                    } else {
                        if (!locked(trade)) {
                            playClick();
                        }
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
                        playClick();
                        plugin.openRecipeFromGui(click.player(), this, trade);
                    } else {
                        if (!requirePermission(trade)) {
                            return;
                        }
                        boolean success = plugin.service().execute(click.player(), this, trade,
                                click.clickType().isShiftClick() ? batches(trade) : 1);
                        if (success) {
                            playSuccess();
                        } else {
                            playFail();
                        }
                    }
                })
                .build();
    }

    private Item pageButton(boolean next) {
        return Item.builder()
                .setItemProvider(context -> {
                    boolean enabled = next ? page.get() < pageCount() - 1 : page.get() > 0;
                    String label = next ? "下一页" : "上一页";
                    if (!enabled) {
                        return staticItem(Material.GRAY_STAINED_GLASS_PANE, "&8" + label,
                                List.of("&7已经是" + (next ? "最后一页" : "第一页")));
                    }
                    int target = next ? page.get() + 2 : page.get();
                    return staticItem(Material.SPECTRAL_ARROW, "&e" + label,
                            List.of("&7第 " + target + " / " + pageCount() + " 页", "&e左键翻页"));
                })
                .dependsOn(page)
                .addClickHandler(click -> {
                    if (next) {
                        if (page.get() < pageCount() - 1) {
                            playClick();
                            selected = -1;
                            page.update(value -> value + 1);
                        }
                    } else if (page.get() > 0) {
                        playClick();
                        selected = -1;
                        page.update(value -> value - 1);
                    }
                })
                .build();
    }

    private Item closeItem() {
        return Item.builder()
                .setItemProviderConstant(staticItem(Material.BARRIER, "&c关闭",
                        List.of("&7点击关闭界面", "&8或按 ESC")))
                .addClickHandler(click -> {
                    playClick();
                    click.window().close();
                })
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
            List<String> lore = new ArrayList<>();
            lore.add(Msg.color("&8第 " + (page.get() + 1) + " / " + pageCount() + " 页 · 共 " + merchant.trades.size() + " 种交易"));
            if (!merchant.permission.isBlank()) {
                lore.add(Msg.color("&7需要权限：&f" + merchant.permission));
            }
            lore.add(Msg.color("&8--------------------"));
            lore.add(Msg.color("&7① &f左键 &7任意交易：自动放入所需物品"));
            lore.add(Msg.color("&7② 也可以自己把物品放进下面的输入槽"));
            lore.add(Msg.color("&7③ &f产出格 &7出现物品后，&f左键 &7即可交易"));
            lore.add(Msg.color("&7④ &fShift+左键 &7交易/产出：尽量一次全部"));
            lore.add(Msg.color("&7⑤ &f右键 &7交易/产出：查看交易配方"));
            lore.add(Msg.color("&7⑥ 左右 &f箭头 &7翻页，&fESC &7关闭"));
            if (merchant.trades.size() > pageCount() * TRADE_SLOTS.length) {
                lore.add(Msg.color("&8提示：超出 max-pages 的交易未显示"));
            }
            meta.setLore(lore);
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
                                Msg.color("&7或左键上方任意交易自动放入")));
            }
            Trade lockedTrade = lockedMatch();
            if (lockedTrade != null) {
                return staticItem(Material.BARRIER, "&c需要权限",
                        List.of(Msg.color("&7这些物品可以交易，但缺少权限："),
                                Msg.color("&f" + lockedTrade.permission),
                                "",
                                Msg.color("&8右键交易可查看配方")));
            }
            Trade closest = closestTrade();
            if (closest != null) {
                List<String> lore = new ArrayList<>();
                lore.add(Msg.color("&7这笔交易还差："));
                for (int i = 0; i < closest.inputs.size(); i++) {
                    ItemSpec spec = closest.inputs.get(i);
                    int have = matchingAmount(i, spec);
                    if (have < spec.amount) {
                        lore.add(Msg.color("  &8· &f" + specName(spec) + " &7还差 &f" + (spec.amount - have)));
                    }
                }
                lore.add(Msg.color("&8--------------------"));
                lore.add(Msg.color("&e左键这笔交易 &7：自动补足"));
                return staticItem(Material.BARRIER, "&c还差一点", lore);
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
            List<String> lore = new ArrayList<>();
            lore.add(Msg.color(missingConvertSupport(trade)
                    ? "&c需要 SoulSpace 的转换规则（未安装或规则未配置）"
                    : trade.convert ? "&7转换规则没有命中当前物品" : "&c产出配置无法解析，请联系管理员"));
            lore.add(Msg.color("&8右键可查看配方"));
            return staticItem(Material.BARRIER, "&c无法完成交易", lore);
        }
        ItemStack result = previews.get(0).clone();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(Msg.color("&a✔ 可以交易 &7（可交易 &f" + batches + " &7次）"));
            lore.add(Msg.color("&8--------------------"));
            lore.add(Msg.color("&7需要："));
            for (ItemSpec input : trade.inputs) {
                lore.add(Msg.color("  &8· &f" + specName(input) + " &7x" + input.amount));
            }
            lore.add(Msg.color("&8--------------------"));
            lore.add(Msg.color("&7获得："));
            lore.add(Msg.color("  &8· &f" + plugin.itemName(result) + " &7x" + result.getAmount()));
            for (int i = 1; i < previews.size(); i++) {
                ItemStack extra = previews.get(i);
                lore.add(Msg.color("  &8· &f" + plugin.itemName(extra) + " &7x" + extra.getAmount()));
            }
            if (commands) {
                lore.add(Msg.color("&7并执行指令："));
                for (String command : trade.commands) {
                    lore.add(Msg.color("  &8· &a" + command));
                }
            }
            lore.add("");
            lore.add(Msg.color("&a左键 &7：交易 1 次"));
            lore.add(Msg.color("&aShift+左键 &7：全部 &f" + batches + " &7次"));
            lore.add(Msg.color("&8右键 &7查看配方"));
            meta.setLore(lore);
            result.setItemMeta(meta);
        }
        return result;
    }

    /** 纯指令产出（无物品）时的产出格；名字优先用条目上的 display_name，否则显示原生指令。 */
    private ItemStack commandOutputIcon(Trade trade, int batches) {
        List<String> lore = new ArrayList<>();
        lore.add(Msg.color("&a✔ 可以交易 &7（可交易 &f" + batches + " &7次）"));
        lore.add(Msg.color("&8--------------------"));
        lore.add(Msg.color("&7需要："));
        for (ItemSpec input : trade.inputs) {
            lore.add(Msg.color("  &8· &f" + specName(input) + " &7x" + input.amount));
        }
        lore.add(Msg.color("&8--------------------"));
        lore.add(Msg.color("&7获得："));
        if (trade.commandDisplayName != null) {
            // 配置了显示名就以它为准，不再把原生指令摆给玩家看。
            lore.add(Msg.color("  &8· &f" + trade.commandDisplayName));
        } else {
            for (String command : trade.commands) {
                lore.add(Msg.color("  &8· &f" + command));
            }
        }
        lore.add("");
        lore.add(Msg.color("&a左键 &7：交易 1 次"));
        lore.add(Msg.color("&aShift+左键 &7：全部 &f" + batches + " &7次"));
        lore.add(Msg.color("&8右键 &7查看配方"));
        return staticItem(Material.COMMAND_BLOCK, trade.commandName(), lore);
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
        boolean isLocked = locked(trade);
        int batches = batches(trade);
        // 有输入还不够：物品产出必须真的解析成功；纯指令交易则必须至少有一条有效指令。
        // 列表里每笔都会渲染，SoulSpace 能力检查放前面，避免对必然失败的交易反复跑转换。
        boolean needsRules = missingRuleSupport(trade);
        boolean needsConvert = missingConvertSupport(trade);
        List<ItemStack> previews = needsRules || needsConvert ? List.of() : previews(trade);
        boolean hasOutput = trade.hasItemOutput() ? !previews.isEmpty() : trade.hasCommands();
        boolean ready = batches > 0 && hasOutput && !needsRules && !needsConvert;
        String nameColor = index == selected ? "&e" : isLocked ? "&c" : "&f";
        meta.setDisplayName(Msg.color(nameColor + (trade.displayName != null ? trade.displayName : trade.name)));
        List<String> lore = new ArrayList<>();
        lore.add(Msg.color(isLocked
                ? "&c✖ 缺少权限"
                : ready ? "&a✔ 现在可交易 &7(" + batches + " 次)"
                : batches <= 0 ? "&7○ 材料不足" : "&c✖ 产出不可用"));
        StringBuilder kind = new StringBuilder("&8产出：");
        if (trade.hasItemOutput()) {
            kind.append(trade.convert ? "物品 &7(转换规则)" : "物品");
        }
        if (trade.hasCommands()) {
            kind.append(trade.hasItemOutput() ? " &8+ &7指令" : "指令");
        }
        lore.add(Msg.color(kind.toString()));
        lore.add(Msg.color("&8--------------------"));
        for (String line : trade.description) {
            lore.add(Msg.color(line));
        }
        // 有自定义说明时额外标一行指令产出，让玩家点开前就知道还会触发什么。
        if (trade.hasCommands() && !trade.description.isEmpty()) {
            lore.add(Msg.color("&8额外执行指令：" + trade.commandName()));
        }
        if (!trade.description.isEmpty()) {
            lore.add("");
        }
        lore.add(Msg.color("&7需要："));
        for (ItemSpec input : trade.inputs) {
            lore.add(Msg.color("  &8· &f" + specName(input) + " &7x" + input.amount));
        }
        lore.add(Msg.color("&7获得："));
        if (previews.isEmpty()) {
            if (!trade.hasCommands()) {
                lore.add(Msg.color("  &8· &c" + (trade.convert ? "转换规则未命中" : "无法解析")));
            }
        } else {
            for (ItemStack pv : previews) {
                lore.add(Msg.color("  &8· &f" + plugin.itemName(pv) + " &7x" + pv.getAmount()
                        + (trade.convert ? " &8(转换)" : "")));
            }
        }
        // 纯指令产出配了 display_name 时只显示名字，否则列出原生指令。
        if (trade.hasCommands()) {
            if (trade.commandDisplayName != null) {
                lore.add(Msg.color("  &8· &f" + trade.commandDisplayName));
            } else {
                for (String command : trade.commands) {
                    lore.add(Msg.color("  &8· &a" + command));
                }
            }
        }
        lore.add("");
        if (isLocked) {
            lore.add(Msg.color("&c需要权限：&f" + trade.permission));
            lore.add(Msg.color("&8解锁后才能交易"));
        } else if (ready) {
            lore.add(Msg.color("&e左键 &7自动放入可用物品"));
        } else if (needsRules) {
            lore.add(Msg.color("&c需要 SoulSpace 的规则匹配（未安装或版本过低）"));
        } else if (needsConvert) {
            lore.add(Msg.color("&c需要 SoulSpace 的转换规则（未安装或规则未配置）"));
        } else if (batches <= 0) {
            lore.add(Msg.color("&7放入所需物品后可交易"));
        } else {
            lore.add(Msg.color("&c产出配置不可用，请联系管理员"));
        }
        lore.add(Msg.color("&e右键 &7查看交易配方"));
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
        // convert 的预览随输入槽物品变化，不能按交易下标永久缓存。
        ItemStack cached = trade.convert ? null : iconCache.get(index);
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
        if (!trade.convert) {
            iconCache.put(index, icon);
        }
        return icon;
    }

    /**
     * 产出预览。
     *
     * <p>{@code convert} 交易必须用输入槽里**实际**的物品跑转换（例如输入带 PDC 的票券），
     * 否则会用样品去转换、预览成另一个物品或直接失败，和点击后的结果不一致；
     * 输入槽为空时才退回样品预览，让玩家先看到大致能换到什么。</p>
     */
    private List<ItemStack> previews(Trade trade) {
        if (trade.convert) {
            ItemStack input = inputAt(0);
            ItemSpec first = trade.inputs.isEmpty() ? null : trade.inputs.get(0);
            if (input != null && first != null && !input.getType().isAir()
                    && input.getAmount() >= first.amount
                    && first.matches(input, plugin.soulSpace())) {
                int index = merchant.trades.indexOf(trade);
                if (index >= 0 && index == convertCacheTrade && sameStack(convertCacheInput, input)) {
                    return convertCacheOutput;
                }
                // 输入槽里的实际物品已经匹配：转换失败就应当显示失败，不能退回样品预览
                // 让玩家误以为可以交易。
                List<ItemStack> actual = List.copyOf(plugin.service().produceAll(player, trade, input));
                convertCacheTrade = index;
                convertCacheInput = input.clone();
                convertCacheOutput = actual;
                return actual;
            }
            int index = merchant.trades.indexOf(trade);
            if (index < 0) {
                return plugin.service().previewAll(player, trade);
            }
            return previewCache.computeIfAbsent(index, key -> plugin.service().previewAll(player, trade));
        }
        int index = merchant.trades.indexOf(trade);
        if (index < 0) {
            return plugin.service().previewAll(player, trade);
        }
        return previewCache.computeIfAbsent(index, key -> plugin.service().previewAll(player, trade));
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        return first != null && second != null
                && first.getAmount() == second.getAmount()
                && first.isSimilar(second);
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
        if (name == null || name.isBlank()) {
            name = "未知物品";
        }
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
                && !locked(merchant.trades.get(selected))
                && batches(merchant.trades.get(selected)) > 0) {
            return merchant.trades.get(selected);
        }
        for (Trade trade : merchant.trades) {
            if (!locked(trade) && batches(trade) > 0) {
                return trade;
            }
        }
        return null;
    }

    /** 输入已满足、但当前玩家没权限的交易（用于产出格提示）。 */
    private Trade lockedMatch() {
        for (Trade trade : merchant.trades) {
            if (locked(trade) && batches(trade) > 0) {
                return trade;
            }
        }
        return null;
    }

    /** 输入槽已放了一部分时，找最接近完成的那笔交易（用于「还差多少」提示）。 */
    private Trade closestTrade() {
        Trade best = null;
        int bestMatched = 0;
        for (Trade trade : merchant.trades) {
            if (locked(trade)) {
                continue;
            }
            int matched = 0;
            for (int i = 0; i < trade.inputs.size(); i++) {
                if (matchingAmount(i, trade.inputs.get(i)) > 0) {
                    matched++;
                }
            }
            if (matched > bestMatched) {
                best = trade;
                bestMatched = matched;
            }
        }
        return best;
    }

    /** 输入槽 {@code slot} 里符合该规格的物品数量（不匹配返回 0）。 */
    private int matchingAmount(int slot, ItemSpec spec) {
        ItemStack item = inputs.itemAt(slot);
        if (item == null || item.getType().isAir() || !spec.matches(item, plugin.soulSpace())) {
            return 0;
        }
        return item.getAmount();
    }

    /** 当前玩家是否缺少该交易配置的 {@code permission}。 */
    boolean locked(Trade trade) {
        return trade.permission != null && !trade.permission.isBlank()
                && !player.hasPermission(trade.permission);
    }

    /** 交易前检查权限；没有权限时发提示并返回 false。 */
    private boolean requirePermission(Trade trade) {
        if (!locked(trade)) {
            return true;
        }
        playFail();
        player.sendMessage(plugin.prefixed("trade-locked", "%permission%", trade.permission));
        return false;
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

    /**
     * 当前交易是否需要（且没有）SoulSpace 规则匹配：显示界面上会提示管理员去装 SoulSpace，
     * 否则玩家只会看到「材料不足」，很容易被误当成配置错误。
     */
    private boolean missingRuleSupport(Trade trade) {
        SoulSpaceHook ss = plugin.soulSpace();
        if (ss != null && ss.canMatchRules()) {
            return false;
        }
        for (ItemSpec input : trade.inputs) {
            if (input.requiresRules()) {
                return true;
            }
        }
        return false;
    }

    /** 交易需要 SoulSpace 转换规则，但转换能力不可用（未安装 / 版本过低 / 规则加载失败）。 */
    private boolean missingConvertSupport(Trade trade) {
        if (!trade.convert) {
            return false;
        }
        SoulSpaceHook ss = plugin.soulSpace();
        return ss == null || !ss.canConvert();
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

    /**
     * 左键交易时自动放入物品。
     *
     * @param batches 期望放入的批数；{@code <= 0} 或 {@link Integer#MAX_VALUE} 表示尽量全部放入
     */
    void autoFill(Trade trade, int batches) {
        if (!requirePermission(trade)) {
            return;
        }
        boolean all = batches <= 0 || batches >= Integer.MAX_VALUE;
        for (int i = 0; i < trade.inputs.size(); i++) {
            ItemSpec spec = trade.inputs.get(i);
            ItemStack current = inputs.itemAt(i);
            if (current != null && !current.getType().isAir()
                    && !spec.matches(current, plugin.soulSpace())) {
                // 槽里是上一笔交易留下的不匹配物品：先退回玩家，再放本交易需要的物品。
                giveOrDrop(player, current.clone());
                setInput(i, null);
                current = null;
            }
            if (current != null && !current.getType().isAir()) {
                // 已放入的物品能复用就复用，只补差额，避免“退回 → 再取”在背包满时掉落。
                int cap = Math.max(1, current.getMaxStackSize());
                int missing = all ? cap - current.getAmount() : spec.amount * batches - current.getAmount();
                missing = Math.min(missing, cap - current.getAmount());
                if (missing > 0) {
                    // 规则匹配可能匹配到不同 PDC/名字的同材质物品；只合并与当前槽完全相同的物品。
                    ItemStack more = takeFromPlayer(spec, missing, current);
                    if (more != null) {
                        if (current.isSimilar(more)) {
                            current.setAmount(current.getAmount() + more.getAmount());
                            setInput(i, current);
                        } else {
                            giveOrDrop(player, more);
                        }
                    }
                }
                continue;
            }
            ItemStack taken = takeFromPlayer(spec, all ? Integer.MAX_VALUE : spec.amount * batches);
            if (taken != null) {
                setInput(i, taken);
            }
        }
        selected = merchant.trades.indexOf(trade);
    }

    private ItemStack takeFromPlayer(ItemSpec spec, int need) {
        return takeFromPlayer(spec, need, null);
    }

    /**
     * 从玩家背包取物品；{@code like} 非空时只取与它完全相同的物品，
     * 避免规则匹配（如任意票券）把不同 PDC/名字的物品合并进同一个输入槽。
     */
    private ItemStack takeFromPlayer(ItemSpec spec, int need, ItemStack like) {
        ItemStack result = null;
        for (int i = 0; i < playerInv.size() && need > 0; i++) {
            ItemStack item = playerInv.itemAt(i);
            if (item == null || item.getType().isAir() || !spec.matches(item, plugin.soulSpace())) {
                continue;
            }
            if (like != null && !like.isSimilar(item)) {
                continue;
            }
            if (result != null && !result.isSimilar(item)) {
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
                try {
                    giveOrDrop(player, item.clone());
                    inputs.setItem(i, null);
                } catch (Throwable failure) {
                    // 单个槽位退回失败不应阻断其余槽位（尤其是停服/退出兜底时）。
                    plugin.getLogger().warning("退回输入槽物品失败：" + failure);
                }
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

    Player player() {
        return player;
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

}

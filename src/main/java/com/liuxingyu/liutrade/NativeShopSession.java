package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次「原生村民交易界面」会话。
 *
 * <p>用 {@link Bukkit#createMerchant(String)} 创建服务端商户（CraftMerchantCustom），
 * 填充 {@link MerchantRecipe} 后由 {@code player.openMerchant(handle, true)} 打开客户端原生界面：
 * 左侧交易列表、滚动、点击自动填入、音效都是原生的。
 *
 * <p>村民界面按<b>物品</b>匹配（示例物品相似），因此输入取自物品规格的示例
 * （{@code library_id} 解析结果或 {@code material}）；含 {@code pdc}/{@code name}/规则字段、
 * 示例无法唯一代表的输入在原生模式下会被跳过（改用 {@code settings.gui-mode: custom}）。
 */
final class NativeShopSession {

    /** 同一笔不支持原生界面的交易只提示一次，避免每次打开商店都刷屏。 */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final LiuTrade plugin;
    private final Player player;
    private final Merchant shop;
    private final org.bukkit.inventory.Merchant handle;
    private final List<Trade> trades = new ArrayList<>();
    private final List<MerchantRecipe> recipes = new ArrayList<>();

    private NativeShopSession(LiuTrade plugin, Player player, Merchant shop,
                              org.bukkit.inventory.Merchant handle) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        this.handle = handle;
    }

    static NativeShopSession create(LiuTrade plugin, Player player, Merchant shop) {
        NativeShopSession session = new NativeShopSession(plugin, player, shop,
                Bukkit.createMerchant(Msg.plain(shop.displayTitle())));
        for (Trade trade : shop.trades) {
            if (!trade.permission.isBlank() && !player.hasPermission(trade.permission)) {
                continue;
            }
            MerchantRecipe recipe = session.build(trade);
            if (recipe != null) {
                session.trades.add(trade);
                session.recipes.add(recipe);
            }
        }
        session.handle.setRecipes(session.recipes);
        return session;
    }

    private MerchantRecipe build(Trade trade) {
        if (trade.inputs.size() > 2) {
            warnOnce(trade, "inputs",
                    "商店 " + shop.id + " 的交易「" + trade.name + "」有 " + trade.inputs.size()
                            + " 个输入，原生村民界面最多 2 个，已跳过（可改用 settings.gui-mode: custom）。");
            return null;
        }
        if (!trade.hasItemOutput()) {
            warnOnce(trade, "commands",
                    "商店 " + shop.id + " 的交易「" + trade.name
                            + "」只有指令产出，原生村民界面无法表示，已跳过（可改用 settings.gui-mode: custom）。");
            return null;
        }
        ItemStack result = plugin.service().preview(player, trade);
        if (result == null || result.getType().isAir()) {
            warnOnce(trade, "output",
                    "商店 " + shop.id + " 的交易「" + trade.name + "」产出无法解析，村民界面已跳过该笔。");
            return null;
        }
        MerchantRecipe recipe = new MerchantRecipe(result.clone(), Integer.MAX_VALUE);
        recipe.setIgnoreDiscounts(true);
        for (ItemSpec input : trade.inputs) {
            if (!input.nativeRepresentable()) {
                warnOnce(trade, "rules",
                        "商店 " + shop.id + " 的交易「" + trade.name
                                + "」输入含 pdc/name 等规则字段，原生村民界面无法按规则匹配，已跳过该笔"
                                + "（可改用 settings.gui-mode: custom）。");
                return null;
            }
            ItemStack sample = input.sample(plugin.soulSpace());
            if (sample == null) {
                warnOnce(trade, "sample",
                        "商店 " + shop.id + " 的交易「" + trade.name + "」输入无法解析，村民界面已跳过该笔。");
                return null;
            }
            sample = sample.clone();
            sample.setAmount(Math.min(input.amount, sample.getMaxStackSize()));
            recipe.addIngredient(sample);
        }
        return recipe;
    }

    private void warnOnce(Trade trade, String reason, String message) {
        if (WARNED.add(shop.id + "|" + trade.id + "|" + reason)) {
            plugin.getLogger().warning(message);
        }
    }

    org.bukkit.inventory.Merchant handle() {
        return handle;
    }

    Player player() {
        return player;
    }

    int size() {
        return trades.size();
    }

    /** 由 {@link MerchantInventory#getSelectedRecipeIndex()} 取配置里的交易。 */
    Trade tradeAt(int index) {
        return index >= 0 && index < trades.size() ? trades.get(index) : null;
    }

    /** 按真实输入槽计算产出；不匹配返回 null。 */
    ItemStack prepareResult(MerchantInventory inventory) {
        Trade trade = tradeAt(inventory.getSelectedRecipeIndex());
        if (trade == null || !matches(trade, inventory.getItem(0), inventory.getItem(1))) {
            return null;
        }
        return plugin.service().result(player, trade, inventory.getItem(0));
    }

    /**
     * 发放第一产出之外的其余产出。原生村民界面每笔只能给一个结果，
     * 因此配方只用第一个产出，其余在购买成功后补发。
     */
    void grantExtras(Player buyer, Trade trade, ItemStack subject) {
        List<ItemStack> all = plugin.service().produceAll(buyer, trade, subject);
        for (int i = 1; i < all.size(); i++) {
            TradeGui.giveOrDrop(buyer, all.get(i));
        }
    }

    /** 校验当前输入槽是否精确满足所选交易（数量足够、无多余物品）。 */
    boolean validate(MerchantInventory inventory) {
        Trade trade = tradeAt(inventory.getSelectedRecipeIndex());
        return trade != null && matches(trade, inventory.getItem(0), inventory.getItem(1));
    }

    /** 校验输入槽是否精确满足该交易。 */
    boolean matches(Trade trade, ItemStack in1, ItemStack in2) {
        ItemStack[] slots = {in1, in2};
        for (int i = 0; i < 2; i++) {
            ItemSpec spec = i < trade.inputs.size() ? trade.inputs.get(i) : null;
            ItemStack item = slots[i];
            boolean present = item != null && !item.getType().isAir();
            if (spec == null) {
                if (present) {
                    return false;
                }
                continue;
            }
            if (!present || !spec.matches(item, plugin.soulSpace()) || item.getAmount() < spec.amount) {
                return false;
            }
        }
        return true;
    }
}

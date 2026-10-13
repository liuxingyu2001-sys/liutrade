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
        ItemStack result = plugin.service().preview(player, trade);
        if (result == null || result.getType().isAir()) {
            plugin.getLogger().warning("商店 " + shop.id + " 的交易「" + trade.name
                    + "」产出无法解析，村民界面已跳过该笔。");
            return null;
        }
        MerchantRecipe recipe = new MerchantRecipe(result.clone(), Integer.MAX_VALUE);
        recipe.setIgnoreDiscounts(true);
        for (ItemSpec input : trade.inputs) {
            if (!input.nativeRepresentable()) {
                plugin.getLogger().warning("商店 " + shop.id + " 的交易「" + trade.name
                        + "」输入含 pdc/name 等规则字段，原生村民界面无法按规则匹配，已跳过该笔"
                        + "（可改用 settings.gui-mode: custom）。");
                return null;
            }
            ItemStack sample = input.sample(plugin.soulSpace());
            if (sample == null) {
                plugin.getLogger().warning("商店 " + shop.id + " 的交易「" + trade.name
                        + "」输入无法解析，村民界面已跳过该笔。");
                return null;
            }
            sample = sample.clone();
            sample.setAmount(Math.min(input.amount, sample.getMaxStackSize()));
            recipe.addIngredient(sample);
        }
        return recipe;
    }

    org.bukkit.inventory.Merchant handle() {
        return handle;
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

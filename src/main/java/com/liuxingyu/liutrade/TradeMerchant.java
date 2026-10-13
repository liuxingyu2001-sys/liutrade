package com.liuxingyu.liutrade;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 原生村民交易界面：实现 {@link org.bukkit.inventory.Merchant}，由
 * {@code player.openMerchant(this, true)} 打开客户端原生的村民交易界面
 * （左侧交易列表、滚动、点击自动放入、音效均由客户端原生处理）。
 *
 * <p>村民界面按<b>物品</b>匹配（与服务端 {@link MerchantRecipe} 的示例物品相似），
 * 因此输入取自物品规格的示例（{@code library_id} 解析结果或 {@code material}）；
 * 仅靠 {@code pdc}/{@code name}/规则字段才能区分、且示例无法代表的输入，请改用自定义界面
 * （{@code settings.gui-mode: custom}）。
 */
final class TradeMerchant implements org.bukkit.inventory.Merchant {

    private final LiuTrade plugin;
    private final Player player;
    private final Merchant shop;
    private final List<Trade> trades = new ArrayList<>();
    private final List<MerchantRecipe> recipes = new ArrayList<>();

    TradeMerchant(LiuTrade plugin, Player player, Merchant shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        for (Trade trade : shop.trades) {
            if (!trade.permission.isBlank() && !player.hasPermission(trade.permission)) {
                continue;
            }
            MerchantRecipe recipe = build(trade);
            if (recipe != null) {
                trades.add(trade);
                recipes.add(recipe);
            }
        }
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
        recipe.setPriceMultiplier(0f);
        for (ItemSpec input : trade.inputs) {
            if (!input.nativeRepresentable()) {
                plugin.getLogger().warning("商店 " + shop.id + " 的交易「" + trade.name
                        + "」输入含 pdc/name 等规则字段，原生村民界面无法按规则匹配，已跳过该笔（可改用 settings.gui-mode: custom）。");
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

    /** 由 {@link MerchantRecipe} 反查配置里的交易。 */
    Trade tradeFor(MerchantRecipe recipe) {
        int index = recipes.indexOf(recipe);
        return index >= 0 && index < trades.size() ? trades.get(index) : null;
    }

    Trade tradeAt(int index) {
        return index >= 0 && index < trades.size() ? trades.get(index) : null;
    }

    /** 按真实输入槽计算产出；不匹配返回 null。 */
    ItemStack prepareResult(MerchantInventory inventory) {
        Trade trade = tradeAt(inventory.getSelectedRecipeIndex());
        if (trade == null) {
            return null;
        }
        ItemStack in1 = inventory.getItem(0);
        ItemStack in2 = inventory.getItem(1);
        if (!matches(trade, in1, in2)) {
            return null;
        }
        return plugin.service().result(player, trade, in1);
    }

    /** 校验当前输入槽是否精确满足该交易（数量足够、无多余物品）。 */
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

    boolean validate(MerchantInventory inventory, MerchantRecipe recipe) {
        Trade trade = tradeFor(recipe);
        return trade != null && matches(trade, inventory.getItem(0), inventory.getItem(1));
    }

    // ---------------- Merchant ----------------

    @Override
    public List<MerchantRecipe> getRecipes() {
        return Collections.unmodifiableList(recipes);
    }

    @Override
    public void setRecipes(List<MerchantRecipe> list) {
        recipes.clear();
        recipes.addAll(list);
    }

    @Override
    public MerchantRecipe getRecipe(int index) {
        return recipes.get(index);
    }

    @Override
    public void setRecipe(int index, MerchantRecipe recipe) {
        recipes.set(index, recipe);
    }

    @Override
    public int getRecipeCount() {
        return recipes.size();
    }

    @Override
    public boolean isTrading() {
        return false;
    }

    @Override
    public HumanEntity getTrader() {
        return player;
    }
}

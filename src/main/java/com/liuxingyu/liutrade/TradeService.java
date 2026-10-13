package com.liuxingyu.liutrade;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 交易执行：校验输入、扣物品、发产出（显式产出或 SoulSpace 转换）。 */
final class TradeService {

    private final LiuTrade plugin;

    TradeService(LiuTrade plugin) {
        this.plugin = plugin;
    }

    /** 预览一笔交易的第一个产出（按 1 次）；无法产出返回 null。 */
    ItemStack preview(Player player, Trade trade) {
        List<ItemStack> all = previewAll(player, trade);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 预览一笔交易的全部产出（按 1 次）。 */
    List<ItemStack> previewAll(Player player, Trade trade) {
        return produceAll(player, trade, null);
    }

    /** 根据真实输入物品计算单次产出的第一个（村民界面用）。 */
    ItemStack result(Player player, Trade trade, ItemStack input) {
        List<ItemStack> all = produceAll(player, trade, input);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 完成 {@code times} 次交易。
     *
     * @param times 期望次数；内部按输入槽实际可完成次数收敛
     * @return 是否成功
     */
    boolean execute(Player player, TradeGui gui, Trade trade, int times) {
        if (!trade.permission.isBlank() && !player.hasPermission(trade.permission)) {
            player.sendMessage(plugin.prefixed("no-permission"));
            return false;
        }
        int available = gui.batches(trade);
        times = Math.min(times, available);
        if (times <= 0) {
            player.sendMessage(plugin.prefixed("trade-fail"));
            return false;
        }
        Inventory inventory = gui.getInventory();
        List<ItemStack> outputs = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            ItemStack subject = inventory.getItem(TradeGui.SLOT_IN1);
            List<ItemStack> produced = produceAll(player, trade, subject);
            if (produced.isEmpty()) {
                player.sendMessage(plugin.prefixed(trade.convert ? "soulspace-missing" : "trade-fail"));
                return false;
            }
            outputs.addAll(produced);
        }
        for (int i = 0; i < trade.inputs.size(); i++) {
            int slot = i == 0 ? TradeGui.SLOT_IN1 : TradeGui.SLOT_IN2;
            ItemStack item = inventory.getItem(slot);
            int remaining = item.getAmount() - trade.inputs.get(i).amount * times;
            if (remaining <= 0) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(remaining);
                inventory.setItem(slot, item);
            }
        }
        for (ItemStack output : outputs) {
            TradeGui.giveOrDrop(player, output);
        }
        if (plugin.config().feedback) {
            player.sendMessage(plugin.prefixed("trade-success",
                    "%name%", Msg.color(trade.name),
                    "%times%", String.valueOf(times)));
        }
        gui.render();
        return true;
    }

    /**
     * 计算一笔交易一次的全部产出（按顺序）：显式 {@code outputs} 逐条构建，
     * 或 {@code convert: true} 时经 SoulSpace 转换规则。
     */
    List<ItemStack> produceAll(Player player, Trade trade, ItemStack subject) {
        List<ItemStack> results = new ArrayList<>();
        if (trade.outputs != null) {
            for (ItemSpec spec : trade.outputs) {
                ItemStack sample = spec.sample(plugin.soulSpace());
                if (sample == null || sample.getType().isAir()) {
                    continue;
                }
                ItemStack output = sample.clone();
                output.setAmount(Math.min(spec.amount, output.getMaxStackSize()));
                results.add(output);
            }
            return results;
        }
        ItemStack input = subject;
        if (input == null || input.getType().isAir()
                || !trade.inputs.get(0).matches(input, plugin.soulSpace())) {
            input = trade.inputs.get(0).sample(plugin.soulSpace());
        }
        if (input == null || input.getType().isAir()) {
            return results;
        }
        ItemStack convertedInput = input.clone();
        convertedInput.setAmount(Math.min(trade.inputs.get(0).amount, convertedInput.getMaxStackSize()));
        ItemStack converted = plugin.soulSpace().convert(player.getUniqueId(), convertedInput).orElse(null);
        if (converted != null && !converted.getType().isAir()) {
            results.add(converted);
        }
        return results;
    }
}

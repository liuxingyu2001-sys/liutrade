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

    /** 预览一笔交易的产出（按 1 次）。无法产出返回 null。 */
    ItemStack preview(Player player, Trade trade) {
        return produce(player, trade, null);
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
            ItemStack output = produce(player, trade, subject);
            if (output == null || output.getType().isAir()) {
                player.sendMessage(plugin.prefixed(trade.convert ? "soulspace-missing" : "trade-fail"));
                return false;
            }
            outputs.add(output);
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

    private ItemStack produce(Player player, Trade trade, ItemStack subject) {
        if (trade.outputs != null) {
            ItemSpec spec = trade.outputs.get(0);
            ItemStack sample = spec.sample(plugin.soulSpace());
            if (sample == null) {
                return null;
            }
            ItemStack output = sample.clone();
            output.setAmount(Math.min(spec.amount, output.getMaxStackSize()));
            return output;
        }
        ItemStack input = subject;
        if (input == null || input.getType().isAir()) {
            input = trade.inputs.get(0).sample(plugin.soulSpace());
        } else if (!trade.inputs.get(0).matches(input, plugin.soulSpace())) {
            input = trade.inputs.get(0).sample(plugin.soulSpace());
        }
        if (input == null) {
            return null;
        }
        ItemStack convertedInput = input.clone();
        convertedInput.setAmount(Math.min(trade.inputs.get(0).amount, convertedInput.getMaxStackSize()));
        return plugin.soulSpace().convert(player.getUniqueId(), convertedInput).orElse(null);
    }
}

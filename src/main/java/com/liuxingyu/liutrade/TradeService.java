package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
        List<ItemStack> outputs = new ArrayList<>();
        if (trade.hasItemOutput()) {
            // 输入扣除在下面统一进行；同一份输入重复 times 次的结果是确定的，
            // 只构建一次再按次数复制，避免 shift 交易时反复跑转换规则/解析物品库。
            List<ItemStack> produced = produceAll(player, trade, gui.inputAt(0));
            if (produced.isEmpty()) {
                player.sendMessage(plugin.prefixed(trade.convert ? "soulspace-missing" : "trade-fail"));
                return false;
            }
            for (int i = 0; i < times; i++) {
                for (ItemStack item : produced) {
                    outputs.add(item.clone());
                }
            }
        }
        for (int i = 0; i < trade.inputs.size(); i++) {
            ItemStack item = gui.inputAt(i);
            if (item == null) {
                continue;
            }
            int remaining = item.getAmount() - trade.inputs.get(i).amount * times;
            if (remaining <= 0) {
                gui.setInput(i, null);
            } else {
                item.setAmount(remaining);
                gui.setInput(i, item);
            }
        }
        for (ItemStack output : outputs) {
            TradeGui.giveOrDrop(player, output);
        }
        runCommands(player, trade, times);
        if (plugin.config().feedback) {
            player.sendMessage(plugin.prefixed("trade-success",
                    "%name%", Msg.color(trade.name),
                    "%times%", String.valueOf(times)));
        }
        return true;
    }

    /**
     * 执行一笔交易配置的 commands（以控制台身份）。占位符：{@code %player%} / {@code %uuid%} / {@code %times%}。
     *
     * <p>带 {@code %times%} 的指令只执行一次并填入总次数（适合 {@code /give ... %times%}）；
     * 不带的按次数执行多遍。
     *
     * @return 执行失败的指令条数
     */
    int runCommands(Player player, Trade trade, int times) {
        if (trade == null || !trade.hasCommands()) {
            return 0;
        }
        int failed = 0;
        CommandSender console = Bukkit.getConsoleSender();
        for (String raw : trade.commands) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            int rounds = raw.contains("%times%") ? 1 : Math.max(1, times);
            for (int i = 0; i < rounds; i++) {
                String command = raw
                        .replace("%player%", player.getName())
                        .replace("%uuid%", player.getUniqueId().toString())
                        .replace("%times%", String.valueOf(times));
                try {
                    if (!Bukkit.dispatchCommand(console, command)) {
                        failed++;
                        plugin.getLogger().warning("交易 " + trade.id + " 的指令执行失败（返回 false）：" + command);
                    }
                } catch (Throwable t) {
                    failed++;
                    plugin.getLogger().warning("交易 " + trade.id + " 的指令执行异常：" + command + "（" + t + "）");
                }
            }
        }
        return failed;
    }

    /**
     * 计算一笔交易一次的全部产出（按顺序）：显式 {@code outputs} 逐条构建，
     * 或 {@code convert: true} 时经 SoulSpace 转换规则。
     */
    List<ItemStack> produceAll(Player player, Trade trade, ItemStack subject) {
        List<ItemStack> results = new ArrayList<>();
        if (!trade.hasItemOutput()) {
            // 纯指令产出：没有物品结果（避免误走 convert 转换规则）。
            return results;
        }
        if (trade.outputs != null) {
            for (ItemSpec spec : trade.outputs) {
                ItemStack sample = spec.sample(plugin.soulSpace());
                if (sample == null || sample.getType().isAir()) {
                    continue;
                }
                // 配置 amount 可能大于单堆叠上限（例如 128 个），按最大堆叠拆成多组，
                // 否则原生界面/自定义界面都只会发第一组，玩家少拿产出。
                addSplit(results, sample, spec.amount);
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
            addSplit(results, converted, converted.getAmount());
        }
        return results;
    }

    /** 按物品最大堆叠拆成多组；sample 本身不会被修改。 */
    static void addSplit(List<ItemStack> results, ItemStack sample, int amount) {
        int max = Math.max(1, sample.getMaxStackSize());
        for (int take : splitAmounts(amount, max)) {
            ItemStack stack = sample.clone();
            stack.setAmount(take);
            results.add(stack);
        }
    }

    /** 纯数量拆分（便于单测，不依赖 Bukkit ItemStack 注册表）。 */
    static List<Integer> splitAmounts(int amount, int maxStackSize) {
        List<Integer> out = new ArrayList<>();
        int remaining = amount;
        int max = Math.max(1, maxStackSize);
        while (remaining > 0) {
            int take = Math.min(remaining, max);
            out.add(take);
            remaining -= take;
        }
        return out;
    }
}

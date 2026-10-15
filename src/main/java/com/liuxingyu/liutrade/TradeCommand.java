package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** /liutrade 命令（控制台可用；open 支持为指定玩家打开）。 */
final class TradeCommand implements CommandExecutor, TabCompleter {

    private final LiuTrade plugin;

    TradeCommand(LiuTrade plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("liutrade.admin.reload")) {
                sender.sendMessage(plugin.prefixed("no-permission"));
                return true;
            }
            plugin.reloadAll();
            sender.sendMessage(plugin.prefixed("reloaded", "%count%", String.valueOf(plugin.manager().size()))
                    + Msg.color(" &7(界面模式：" + plugin.config().guiMode + ")"));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            list(sender);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("item")) {
            return item(sender);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            return give(sender, args);
        }

        String id = null;
        String playerArg = null;
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (args.length >= 2) {
                id = args[1];
            }
            if (args.length >= 3) {
                playerArg = args[2];
            }
        } else {
            id = args[0];
            if (args.length >= 2) {
                playerArg = args[1];
            }
        }

        Player target = resolveTarget(sender, playerArg);
        if (target == null) {
            return true;
        }
        if (id == null) {
            List<Merchant> all = plugin.merchants();
            if (all.isEmpty()) {
                sender.sendMessage(plugin.prefixed("no-trades"));
            } else if (all.size() == 1) {
                openFor(sender, target, all.get(0));
            } else {
                list(sender);
            }
            return true;
        }
        Merchant merchant = plugin.manager().get(id);
        if (merchant == null) {
            sender.sendMessage(plugin.prefixed("merchant-not-found", "%name%", id));
            return true;
        }
        openFor(sender, target, merchant);
        return true;
    }

    /** 把手持物品导出为 bytes: 配置行（用于全保真自定义产出）。 */
    private boolean item(CommandSender sender) {
        if (!sender.hasPermission("liutrade.admin")) {
            sender.sendMessage(plugin.prefixed("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Msg.color("&c该命令只能由玩家执行。"));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(Msg.color("&c主手没有物品。"));
            return true;
        }
        String base64 = Base64.getEncoder().encodeToString(hand.serializeAsBytes());
        player.sendMessage(Msg.color("&a把下面这行贴到交易的 outputs 里作为产出（同样已输出到控制台）："));
        player.sendMessage(Msg.color("&f- bytes: \"" + base64 + "\""));
        plugin.getLogger().info("[" + player.getName() + "] 主手物品导出：- bytes: \"" + base64 + "\"");
        return true;
    }

    /** /liutrade give <商店> <交易> [玩家] [份数]：把交易的配置产出直接发给玩家。 */
    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("liutrade.admin.give")) {
            sender.sendMessage(plugin.prefixed("no-permission"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Msg.color("&c用法：&f/liutrade give <商店> <交易> [玩家] [份数]"));
            return true;
        }
        Merchant merchant = plugin.manager().get(args[1]);
        if (merchant == null) {
            sender.sendMessage(plugin.prefixed("merchant-not-found", "%name%", args[1]));
            return true;
        }
        Trade trade = merchant.findTrade(args[2]);
        if (trade == null) {
            sender.sendMessage(plugin.prefixed("trade-not-found", "%id%", args[2]));
            return true;
        }
        if (!trade.hasItemOutput()) {
            sender.sendMessage(Msg.color("&c这笔交易没有物品产出（只有指令），不能 give；请直接执行交易。"));
            return true;
        }
        Player target = resolveTarget(sender, args.length >= 4 ? args[3] : null);
        if (target == null) {
            return true;
        }
        int count = 1;
        if (args.length >= 5) {
            try {
                count = Integer.parseInt(args[4]);
            } catch (NumberFormatException e) {
                count = 0;
            }
            if (count <= 0) {
                sender.sendMessage(Msg.color("&c份数必须是正整数。"));
                return true;
            }
            count = Math.min(count, 2304);
        }
        List<ItemStack> outputs = plugin.service().produceAll(target, trade, null);
        if (outputs.isEmpty()) {
            sender.sendMessage(plugin.prefixed("trade-fail"));
            return true;
        }
        for (ItemStack output : outputs) {
            giveStacked(target, output, output.getAmount() * count);
        }
        sender.sendMessage(Msg.color("&a已给 &f" + target.getName() + " &a发放 &f" + merchant.id + " / "
                + (trade.key == null || trade.key.isBlank() ? trade.name : trade.key)
                + " &a的产出 x" + count + "。"));
        return true;
    }

    /** 按最大堆叠数拆成多组后发给玩家（放不下则原地掉落）。 */
    private static void giveStacked(Player target, ItemStack item, int total) {
        int max = Math.max(1, item.getMaxStackSize());
        while (total > 0) {
            ItemStack stack = item.clone();
            int take = Math.min(total, max);
            stack.setAmount(take);
            TradeGui.giveOrDrop(target, stack);
            total -= take;
        }
    }

    /** 解析「为谁打开」：控制台必须指定玩家；玩家为他人打开需要 liutrade.admin。 */
    private Player resolveTarget(CommandSender sender, String name) {
        if (name == null) {
            if (sender instanceof Player player) {
                return player;
            }
            sender.sendMessage(Msg.color("&c控制台需要指定玩家：&f/liutrade open <商店> <玩家>"));
            return null;
        }
        if (sender instanceof Player && !sender.hasPermission("liutrade.admin")) {
            sender.sendMessage(plugin.prefixed("no-permission"));
            return null;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Msg.color("&c玩家不在线：&f" + name));
            return null;
        }
        return target;
    }

    private void openFor(CommandSender sender, Player target, Merchant merchant) {
        if (!target.hasPermission("liutrade.use")) {
            sender.sendMessage(plugin.prefixed("no-permission"));
            return;
        }
        plugin.openMerchant(target, merchant, 0);
        if (!sender.equals(target)) {
            sender.sendMessage(Msg.color("&a已为 &f" + target.getName() + " &a打开商店 &f" + merchant.id));
        }
    }

    private void list(CommandSender sender) {
        List<Merchant> all = plugin.merchants();
        if (all.isEmpty()) {
            sender.sendMessage(plugin.prefixed("no-trades"));
            return;
        }
        sender.sendMessage(Msg.color("&6&l交易商店 &7(" + all.size() + ")"));
        for (Merchant merchant : all) {
            sender.sendMessage(Msg.color(" &8- &f" + merchant.id + " &7" + Msg.plain(merchant.displayName())
                    + " &8(" + merchant.trades.size() + " 笔) &e/liutrade open " + merchant.id));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String option : new String[]{"open", "list", "reload", "item", "give"}) {
                if (option.startsWith(args[0].toLowerCase())) {
                    out.add(option);
                }
            }
            for (Merchant merchant : plugin.merchants()) {
                if (merchant.id.toLowerCase().startsWith(args[0].toLowerCase())) {
                    out.add(merchant.id);
                }
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("give")) {
                for (Merchant merchant : plugin.merchants()) {
                    if (merchant.id.toLowerCase().startsWith(args[1].toLowerCase())) {
                        out.add(merchant.id);
                    }
                }
            } else if (sender.hasPermission("liutrade.admin")) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        out.add(player.getName());
                    }
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give") && sender.hasPermission("liutrade.admin.give")) {
            Merchant merchant = plugin.manager().get(args[1]);
            if (merchant != null) {
                for (Trade trade : merchant.trades) {
                    if (trade.key != null && trade.key.toLowerCase().startsWith(args[2].toLowerCase())) {
                        out.add(trade.key);
                    }
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give") && sender.hasPermission("liutrade.admin.give")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[3].toLowerCase())) {
                    out.add(player.getName());
                }
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("give") && sender.hasPermission("liutrade.admin.give")) {
            for (String n : new String[]{"1", "8", "16", "64"}) {
                if (n.startsWith(args[4])) {
                    out.add(n);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("open")
                && sender.hasPermission("liutrade.admin")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[2].toLowerCase())) {
                    out.add(player.getName());
                }
            }
        }
        return out;
    }
}

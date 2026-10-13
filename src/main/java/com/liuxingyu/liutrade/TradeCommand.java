package com.liuxingyu.liutrade;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /liutrade 命令。 */
final class TradeCommand implements CommandExecutor, TabCompleter {

    private final LiuTrade plugin;

    TradeCommand(LiuTrade plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家执行。");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (args.length >= 2) {
                open(player, args[1]);
                return true;
            }
            List<Merchant> all = plugin.merchants();
            if (all.isEmpty()) {
                player.sendMessage(plugin.prefixed("no-trades"));
            } else if (all.size() == 1) {
                plugin.openMerchant(player, all.get(0), 0);
            } else {
                list(player);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            list(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!player.hasPermission("liutrade.admin.reload")) {
                player.sendMessage(plugin.prefixed("no-permission"));
                return true;
            }
            plugin.reloadMerchants();
            player.sendMessage(plugin.prefixed("reloaded", "%count%", String.valueOf(plugin.manager().size())));
            return true;
        }
        open(player, args[0]);
        return true;
    }

    private void open(Player player, String id) {
        if (!player.hasPermission("liutrade.use")) {
            player.sendMessage(plugin.prefixed("no-permission"));
            return;
        }
        Merchant merchant = plugin.manager().get(id);
        if (merchant == null) {
            player.sendMessage(plugin.prefixed("merchant-not-found", "%name%", id));
            return;
        }
        plugin.openMerchant(player, merchant, 0);
    }

    private void list(Player player) {
        List<Merchant> all = plugin.merchants();
        if (all.isEmpty()) {
            player.sendMessage(plugin.prefixed("no-trades"));
            return;
        }
        player.sendMessage(Msg.color("&6&l交易商店 &7(" + all.size() + ")"));
        for (Merchant merchant : all) {
            player.sendMessage(Msg.color(" &8- &f" + merchant.id + " &7" + Msg.plain(merchant.displayName())
                    + " &8(" + merchant.trades.size() + " 笔) &e/liutrade open " + merchant.id));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String option : new String[]{"open", "list", "reload"}) {
                if (option.startsWith(args[0].toLowerCase())) {
                    out.add(option);
                }
            }
            for (Merchant merchant : plugin.merchants()) {
                if (merchant.id.toLowerCase().startsWith(args[0].toLowerCase())) {
                    out.add(merchant.id);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            for (Merchant merchant : plugin.merchants()) {
                if (merchant.id.toLowerCase().startsWith(args[1].toLowerCase())) {
                    out.add(merchant.id);
                }
            }
        }
        return out;
    }
}

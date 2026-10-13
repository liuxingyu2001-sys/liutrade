package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
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
            plugin.reloadMerchants();
            sender.sendMessage(plugin.prefixed("reloaded", "%count%", String.valueOf(plugin.manager().size())));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            list(sender);
            return true;
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
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("open")) {
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

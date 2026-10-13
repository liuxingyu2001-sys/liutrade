package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** LiuTrade 主类。 */
public final class LiuTrade extends JavaPlugin {

    private final TradeConfig config = new TradeConfig();
    private final Map<UUID, TradeGui> open = new HashMap<>();
    private MerchantManager manager;
    private SoulSpaceHook soulSpace;
    private TradeService service;
    private long fingerprint;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        copyExample();
        config.load(getConfig());
        soulSpace = new SoulSpaceHook(this);
        if (!soulSpace.connect()) {
            if (Bukkit.getPluginManager().getPlugin("SoulSpace") != null) {
                getLogger().warning("检测到 SoulSpace 但 API 连接失败，library_id / 规则匹配可能不可用。");
            } else {
                getLogger().info("未检测到 SoulSpace：仅支持原版材质交易（library_id / 规则字段退化为材质匹配）。");
            }
        }
        manager = new MerchantManager(this);
        service = new TradeService(this);
        manager.load(merchantsRoot());
        fingerprint = fingerprint();
        getServer().getPluginManager().registerEvents(new TradeListener(this), this);
        PluginCommand command = getCommand("liutrade");
        if (command != null) {
            TradeCommand executor = new TradeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        new CitizensHook(this).register();
        if (config.autoReload) {
            long period = config.pollIntervalTicks;
            Bukkit.getScheduler().runTaskTimer(this, this::poll, period, period);
        }
        getLogger().info("LiuTrade 已启用。");
    }

    @Override
    public void onDisable() {
        for (TradeGui gui : new ArrayList<>(open.values())) {
            if (gui.player().isOnline()) {
                gui.returnInputs();
            } else {
                gui.getInventory().setItem(TradeGui.SLOT_IN1, null);
                gui.getInventory().setItem(TradeGui.SLOT_IN2, null);
            }
        }
        open.clear();
    }

    private void copyExample() {
        File dir = new File(getDataFolder(), "merchants");
        File example = new File(dir, "_example.yml");
        if (!example.exists() && getResource("merchants/_example.yml") != null) {
            saveResource("merchants/_example.yml", false);
        }
    }

    /** 商店根目录：配了共享目录则用它，否则用插件数据目录。 */
    File merchantsRoot() {
        String shared = config.sharedConfigPath;
        File root = shared != null && !shared.isBlank() ? new File(shared) : getDataFolder();
        return new File(root, "merchants");
    }

    void reloadMerchants() {
        merchantsRoot().mkdirs();
        manager.load(merchantsRoot());
        fingerprint = fingerprint();
    }

    private void poll() {
        long current = fingerprint();
        if (current != fingerprint) {
            fingerprint = current;
            manager.load(merchantsRoot());
            getLogger().info("检测到交易配置变化，已自动重载 " + manager.size() + " 个商店。");
        }
    }

    private long fingerprint() {
        long sum = 0;
        for (File file : yamlFiles(merchantsRoot())) {
            sum = sum * 31 + file.lastModified() * 131 + file.length();
        }
        return sum;
    }

    private static List<File> yamlFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    out.addAll(yamlFiles(child));
                } else if (child.getName().toLowerCase().endsWith(".yml")) {
                    out.add(child);
                }
            }
        }
        return out;
    }

    // ---------------- 界面入口 ----------------

    void openMerchant(Player player, Merchant merchant, int page) {
        if (merchant == null) {
            return;
        }
        if (!merchant.permission.isBlank() && !player.hasPermission(merchant.permission)) {
            player.sendMessage(prefixed("no-permission"));
            return;
        }
        if ("custom".equals(config.guiMode)) {
            TradeGui gui = new TradeGui(this, player, merchant, page);
            open.put(player.getUniqueId(), gui);
            gui.open();
        } else {
            TradeMerchant merchantGui = new TradeMerchant(this, player, merchant);
            if (merchantGui.getRecipeCount() == 0) {
                player.sendMessage(prefixed("no-trades"));
                return;
            }
            player.openMerchant(merchantGui, true);
        }
    }

    void forget(TradeGui gui) {
        open.remove(gui.player().getUniqueId(), gui);
    }

    // ---------------- 访问器 ----------------

    TradeConfig config() {
        return config;
    }

    MerchantManager manager() {
        return manager;
    }

    SoulSpaceHook soulSpace() {
        return soulSpace;
    }

    TradeService service() {
        return service;
    }

    String prefixed(String key, String... replacements) {
        return config.prefixed(key, replacements);
    }

    String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "?";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return Msg.plain(meta.getDisplayName());
        }
        return item.getType().name().toLowerCase();
    }

    /** 供外部（命令）使用。 */
    List<Merchant> merchants() {
        return manager.all();
    }
}

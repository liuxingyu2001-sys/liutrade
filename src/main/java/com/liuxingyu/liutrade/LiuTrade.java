package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** LiuTrade 主类。 */
public final class LiuTrade extends JavaPlugin {

    private final TradeConfig config = new TradeConfig();
    /** Folia 下不同玩家的区域线程会并发访问这些会话表。 */
    private final Map<UUID, TradeGui> open = new ConcurrentHashMap<>();
    private final Map<org.bukkit.inventory.Merchant, NativeShopSession> nativeSessions = new ConcurrentHashMap<>();
    /** 正在查看配方而被替换的原生商户（关闭时不应遗忘，以便返回）。 */
    private final java.util.Set<org.bukkit.inventory.Merchant> navigatingNative =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private MerchantManager manager;
    private SoulSpaceHook soulSpace;
    private TradeService service;
    private volatile long fingerprint;
    private volatile Runnable reloadTimer;
    /** 当前自动重载周期；-1 = 未启用。用于避免每次自动重载都取消/重建定时器。 */
    private volatile long reloadPeriod = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        copyExample();
        config.load(getConfig());
        boolean sparrow = SparrowBootstrap.setUp(this);
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
        getLogger().info("界面模式：" + config.guiMode + ("custom".equals(config.guiMode) ? "（Sparrow UI 自定义界面）" : "（原生村民界面）"));
        if ("custom".equals(config.guiMode) && !sparrow) {
            getLogger().warning("自定义界面需要 Sparrow UI，初始化失败时已自动回退为原生村民界面。");
        }
        if (config.autoReload) {
            long period = config.pollIntervalTicks;
            reloadTimer = Sched.runGlobalTimer(this, this::poll, period, period);
            reloadPeriod = period;
        }
        getLogger().info("LiuTrade 已启用。");
    }

    @Override
    public void onDisable() {
        if (reloadTimer != null) {
            try {
                reloadTimer.run();
            } catch (Throwable ignored) {
                // 停止阶段忽略取消失败，继续释放会话。
            }
            reloadTimer = null;
            reloadPeriod = -1;
        }
        for (TradeGui gui : new ArrayList<>(open.values())) {
            // 按 leftover-on-close 统一处理：离线玩家也不能直接 clear，否则虚拟输入槽物品会消失。
            try {
                gui.releaseOnDisable();
            } catch (Throwable failure) {
                getLogger().warning("停用 LiuTrade 时退回输入槽失败：" + failure);
            }
        }
        open.clear();
        nativeSessions.clear();
        navigatingNative.clear();
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

    synchronized void reloadMerchants() {
        merchantsRoot().mkdirs();
        manager.load(merchantsRoot());
        fingerprint = fingerprint();
    }

    /** 重载 config.yml + 全部商店（/liutrade reload 与自动检测用）。 */
    synchronized void reloadAll() {
        reloadConfig();
        config.load(getConfig());
        syncReloadTimer();
        reloadMerchants();
    }

    /** 让自动重载任务与最新 config 的 auto-reload/轮询间隔保持一致（/liutrade reload 后即时生效）。 */
    private synchronized void syncReloadTimer() {
        long desired = config.autoReload && isEnabled() ? config.pollIntervalTicks : -1;
        if (desired == reloadPeriod && (desired < 0 || reloadTimer != null)) {
            return; // 配置未变：不要取消/重建正在运行的定时器
        }
        if (reloadTimer != null) {
            try {
                reloadTimer.run();
            } catch (Throwable ignored) {
                // 已取消/已结束的任务再取消可能抛异常，不影响重新调度。
            }
            reloadTimer = null;
        }
        reloadPeriod = desired;
        if (desired > 0) {
            reloadTimer = Sched.runGlobalTimer(this, this::poll, desired, desired);
        }
    }

    private void poll() {
        try {
            long current = fingerprint();
            if (current != fingerprint) {
                reloadAll();
                getLogger().info("检测到配置变化，已自动重载（界面模式：" + config.guiMode
                        + "，商店 " + manager.size() + " 个）。");
            }
        } catch (Throwable failure) {
            // 周期任务抛异常会让调度器停掉后续轮询；这里吞掉并保留下一轮重试。
            getLogger().warning("自动重载失败（下一轮继续重试）：" + failure);
        }
    }

    private long fingerprint() {
        long sum = 0;
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.isFile()) {
            sum = configFile.lastModified() * 131 + configFile.length();
        }
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
        // 排序：listFiles() 的返回顺序不保证稳定，否则指纹会无端变化导致每轮都重载。
        out.sort(java.util.Comparator.comparing(File::getAbsolutePath));
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
        // 建窗/开容器都要在玩家所属线程执行（Folia 区域线程）。
        Sched.runForPlayer(this, player, () -> {
            if (player.isOnline()) {
                openMerchantNow(player, merchant, page);
            }
        });
    }

    /** 实际打开（已确保在玩家所属线程执行）。 */
    private void openMerchantNow(Player player, Merchant merchant, int page) {
        if ("custom".equals(config.guiMode) && SparrowBootstrap.ready()) {
            try {
                openCustomNow(player, merchant, page);
            } catch (Throwable t) {
                getLogger().warning("打开自定义界面失败：" + t);
                player.sendMessage(prefixed("no-trades"));
            }
            return;
        }
        NativeShopSession session;
        try {
            session = NativeShopSession.create(this, player, merchant);
        } catch (Throwable t) {
            getLogger().warning("打开原生村民界面失败：" + t);
            if (SparrowBootstrap.ready()) {
                try {
                    openCustomNow(player, merchant, page);
                } catch (Throwable customFailure) {
                    getLogger().warning("回退自定义界面也失败：" + customFailure);
                    player.sendMessage(prefixed("no-trades"));
                }
            } else {
                player.sendMessage(prefixed("no-trades"));
            }
            return;
        }
        if (session.size() == 0) {
            player.sendMessage(prefixed("no-trades"));
            return;
        }
        nativeSessions.put(session.handle(), session);
        try {
            player.openMerchant(session.handle(), true);
        } catch (Throwable t) {
            nativeSessions.remove(session.handle());
            getLogger().warning("打开原生村民容器失败：" + t);
            player.sendMessage(prefixed("no-trades"));
        }
    }

    /** 打开 Sparrow 自定义界面（已确保在玩家所属线程执行）。 */
    private void openCustomNow(Player player, Merchant merchant, int page) {
        TradeGui gui = new TradeGui(this, player, merchant, page);
        open.put(player.getUniqueId(), gui);
        gui.open();
    }

    NativeShopSession nativeSession(org.bukkit.inventory.Merchant handle) {
        return handle == null ? null : nativeSessions.get(handle);
    }

    void forgetNative(org.bukkit.inventory.Merchant handle) {
        if (handle == null) {
            return;
        }
        // 被配方界面替换时会话要保留，返回时才能重新打开
        if (navigatingNative.remove(handle)) {
            return;
        }
        nativeSessions.remove(handle);
    }

    // ---------------- 配方查看 ----------------

    /** 自定义界面：右键交易/产出格 → 查看这笔交易配置的输入→输出配方（ESC 由 Sparrow 会话原生返回）。 */
    void openRecipeFromGui(Player player, TradeGui gui, Trade trade) {
        if (!SparrowBootstrap.ready()) {
            player.sendMessage(prefixed("recipe-none"));
            return;
        }
        try {
            gui.navigateTo(RecipeGui.child(this, player, trade));
        } catch (Throwable failure) {
            getLogger().warning("打开交易配方失败：" + failure);
            player.sendMessage(prefixed("recipe-none"));
        }
    }

    /** 原生村民界面：右键产出格 → 查看配方，关闭后重新打开村民界面。 */
    void openRecipeFromNative(Player player, NativeShopSession session, Trade trade) {
        if (!SparrowBootstrap.ready()) {
            player.sendMessage(prefixed("recipe-none"));
            return;
        }
        // 打开配方窗会先关掉村民容器，这里标记会话不要被遗忘，返回时才能重开。
        navigatingNative.add(session.handle());
        try {
            RecipeGui.openRoot(this, player, trade, () -> {
                navigatingNative.remove(session.handle());
                // 会话结束当刻直接开原生容器可能与会话收尾相冲，放到下一 tick 的玩家线程。
                Sched.runForPlayer(this, player, () -> {
                    if (player.isOnline()) {
                        nativeSessions.put(session.handle(), session);
                        try {
                            player.openMerchant(session.handle(), true);
                        } catch (Throwable t) {
                            nativeSessions.remove(session.handle());
                            getLogger().warning("返回原生村民容器失败：" + t);
                        }
                    }
                });
            });
        } catch (Throwable failure) {
            navigatingNative.remove(session.handle());
            getLogger().warning("打开交易配方失败：" + failure);
            player.sendMessage(prefixed("recipe-none"));
        }
    }

    void forget(TradeGui gui) {
        open.remove(gui.player().getUniqueId(), gui);
    }

    /** 玩家退出时清理所有会话引用，并先把自定义界面的输入槽退回。 */
    void forgetPlayer(Player player) {
        TradeGui gui = open.remove(player.getUniqueId());
        if (gui != null) {
            gui.releaseOnQuit();
        }
        nativeSessions.entrySet().removeIf(entry -> {
            if (!entry.getValue().player().equals(player)) {
                return false;
            }
            navigatingNative.remove(entry.getKey());
            return true;
        });
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

    /** 物品展示名：经 SoulSpace（自定义名 → 材质中文名）→ 本地自定义名 → 英文材质名。 */
    String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "?";
        }
        String viaSoulSpace = soulSpace.displayName(item);
        if (viaSoulSpace != null) {
            return Msg.plain(viaSoulSpace);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                return Msg.plain(meta.getDisplayName());
            }
            if (meta.hasItemName()) {
                return Msg.plain(meta.getItemName());
            }
        }
        return item.getType().name().toLowerCase().replace('_', ' ');
    }

    /** 物品规格的界面显示名：配置的 display_name/label 优先，否则按显示样品取名，再兜底 label()。 */
    String specName(ItemSpec spec) {
        if (spec == null) {
            return "?";
        }
        String configured = spec.configuredName();
        if (configured != null) {
            return Msg.plain(configured);
        }
        ItemStack icon = spec.iconSample(soulSpace);
        if (icon != null && !icon.getType().isAir()) {
            return itemName(icon);
        }
        return spec.label();
    }

    /** 供外部（命令）使用。 */
    List<Merchant> merchants() {
        return manager.all();
    }
}

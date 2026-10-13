package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 调度工具：自动区分 Paper/Spigot（Bukkit 调度器）与 Folia（区域 / 实体调度器）。
 *
 * <p>Folia 下 {@code Bukkit.getScheduler()} 会直接抛 {@link UnsupportedOperationException}，
 * 因此声明了 {@code folia-supported: true} 的插件必须走区域化调度 API。
 */
final class Sched {

    /** Folia 服务端独有类；Paper/Spigot 上没有。 */
    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private Sched() {
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 周期任务（无区域归属的全局任务）。 */
    static void runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    scheduled -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }
    }

    /** 下一 tick 在玩家所属线程执行（用于界面刷新等需要玩家区域的动作）。 */
    static void runForPlayer(Plugin plugin, Player player, Runnable task) {
        if (FOLIA) {
            player.getScheduler().run(plugin, scheduled -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}

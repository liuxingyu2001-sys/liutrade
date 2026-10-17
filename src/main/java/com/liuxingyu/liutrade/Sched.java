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

    /** 周期任务（无区域归属的全局任务）；返回取消句柄。 */
    static Runnable runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            var scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    ignored -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
            return scheduled == null ? () -> { } : scheduled::cancel;
        }
        var bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return bukkitTask == null ? () -> { } : bukkitTask::cancel;
    }

    /** 下一 tick 在玩家所属线程执行（用于界面刷新等需要玩家区域的动作）。 */
    static void runForPlayer(Plugin plugin, Player player, Runnable task) {
        runForPlayer(plugin, player, task, () -> { });
    }

    /**
     * 带退役回调的玩家线程调度：Folia 下玩家已退出/实体已 retired 时回调，
     * 避免任务静默丢失；Paper 下插件已停用也走回调。
     */
    static void runForPlayer(Plugin plugin, Player player, Runnable task, Runnable retired) {
        if (FOLIA) {
            if (player == null) {
                Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
                return;
            }
            try {
                if (player.getScheduler().run(plugin, scheduled -> task.run(), retired) == null) {
                    retired.run();
                }
            } catch (Throwable failure) {
                retired.run();
            }
        } else {
            if (!plugin.isEnabled()) {
                retired.run();
                return;
            }
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}

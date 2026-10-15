package com.liuxingyu.liutrade;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.plugin.Plugin;

/**
 * Sparrow UI 运行时引导（包内已 relocate，见 pom 的 maven-shade-plugin）。
 *
 * <p>初始化是一次性动作：注册事件监听、玩家信号运行时、网络与窗口管理器。失败
 * （例如服务端版本不受支持）不影响原生村民界面与其余功能，整体捕获异常并降级。</p>
 */
final class SparrowBootstrap {

    private static volatile boolean ready;
    private static boolean attempted;

    private SparrowBootstrap() {
    }

    /** 幂等初始化：只尝试一次。返回 Sparrow UI 是否可用。 */
    static synchronized boolean setUp(Plugin plugin) {
        if (ready) {
            return true;
        }
        if (attempted) {
            return false;
        }
        attempted = true;
        try {
            SparrowUI.getInstance().setUp(plugin);
            ready = true;
            return true;
        } catch (Throwable failure) {
            plugin.getLogger().warning("Sparrow UI 初始化失败，自定义界面将回退为原生村民界面：" + failure);
            ready = false;
            return false;
        }
    }

    /** Sparrow UI 是否已初始化完成。 */
    static boolean ready() {
        return ready;
    }
}

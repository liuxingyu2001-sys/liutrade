package com.liuxingyu.liutrade;

import com.liuxingyu.liulib.LiuLib;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/**
 * LiuLib 物品规格解析桥（{@code plugin.yml} 已 {@code depend: [LiuLib]}）。
 *
 * <p>把配置里的字符串交给 LiuLib {@code ItemService#buildOrNull}，从而直接支持完整物品规格：
 * {@code DIAMOND} / {@code minecraft:diamond} / {@code DIAMOND*3} / {@code ce:ns:id} /
 * {@code craftengine:ns:id} / {@code base64:...} / {@code nbt:...} / {@code bytes:...} /
 * {@code item:base64:...} / {@code lib:<物品库ID>}（{@code lib:} 由 liuitemlib 注册）。
 * 解析失败返回 {@code null}，由调用方回退原有材质/物品库逻辑，老配置行为不变。</p>
 *
 * <p>非 Bukkit 环境（单元测试）下 {@link LiuLib#items()} 会抛异常，这里全部吞掉按「不可用」处理。</p>
 */
final class LiuLibItems {

    private static final Logger LOGGER = Logger.getLogger("LiuTrade");

    private LiuLibItems() {
    }

    /**
     * 按 LiuLib 物品规格构建物品；规格非法、LiuLib 不可用或构建失败返回 {@code null}。
     *
     * @param raw 配置原文（{@code material}/{@code library_id} 或字符串简写）
     */
    static ItemStack buildOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String spec = raw.trim();
        try {
            // LiuLib 未启用时 items() 会抛 IllegalStateException（测试环境也可能是 NoClassDefFoundError）
            return LiuLib.items().buildOrNull(spec);
        } catch (Throwable t) {
            // 静默回退：老配置（原版材质 / SoulSpace library_id）由调用方兜底，不能因规格写法不同刷屏
            LOGGER.fine("LiuLib 物品规格解析失败，回退原有逻辑：" + spec + "（" + t + "）");
            return null;
        }
    }
}

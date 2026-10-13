package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 对 SoulSpace 的软依赖桥（纯反射，无编译期依赖）。
 *
 * <p>优先通过 Bukkit ServicesManager 取 {@code com.soulspace.api.SoulSpaceApi}，
 * 取不到再退回静态入口 {@code com.soulspace.api.SoulSpace#getApi()}。
 * SoulSpace 未安装时 {@link #available()} 为 false，此时只有原版材质交易可用。
 */
final class SoulSpaceHook {

    private final Plugin plugin;
    private volatile Object api;
    private Method mResolve;
    private Method mMatches;
    private Method mDisplayName;
    private Method mConversion;
    private Method mConvert;
    private Method mHasRule;

    SoulSpaceHook(Plugin plugin) {
        this.plugin = plugin;
    }

    boolean available() {
        return api != null;
    }

    /** SoulSpace 是否提供规则匹配（2.5.0+）。 */
    boolean canMatchRules() {
        return api != null && mMatches != null;
    }

    /** SoulSpace 是否提供统一展示名（2.5.1+）。 */
    boolean canDisplayName() {
        return api != null && mDisplayName != null;
    }

    /** 连接 SoulSpace；成功返回 true。可重复调用。 */
    boolean connect() {
        if (api != null) {
            return true;
        }
        Object provider = serviceApi();
        if (provider == null) {
            provider = staticApi();
        }
        if (provider == null) {
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("com.soulspace.api.SoulSpaceApi");
            Method conversion = apiClass.getMethod("conversion");
            Class<?> conversionClass = Class.forName("com.soulspace.api.ItemConversion");
            Method convert = conversionClass.getMethod("convert", UUID.class, ItemStack.class);
            Method hasRule = conversionClass.getMethod("hasRule", ItemStack.class);
            this.mConversion = conversion;
            this.mConvert = convert;
            this.mHasRule = hasRule;
            // 2.5.0 新增；旧版没有则仅影响 library_id / 规则匹配。
            this.mResolve = tryMethod(apiClass, "resolveItem", String.class);
            this.mMatches = tryMethod(apiClass, "matchesRule", Map.class, ItemStack.class);
            this.mDisplayName = tryMethod(apiClass, "displayName", ItemStack.class);
            this.api = provider;
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("连接 SoulSpace API 失败（需 SoulSpace 2.4.9+）：" + t);
            return false;
        }
    }

    private static Method tryMethod(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Object serviceApi() {
        try {
            Class<?> apiClass = Class.forName("com.soulspace.api.SoulSpaceApi");
            @SuppressWarnings({"unchecked", "rawtypes"})
            RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration((Class) apiClass);
            return reg == null ? null : reg.getProvider();
        } catch (Throwable t) {
            return null;
        }
    }

    private Object staticApi() {
        try {
            Class<?> soul = Class.forName("com.soulspace.api.SoulSpace");
            return soul.getMethod("getApi").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    Optional<ItemStack> resolveItem(String libraryId) {
        if (!available() || mResolve == null || libraryId == null) {
            return Optional.empty();
        }
        try {
            Object result = mResolve.invoke(api, libraryId);
            return result instanceof Optional<?> opt ? (Optional<ItemStack>) opt : Optional.empty();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    boolean matchesRule(Map<String, Object> rule, ItemStack item) {
        if (!available() || mMatches == null || rule == null || item == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(mMatches.invoke(api, rule, item));
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    Optional<ItemStack> convert(UUID playerId, ItemStack item) {
        if (!available() || mConvert == null || item == null) {
            return Optional.empty();
        }
        try {
            Object conversion = mConversion.invoke(api);
            Object result = mConvert.invoke(conversion, playerId, item);
            return result instanceof Optional<?> opt ? (Optional<ItemStack>) opt : Optional.empty();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    boolean hasConversion(ItemStack item) {
        if (!available() || mHasRule == null || item == null) {
            return false;
        }
        try {
            Object conversion = mConversion.invoke(api);
            return Boolean.TRUE.equals(mHasRule.invoke(conversion, item));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 经 SoulSpace 取统一展示名（中文/物品库名）；不可用或失败返回 null。 */
    String displayName(ItemStack item) {
        if (!available() || mDisplayName == null || item == null) {
            return null;
        }
        try {
            Object result = mDisplayName.invoke(api, item);
            if (result instanceof String s && !s.isBlank()) {
                return s;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}

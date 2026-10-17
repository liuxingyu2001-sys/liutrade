package com.liuxingyu.liutrade;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Citizens 软依赖：玩家右键 NPC 时按 {@code npc} 绑定打开对应商店。
 * 纯反射调用 Citizens API，未安装 Citizens 时静默跳过。
 */
final class CitizensHook implements Listener {

    private final LiuTrade plugin;
    private Object registry;
    private Method getNPC;
    private Method getId;
    private Method getName;
    private boolean ready;
    private final Map<UUID, Long> lastOpen = new ConcurrentHashMap<>();

    CitizensHook(LiuTrade plugin) {
        this.plugin = plugin;
    }

    void register() {
        Plugin citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        if (citizens == null) {
            return;
        }
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            registry = api.getMethod("getNPCRegistry").invoke(null);
            Class<?> registryClass = Class.forName("net.citizensnpcs.api.npc.NPCRegistry");
            getNPC = registryClass.getMethod("getNPC", Entity.class);
            Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
            getId = npcClass.getMethod("getId");
            getName = npcClass.getMethod("getName");
            ready = registry != null;
        } catch (Throwable t) {
            plugin.getLogger().warning("连接 Citizens 失败：" + t);
        }
        if (ready) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            plugin.getLogger().info("已启用 Citizens NPC 绑定。");
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!ready || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Merchant merchant = lookup(event.getRightClicked());
        if (merchant != null) {
            // 右键实体会先后触发 PlayerInteractAtEntityEvent 与 PlayerInteractEntityEvent，
            // 两个都属于本监听（子类关系），这里做短时间去重。
            long now = System.currentTimeMillis();
            UUID playerId = event.getPlayer().getUniqueId();
            boolean[] allowed = {false};
            lastOpen.compute(playerId, (key, previous) -> {
                if (previous != null && now - previous < 300) {
                    return previous;
                }
                allowed[0] = true;
                return now;
            });
            if (!allowed[0]) {
                return;
            }
            event.setCancelled(true);
            plugin.openMerchant(event.getPlayer(), merchant, 0);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastOpen.remove(event.getPlayer().getUniqueId());
    }

    private Merchant lookup(Entity entity) {
        try {
            Object npc = getNPC.invoke(registry, entity);
            if (npc == null) {
                return null;
            }
            int id = (Integer) getId.invoke(npc);
            String name = String.valueOf(getName.invoke(npc));
            return plugin.manager().byNpc(String.valueOf(id), name);
        } catch (Throwable t) {
            return null;
        }
    }
}

package com.liuxingyu.liutrade;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.MerchantInventory;

/** 交易界面事件（自定义界面由 Sparrow UI 内部处理，这里只留原生村民界面相关）。 */
final class TradeListener implements Listener {

    private final LiuTrade plugin;

    TradeListener(LiuTrade plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.getInventory() instanceof MerchantInventory merchantInventory) {
            handleNativeRecipeClick(event, merchantInventory);
        }
    }

    /** 原生村民界面：右键产出格查看配方。 */
    private void handleNativeRecipeClick(InventoryClickEvent event, MerchantInventory merchantInventory) {
        NativeShopSession session = plugin.nativeSession(merchantInventory.getMerchant());
        if (session == null || !event.isRightClick() || event.getRawSlot() != 2) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        Trade trade = session.tradeAt(merchantInventory.getSelectedRecipeIndex());
        if (trade != null) {
            plugin.openRecipeFromNative(player, session, trade);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // 清理会话引用，避免离线玩家在 nativeSessions / open / CitizensHook 中泄漏。
        plugin.forgetPlayer(event.getPlayer());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory() instanceof MerchantInventory merchantInventory) {
            plugin.forgetNative(merchantInventory.getMerchant());
        }
    }

    // ---------------- 原生村民界面 ----------------

    @EventHandler
    public void onPrepare(PrepareResultEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory inventory)) {
            return;
        }
        NativeShopSession session = plugin.nativeSession(inventory.getMerchant());
        if (session == null) {
            return;
        }
        // 用真实输入槽重算产出（转换类交易尤其需要），不匹配则清空结果。
        event.setResult(session.prepareResult(inventory));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPurchase(PlayerPurchaseEvent event) {
        NativeShopSession session = plugin.nativeSession(event.getMerchant());
        if (session == null) {
            return;
        }
        Player player = event.getPlayer();
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top instanceof MerchantInventory inventory)) {
            return;
        }
        if (!session.validate(inventory)) {
            event.setCancelled(true);
            player.sendMessage(plugin.prefixed("trade-fail"));
            return;
        }
        Trade trade = session.tradeAt(inventory.getSelectedRecipeIndex());
        if (trade != null) {
            session.grantExtras(player, trade, inventory.getItem(0));
            plugin.service().runCommands(player, trade, 1);
            if (plugin.config().feedback) {
                player.sendMessage(plugin.prefixed("trade-success",
                        "%name%", Msg.color(trade.name),
                        "%times%", "1"));
            }
        }
    }
}

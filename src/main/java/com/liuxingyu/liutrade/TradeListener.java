package com.liuxingyu.liutrade;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.MerchantInventory;

/** 交易界面事件。 */
final class TradeListener implements Listener {

    private final LiuTrade plugin;

    TradeListener(LiuTrade plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeGui gui)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null) {
            event.setCancelled(true);
            return;
        }
        if (!event.getClickedInventory().equals(event.getInventory())) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
            return;
        }
        int slot = event.getRawSlot();
        if (TradeGui.isInput(slot)) {
            plugin.getServer().getScheduler().runTask(plugin, gui::render);
            return;
        }
        event.setCancelled(true);
        if (slot == TradeGui.SLOT_OUT) {
            Trade trade = gui.currentTrade();
            if (trade != null) {
                plugin.service().execute(player, gui, trade, event.isShiftClick() ? gui.batches(trade) : 1);
            }
        } else if (slot == TradeGui.SLOT_CLOSE) {
            player.closeInventory();
        } else if (slot == TradeGui.SLOT_PREV) {
            plugin.openMerchant(player, gui.merchant(), gui.page() - 1);
        } else if (slot == TradeGui.SLOT_NEXT) {
            plugin.openMerchant(player, gui.merchant(), gui.page() + 1);
        } else if (TradeGui.isTradeSlot(slot)) {
            int index = gui.page() * TradeGui.TRADE_SLOTS.length + tradeIndex(slot);
            if (index >= 0 && index < gui.merchant().trades.size()) {
                gui.autoFill(gui.merchant().trades.get(index));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeGui gui)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < 54 && !TradeGui.isInput(slot)) {
                event.setCancelled(true);
                return;
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, gui::render);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TradeGui gui)) {
            return;
        }
        if ("void".equalsIgnoreCase(plugin.config().leftoverOnClose)) {
            gui.getInventory().setItem(TradeGui.SLOT_IN1, null);
            gui.getInventory().setItem(TradeGui.SLOT_IN2, null);
        } else {
            gui.returnInputs();
        }
        plugin.forget(gui);
    }

    // ---------------- 原生村民界面 ----------------

    @EventHandler
    public void onPrepare(PrepareResultEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory inventory)) {
            return;
        }
        if (!(inventory.getMerchant() instanceof TradeMerchant merchant)) {
            return;
        }
        // 用真实输入槽重算产出（转换类交易尤其需要），不匹配则清空结果。
        event.setResult(merchant.prepareResult(inventory));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPurchase(PlayerPurchaseEvent event) {
        if (!(event.getMerchant() instanceof TradeMerchant merchant)) {
            return;
        }
        Player player = event.getPlayer();
        Trade trade = merchant.tradeFor(event.getTrade());
        if (trade == null) {
            event.setCancelled(true);
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top instanceof MerchantInventory inventory && !merchant.validate(inventory, event.getTrade())) {
            event.setCancelled(true);
            player.sendMessage(plugin.prefixed("trade-fail"));
            return;
        }
        if (plugin.config().feedback) {
            player.sendMessage(plugin.prefixed("trade-success",
                    "%name%", Msg.color(trade.name),
                    "%times%", "1"));
        }
    }

    private static int tradeIndex(int slot) {
        for (int i = 0; i < TradeGui.TRADE_SLOTS.length; i++) {
            if (TradeGui.TRADE_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }
}

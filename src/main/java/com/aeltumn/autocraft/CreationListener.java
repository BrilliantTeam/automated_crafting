package com.aeltumn.autocraft;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Nameable;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class CreationListener implements Listener {
    public static boolean isValidBlock(Block bl, boolean existing) {
        BlockState state = bl.getState(false);
        if ((!ConfigFile.allowDispensers() || !(state instanceof org.bukkit.block.Dispenser)) && (
        !ConfigFile.allowChests() || !(state instanceof org.bukkit.block.Chest)) && !(state instanceof org.bukkit.block.Dropper))
        return false; 
        return (!existing || AutomatedCrafting.INSTANCE.getCrafterRegistry().isAutocrafter(bl));
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(InventoryMoveItemEvent e) {
        InventoryHolder sourceHolder = e.getSource().getHolder(false);
        if (sourceHolder instanceof Container) {
        Block bl = ((Container)sourceHolder).getBlock();
        if (isValidBlock(bl, true)) {
            e.setCancelled(true);
            if (ConfigFile.craftOnRedstonePulse()) {
            InventoryHolder destHolder = e.getDestination().getHolder(false);
            if (!(destHolder instanceof org.bukkit.block.Hopper) && !(destHolder instanceof org.bukkit.entity.minecart.HopperMinecart))
                AutomatedCrafting.INSTANCE.getCrafterRegistry().tick(bl); 
            } 
        } 
        } 
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        Block bl = e.getBlock();
        if (isValidBlock(bl, true)) {
        e.setCancelled(true);
        if (ConfigFile.craftOnRedstonePulse())
            Bukkit.getRegionScheduler().run((Plugin)AutomatedCrafting.INSTANCE, bl.getLocation(), ignored -> AutomatedCrafting.INSTANCE.getCrafterRegistry().tick(bl)); 
        } 
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreate(HangingPlaceEvent e) {
        Block bl = e.getEntity().getLocation().getBlock().getRelative(e.getEntity().getAttachedFace());
        if (isValidBlock(bl, false))
        AutomatedCrafting.INSTANCE.getCrafterRegistry().create(bl.getLocation(), e.getPlayer(), null); 
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        breakCrafter(e.getBlock(), true);
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(HangingBreakEvent e) {
        destroyCrafter((Entity)e.getEntity(), false);
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStealItem(EntityDamageByEntityEvent e) {
        destroyCrafter(e.getEntity(), true);
    }
    
    private void destroyCrafter(Entity itemFrame, boolean clean) {
        if (!(itemFrame instanceof ItemFrame))
        return; 
        Block bl = itemFrame.getLocation().getBlock().getRelative(((ItemFrame)itemFrame).getAttachedFace());
        breakCrafter(bl, clean);
    }
    
    private void breakCrafter(Block bl, boolean clean) {
        if (isValidBlock(bl, true)) {
        AutomatedCrafting.INSTANCE.getCrafterRegistry().destroy(bl.getLocation());
        if (clean) {
            BlockState state = bl.getState();
            ((Nameable)state).setCustomName(null);
            state.update();
        } 
        } 
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClickItemFrame(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame))
        return; 
        ItemStack heldItem = (e.getHand() == EquipmentSlot.HAND) ? e.getPlayer().getInventory().getItemInMainHand() : e.getPlayer().getInventory().getItemInOffHand();
        if (heldItem == null || heldItem.getType() == Material.AIR)
        return; 
        Block bl = e.getRightClicked().getLocation().getBlock().getRelative(((ItemFrame)e.getRightClicked()).getAttachedFace());
        if (isValidBlock(bl, false)) {
        if (((ItemFrame)e.getRightClicked()).getItem().getType() != Material.AIR) {
            e.setCancelled(true);
            return;
        } 
        Bukkit.getRegionScheduler().runDelayed((Plugin)AutomatedCrafting.INSTANCE, bl.getLocation(), ignored -> {
                ItemStack item = ((ItemFrame)e.getRightClicked()).getItem();
                AutomatedCrafting.INSTANCE.getCrafterRegistry().create(bl.getLocation(), e.getPlayer(), item);
                if (AutomatedCrafting.INSTANCE.getCrafterRegistry().checkBlock(bl.getLocation(), e.getPlayer())) {
                BlockState state = bl.getState();
                ((Nameable)state).setCustomName("自動合成器");
                state.update();
                } 
            }1L);
        } 
    }
}

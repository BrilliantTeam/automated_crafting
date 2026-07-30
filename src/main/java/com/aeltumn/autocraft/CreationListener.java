package com.aeltumn.autocraft;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Nameable;
import org.bukkit.Location;
import org.bukkit.block.*;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.util.BoundingBox;
import org.bukkit.block.data.Directional;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CreationListener implements Listener {
    private static final NamespacedKey KEY_ENABLE = new NamespacedKey("hopper_filter", "enable");
    private static final NamespacedKey KEY_STRICT = new NamespacedKey("hopper_filter", "strict");
    private static final NamespacedKey KEY_MODE = new NamespacedKey("hopper_filter", "mode");
    private static final NamespacedKey KEY_ITEMS = new NamespacedKey("hopper_filter", "items");
    private static final NamespacedKey KEY_MATERIAL = new NamespacedKey("hopper_filter", "materials");

    /**
     * Returns true if this block is a valid block and optionally if it's seen as an autocrafter.
     *
     * @param existing Only return true if this block is also already an autocrafter.
     */
    public static boolean isValidBlock(final Block bl, boolean existing) {
        //If the block is not any of the allowed states.
        BlockState state = bl.getState(false);
        if ((!ConfigFile.allowDispensers() || !(state instanceof Dispenser)) &&
                (!ConfigFile.allowChests() || !(state instanceof Chest)) &&
                !(state instanceof Dropper))
            return false;

        //Test if we can find an autocrafter on this block if applicable.
        return !existing || AutomatedCrafting.INSTANCE.getCrafterRegistry().isAutocrafter(bl);
    }

    private ItemStack getOutputItem(Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        BoundingBox box = BoundingBox.of(center.clone().subtract(1, 1, 1), center.clone().add(1, 1, 1));
        Collection<Entity> entities = block.getWorld().getNearbyEntities(box);
        for (Entity entity : entities) {
            if (entity instanceof ItemFrame frame) {
                Block attachedBlock = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
                if (attachedBlock.equals(block)) {
                    return frame.getItem();
                }
            }
        }
        return new ItemStack(Material.AIR);
    }

    private boolean isShulker(Material mat) {
        return mat == Material.SHULKER_BOX || mat.name().endsWith("_SHULKER_BOX");
    }

    //This method specifically is needed because when droppers put the item directly into the neighbouring container the BlockDispenseEvent is not fired.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(InventoryMoveItemEvent e) {
        //Never call getHolder() here: this event fires for every hopper transfer on the server and CraftBukkit
        //builds a full BlockState snapshot for it (serializing all NBT of e.g. a shulker box). getLocation() is free.
        Location sourceLoc = e.getSource().getLocation();
        if (sourceLoc == null) return;

        Block sourceBlock = sourceLoc.getBlock();
        if (!isValidBlock(sourceBlock, true)) return;

        ItemStack output = getOutputItem(sourceBlock);
        boolean outputShulker = isShulker(output.getType());

        org.bukkit.block.data.BlockData data = sourceBlock.getBlockData();
        if (!(data instanceof Directional)) return;
        BlockFace facing = ((Directional) data).getFacing();
        Block targetBlock = sourceBlock.getRelative(facing);
        boolean targetShulker = isShulker(targetBlock.getType());

        boolean cancelCraft = outputShulker && targetShulker;

        e.setCancelled(true);

        if (!ConfigFile.craftOnRedstonePulse()) return;

        InventoryHolder destHolder = e.getDestination().getHolder();
        boolean isInputFromBelow = false;

        if (destHolder instanceof org.bukkit.block.Hopper hopper) {
            Block hopperBlock = hopper.getBlock();
            isInputFromBelow = hopperBlock.getX() == sourceBlock.getX() &&
                            hopperBlock.getY() == sourceBlock.getY() - 1 &&
                            hopperBlock.getZ() == sourceBlock.getZ();
        }
        else if (destHolder instanceof HopperMinecart minecart) {
            Location sourceCenter = sourceBlock.getLocation().add(0.5, 0.5, 0.5);
            Location cartLoc = minecart.getLocation();

            double dx = Math.abs(cartLoc.getX() - sourceCenter.getX());
            double dz = Math.abs(cartLoc.getZ() - sourceCenter.getZ());
            double dy = cartLoc.getY() - sourceBlock.getY();

            isInputFromBelow = dx < 0.6 && dz < 0.6 && dy > -1.5 && dy < 0;
        }

        if (!isInputFromBelow) {
            // Check if target is Hopper and apply filter logic
            boolean allowTransfer = true;
            if (targetBlock.getState(false) instanceof Hopper targetHopper) {
                var pdc = targetHopper.getPersistentDataContainer();
                boolean isEnable = pdc.getOrDefault(KEY_ENABLE, PersistentDataType.BOOLEAN, false);
                if (isEnable) {
                    ItemStack outputItem = output;
                    if (outputItem.getType() == Material.AIR) {
                        allowTransfer = false;
                    } else {
                        List<byte[]> rawItems = pdc.getOrDefault(KEY_ITEMS, PersistentDataType.LIST.byteArrays(), List.of());
                        List<ItemStack> filterItems = new ArrayList<>();
                        for (byte[] bytes : rawItems) {
                            try {
                                filterItems.add(ItemStack.deserializeBytes(bytes));
                            } catch (Exception ex) {
                                // Ignore invalid items
                            }
                        }

                        List<String> rawMaterials = pdc.getOrDefault(KEY_MATERIAL, PersistentDataType.LIST.strings(), List.of());
                        List<Material> filterMaterials = new ArrayList<>();
                        for (String str : rawMaterials) {
                            Material mat = Material.getMaterial(str);
                            if (mat != null) {
                                filterMaterials.add(mat);
                            }
                        }

                        boolean matches = false;
                        Material outputType = outputItem.getType();

                        boolean isStrict = pdc.getOrDefault(KEY_STRICT, PersistentDataType.BOOLEAN, false);

                        if (isStrict) {
                            // Only match materials
                            if (filterMaterials.contains(outputType)) {
                                matches = true;
                            }
                            for (ItemStack fi : filterItems) {
                                if (fi.getType() == outputType) {
                                    matches = true;
                                    break;
                                }
                            }
                        } else {
                            // Match materials or full items (including meta)
                            if (filterMaterials.contains(outputType)) {
                                matches = true;
                            }
                            for (ItemStack fi : filterItems) {
                                if (outputItem.isSimilar(fi)) {
                                    matches = true;
                                    break;
                                }
                            }
                        }

                        int modeOrdinal = pdc.getOrDefault(KEY_MODE, PersistentDataType.INTEGER, 0);
                        boolean isWhitelist = modeOrdinal == 0; // Assuming 0 is WHITELIST, 1 is BLACKLIST

                        if (isWhitelist) {
                            allowTransfer = matches;
                        } else {
                            allowTransfer = !matches;
                        }
                    }
                }
            }

            if (!cancelCraft && allowTransfer) {
                AutomatedCrafting.INSTANCE.getCrafterRegistry().tick(sourceBlock);
            }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(final BlockDispenseEvent e) {
        //Autocrafters can't drop items normally. This is to avoid dispensing ingredients when powered.
        Block bl = e.getBlock();
        if (isValidBlock(bl, true)) {
            e.setCancelled(true);
            if (ConfigFile.craftOnRedstonePulse()) {
                ItemStack output = getOutputItem(bl);
                boolean outputShulker = isShulker(output.getType());

                org.bukkit.block.data.BlockData data = bl.getBlockData();
                if (!(data instanceof Directional)) return;
                BlockFace facing = ((Directional) data).getFacing();
                Block target = bl.getRelative(facing);
                boolean targetShulker = isShulker(target.getType());

                // Check if target is Hopper and apply filter logic
                boolean allowTransfer = true;
                if (target.getState(false) instanceof Hopper targetHopper) {
                    var pdc = targetHopper.getPersistentDataContainer();
                    boolean isEnable = pdc.getOrDefault(KEY_ENABLE, PersistentDataType.BOOLEAN, false);
                    if (isEnable) {
                        ItemStack outputItem = output;
                        if (outputItem.getType() == Material.AIR) {
                            allowTransfer = false;
                        } else {
                            List<byte[]> rawItems = pdc.getOrDefault(KEY_ITEMS, PersistentDataType.LIST.byteArrays(), List.of());
                            List<ItemStack> filterItems = new ArrayList<>();
                            for (byte[] bytes : rawItems) {
                                try {
                                    filterItems.add(ItemStack.deserializeBytes(bytes));
                                } catch (Exception ex) {
                                    // Ignore invalid items
                                }
                            }

                            List<String> rawMaterials = pdc.getOrDefault(KEY_MATERIAL, PersistentDataType.LIST.strings(), List.of());
                            List<Material> filterMaterials = new ArrayList<>();
                            for (String str : rawMaterials) {
                                Material mat = Material.getMaterial(str);
                                if (mat != null) {
                                    filterMaterials.add(mat);
                                }
                            }

                            boolean matches = false;
                            Material outputType = outputItem.getType();

                            boolean isStrict = pdc.getOrDefault(KEY_STRICT, PersistentDataType.BOOLEAN, false);

                            if (isStrict) {
                                // Only match materials
                                if (filterMaterials.contains(outputType)) {
                                    matches = true;
                                }
                                for (ItemStack fi : filterItems) {
                                    if (fi.getType() == outputType) {
                                        matches = true;
                                        break;
                                    }
                                }
                            } else {
                                // Match materials or full items (including meta)
                                if (filterMaterials.contains(outputType)) {
                                    matches = true;
                                }
                                for (ItemStack fi : filterItems) {
                                    if (outputItem.isSimilar(fi)) {
                                        matches = true;
                                        break;
                                    }
                                }
                            }

                            int modeOrdinal = pdc.getOrDefault(KEY_MODE, PersistentDataType.INTEGER, 0);
                            boolean isWhitelist = modeOrdinal == 0; // Assuming 0 is WHITELIST, 1 is BLACKLIST

                            if (isWhitelist) {
                                allowTransfer = matches;
                            } else {
                                allowTransfer = !matches;
                            }
                        }
                    }
                }

                if (!(outputShulker && targetShulker) && allowTransfer) {
                    Bukkit.getRegionScheduler().run(AutomatedCrafting.INSTANCE, bl.getLocation(), (ignored) -> AutomatedCrafting.INSTANCE.getCrafterRegistry().tick(bl));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreate(final HangingPlaceEvent e) {
        Block bl = e.getEntity().getLocation().getBlock().getRelative(e.getEntity().getAttachedFace());
        if (isValidBlock(bl, false))
            AutomatedCrafting.INSTANCE.getCrafterRegistry().create(bl.getLocation(), e.getPlayer(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent e) {
        //Destroying the item frame break the autocrafter.
        breakCrafter(e.getBlock(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(final HangingBreakEvent e) {
        //Destroying the item frame break the autocrafter.
        destroyCrafter(e.getEntity(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStealItem(final EntityDamageByEntityEvent e) {
        //Stealing the item from the item frame destroys the autocrafter.
        destroyCrafter(e.getEntity(), true);
    }

    private void destroyCrafter(final Entity itemFrame, final boolean clean) {
        if (!(itemFrame instanceof ItemFrame)) return;
        final Block bl = itemFrame.getLocation().getBlock().getRelative(((ItemFrame) itemFrame).getAttachedFace());
        breakCrafter(bl, clean);
    }

    private void breakCrafter(final Block bl, final boolean clean) {
        if (isValidBlock(bl, true)) {
            AutomatedCrafting.INSTANCE.getCrafterRegistry().destroy(bl.getLocation());

            //Clean should be true when the item is removed from the item frame. (can actually be true at all times but we don't need to update droppers randomly if you're placing down item frames, could break redstone)
            if (clean) {
                BlockState state = bl.getState();
                ((Nameable) state).setCustomName(null);
                state.update();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClickItemFrame(final PlayerInteractEntityEvent e) {
        // ignore clicking non-item frames
        if (!(e.getRightClicked() instanceof ItemFrame)) {
            return;
        }

        // ignore clicking whilst holding nothing
        ItemStack heldItem = e.getHand() == EquipmentSlot.HAND ? e.getPlayer().getInventory().getItemInMainHand() :
                e.getPlayer().getInventory().getItemInOffHand();
        if (heldItem == null || heldItem.getType() == Material.AIR) {
            return;
        }

        Block bl = e.getRightClicked().getLocation().getBlock().getRelative(((ItemFrame) e.getRightClicked()).getAttachedFace());
        if (isValidBlock(bl, false)) {
            //If there's already something in the item frame, cancel!
            //This prevents rotating the item in the item frame.
            if (((ItemFrame) e.getRightClicked()).getItem().getType() != Material.AIR) {
                e.setCancelled(true);
                return;
            }
            //Wait a second for the item to be put into the frame.

            Bukkit.getRegionScheduler().runDelayed(AutomatedCrafting.INSTANCE, bl.getLocation(), (ignored) -> {
                ItemStack item = ((ItemFrame) e.getRightClicked()).getItem();
                AutomatedCrafting.INSTANCE.getCrafterRegistry().create(bl.getLocation(), e.getPlayer(), item);

                //Only rename if we have a valid item that we can craft in there.
                if (AutomatedCrafting.INSTANCE.getCrafterRegistry().checkBlock(bl.getLocation(), e.getPlayer())) {
                    //The block is named autocrafter is it has an item frame AND there's an item in the item frame. If the item frame is empty the name should be reset.
                    //Rename it to autocrafter to make this clear to the player.
                    BlockState state = bl.getState();
                    ((Nameable) state).setCustomName("自動合成器");
                    state.update();
                }
            }, 1);
        }
    }
}
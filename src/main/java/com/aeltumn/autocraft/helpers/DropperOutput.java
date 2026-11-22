package com.aeltumn.autocraft.helpers;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Crafter;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BrewingRecipe;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class DropperOutput {
    private static final int[] FURNACE_UP = {0}, FURNACE_DOWN = {2, 1}, FURNACE_SIDE = {1};
    private static final int[] BREWING_UP = {3}, BREWING_DOWN = {0, 1, 2, 3}, BREWING_SIDE = {0, 1, 2, 4};
    private static final int HOPPER_COOLDOWN = 8;
    private static volatile List<RecipeChoice> reagents, potionInputs;

    private DropperOutput() {
    }

    public static Inventory containerAt(Block front) {
        if (front.getState(false) instanceof InventoryHolder holder) return holder.getInventory();
        List<Entity> carts = new ArrayList<>(front.getWorld().getNearbyEntities(BoundingBox.of(front),
                e -> e instanceof InventoryHolder && !(e instanceof LivingEntity) && e.isValid()));
        return carts.isEmpty() ? null : ((InventoryHolder) carts.get(ThreadLocalRandom.current().nextInt(carts.size()))).getInventory();
    }

    public static boolean insert(Inventory into, BlockFace facing, List<ItemStack> items, boolean apply) {
        return place(into, facing, items, apply, false) == 0;
    }

    public static int add(Inventory into, BlockFace facing, ItemStack item) {
        return place(into, facing, List.of(item), true, true);
    }

    private static int place(Inventory into, BlockFace facing, List<ItemStack> items, boolean apply, boolean partial) {
        ItemStack[] real = into.getContents();
        ItemStack[] slots = new ItemStack[real.length];
        for (int i = 0; i < real.length; i++) slots[i] = real[i] == null || real[i].isEmpty() ? null : real[i].clone();
        boolean[] changed = new boolean[slots.length];
        InventoryType type = into.getType();
        int[] order = order(type, facing == null ? null : facing.getOppositeFace(), slots.length);
        Crafter crafter = into.getHolder(false) instanceof Crafter c ? c : null;
        int left = 0;

        for (ItemStack item : items) {
            int amount = item.getAmount();
            for (int i = 0; i < order.length && amount > 0; i++) {
                int slot = order[i];
                if (!canPlace(type, into, crafter, slots, slot, item)) continue;
                ItemStack current = slots[slot];
                if (current == null) {
                    int put = Math.min(amount, into.getMaxStackSize());
                    slots[slot] = item.asQuantity(put);
                    amount -= put;
                    changed[slot] = true;
                } else if (current.getAmount() < current.getMaxStackSize() && current.isSimilar(item)) {
                    int put = Math.min(amount, Math.min(item.getMaxStackSize(), into.getMaxStackSize()) - current.getAmount());
                    if (put <= 0) continue;
                    current.setAmount(current.getAmount() + put);
                    amount -= put;
                    changed[slot] = true;
                }
            }
            if (amount > 0 && !partial) return amount;
            left += amount;
        }
        if (apply) {
            boolean wasEmpty = into.isEmpty();
            boolean moved = false;
            for (int i = 0; i < slots.length; i++) {
                if (!changed[i]) continue;
                into.setItem(i, slots[i]);
                moved = true;
            }
            if (moved && wasEmpty && into.getHolder(false) instanceof Hopper hopper && hopper.getTransferCooldown() <= HOPPER_COOLDOWN) {
                hopper.setTransferCooldown(HOPPER_COOLDOWN);
            }
        }
        return left;
    }

    private static int[] order(InventoryType type, BlockFace face, int size) {
        if (face == null) return allSlots(size);
        return switch (type) {
            case FURNACE, BLAST_FURNACE, SMOKER -> face == BlockFace.UP ? FURNACE_UP : face == BlockFace.DOWN ? FURNACE_DOWN : FURNACE_SIDE;
            case BREWING -> face == BlockFace.UP ? BREWING_UP : face == BlockFace.DOWN ? BREWING_DOWN : BREWING_SIDE;
            default -> allSlots(size);
        };
    }

    private static int[] allSlots(int size) {
        int[] all = new int[size];
        for (int i = 0; i < size; i++) all[i] = i;
        return all;
    }

    private static boolean canPlace(InventoryType type, Inventory into, Crafter crafter, ItemStack[] slots, int slot, ItemStack item) {
        return switch (type) {
            case SHULKER_BOX -> !Tag.SHULKER_BOXES.isTagged(item.getType());
            case FURNACE, BLAST_FURNACE, SMOKER -> slot == 0 || slot == 1 && (((FurnaceInventory) into).isFuel(item)
                    || item.getType() == Material.BUCKET && (slots[1] == null || slots[1].getType() != Material.BUCKET));
            case BREWING -> slot == 4 ? item.getType() == Material.BLAZE_POWDER
                    : slot == 3 ? matches(reagents(), item)
                    : slots[slot] == null && (Tag.ITEMS_BREWING_POTION_INPUTS.isTagged(item.getType()) || matches(potionInputs(), item));
            case CHISELED_BOOKSHELF -> Tag.ITEMS_BOOKSHELF_BOOKS.isTagged(item.getType());
            case JUKEBOX -> item.hasData(DataComponentTypes.JUKEBOX_PLAYABLE) && slots[slot] == null;
            case LECTERN, COMPOSTER -> false;
            case CRAFTER -> crafter != null && !crafter.isSlotDisabled(slot)
                    && (slots[slot] == null || slots[slot].getAmount() < slots[slot].getMaxStackSize() && !smallerStackExists(crafter, slots, slot));
            default -> true;
        };
    }

    private static boolean smallerStackExists(Crafter crafter, ItemStack[] slots, int base) {
        ItemStack baseItem = slots[base];
        for (int i = base + 1; i < 9; i++) {
            if (crafter.isSlotDisabled(i)) continue;
            if (slots[i] == null || slots[i].getAmount() < baseItem.getAmount() && slots[i].isSimilar(baseItem)) return true;
        }
        return false;
    }

    private static boolean matches(List<RecipeChoice> choices, ItemStack item) {
        for (RecipeChoice choice : choices) if (choice.test(item)) return true;
        return false;
    }

    private static List<RecipeChoice> reagents() {
        if (reagents == null) loadBrewing();
        return reagents;
    }

    private static List<RecipeChoice> potionInputs() {
        if (potionInputs == null) loadBrewing();
        return potionInputs;
    }

    public static void reloadBrewing() {
        reagents = null;
        potionInputs = null;
    }

    private static synchronized void loadBrewing() {
        if (reagents != null) return;
        List<RecipeChoice> reagent = new ArrayList<>(), input = new ArrayList<>();
        Bukkit.recipeIterator().forEachRemaining(recipe -> {
            if (!(recipe instanceof BrewingRecipe brewing)) return;
            reagent.add(brewing.getIngredient());
            input.add(brewing.getInput());
        });
        potionInputs = input;
        reagents = reagent;
    }

    public static boolean canCompost(Block composter, BlockFace facing, List<ItemStack> items) {
        return facing == BlockFace.DOWN && items.size() == 1 && items.get(0).getAmount() == 1
                && items.get(0).getType().isCompostable()
                && composter.getBlockData() instanceof Levelled level && level.getLevel() < 7;
    }

    public static void compost(Block composter, ItemStack item) {
        Levelled data = (Levelled) composter.getBlockData();
        boolean success = data.getLevel() == 0 || ThreadLocalRandom.current().nextDouble() < item.getType().getCompostChance();
        if (success) {
            data.setLevel(data.getLevel() + 1);
            composter.setBlockData(data, true);
        }
        composter.getWorld().playEffect(composter.getLocation(), Effect.COMPOSTER_FILL_ATTEMPT, success);
    }

    public static void drop(Block dispenser, BlockFace facing, List<ItemStack> items) {
        World world = dispenser.getWorld();
        Location at = dispenser.getLocation().add(0.5, 0.5, 0.5).add(facing.getDirection().multiply(0.7));
        at.subtract(0, facing.getModY() == 0 ? 0.15625 : 0.125, 0);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (ItemStack item : items) {
            double pow = random.nextDouble() * 0.1 + 0.2;
            Vector velocity = new Vector(triangle(random, facing.getModX() * pow), triangle(random, 0.2), triangle(random, facing.getModZ() * pow));
            world.dropItem(at, item, dropped -> dropped.setVelocity(velocity));
        }
        world.playEffect(dispenser.getLocation(), Effect.DISPENSER_DISPENSE, 0);
        world.playEffect(dispenser.getLocation(), Effect.SMOKE_SHOOT, facing);
    }

    private static double triangle(ThreadLocalRandom random, double mean) {
        return mean + 0.0172275 * 6 * (random.nextDouble() - random.nextDouble());
    }
}

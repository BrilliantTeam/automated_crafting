package com.aeltumn.autocraft.api;

import com.aeltumn.autocraft.AutomatedCrafting;
import com.aeltumn.autocraft.ConfigFile;
import com.aeltumn.autocraft.CreationListener;
import com.aeltumn.autocraft.helpers.DropperOutput;
import com.aeltumn.autocraft.helpers.ExpHopperBridge;
import com.aeltumn.autocraft.helpers.LimitSignBridge;
import com.aeltumn.autocraft.helpers.PackerBridge;
import com.aeltumn.autocraft.helpers.TrashChestBridge;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;

/**
 * Stores all the data we have on an individual autocrafter.
 */
public class Autocrafter {
    private final BlockPos position;
    private final ItemStack item;
    private boolean broken;

    Autocrafter(BlockPos position, ItemStack item) {
        this.position = position;
        this.item = item;
    }

    Autocrafter(long l, ItemStack item) {
        this(BlockPos.fromLong(l), item);
    }

    /**
     * Ticks this auto-crafter, making it craft an item.
     */
    public void tick(Chunk chunk) {
        // Don't tick if broken
        if (isBroken()) return;

        Block bl = chunk.getBlock(position.getX(), position.getY(), position.getZ());

        //If the block was broken we mark it broken and no longer save it next time.
        if (!CreationListener.isValidBlock(bl, false) || !(bl.getState() instanceof final Container container)) {
            markBroken();
            return;
        }

        // If locked we don't craft.
        if (container.isLocked())
            return;

        // If powered and not crafting based on signal we don't craft.
        if (!ConfigFile.craftOnRedstonePulse()) {
            if (bl.getBlockPower() > 0) return;
        }

        // Never craft a material that is banned
        if (!ConfigFile.isMaterialAllowed(item.getType())) return;

        outer:
        for (CraftingRecipe recipe : AutomatedCrafting.INSTANCE.getRecipeLoader().getRecipesFor(item)) {
            if (recipe == null) continue;

            // Check if the dropper contains all required items to craft the result.
            if (!recipe.containsRequirements(container.getInventory()))
                continue;

            final Directional autocrafter = (Directional) bl.getBlockData();
            final Location loc = bl.getLocation().getBlock().getRelative(autocrafter.getFacing()).getLocation();

            // Determine what craft we can perform
            var solution = recipe.findSolution(container.getInventory());
            ArrayList<ItemStack> output = new ArrayList<>(solution.getContainerItems());
            output.add(recipe.getResultDrop());

            // Remove null items
            output.removeIf(f -> f == null || f.getType().isAir());

            // Check if there's a container being output into and if it can fit the
            // items to put in. If there is a container we only craft if we can fit it.
            var front = loc.getBlock();
            var facing = autocrafter.getFacing();
            var composter = front.getType() == Material.COMPOSTER;
            var into = composter ? null : DropperOutput.containerAt(front);
            var trash = into != null && TrashChestBridge.accepts(into);
            if (composter && !DropperOutput.canCompost(front, facing, output)) continue outer;
            if (!trash && into != null) {
                if (!LimitSignBridge.allows(into, output)) continue outer;
                if (!ExpHopperBridge.allows(into, output)) continue outer;

                // A BrilliantPacker packs the output straight into its shulker boxes, so it has to
                // fit in those boxes rather than in the dispenser's own slots.
                var packable = PackerBridge.offer(into, output, true);
                if (packable >= 0) {
                    if (packable < output.stream().mapToInt(ItemStack::getAmount).sum()) continue outer;
                } else if (!DropperOutput.insert(into, facing, output, false)) {
                    continue outer;
                }
            }

            // Perform the actual crafting
            solution.applyTo(container.getInventory());

            if (trash) {
                output.clear();
            } else if (composter) {
                DropperOutput.compost(front, output.get(0));
            } else if (into != null) {
                if (PackerBridge.offer(into, output, false) < 0) DropperOutput.insert(into, facing, output, true);
            } else {
                DropperOutput.drop(bl, facing, output);
            }

            AutomatedCrafting.INSTANCE.skin.pulse(bl, 30);

            // If a recipe was found we stop here, never craft two things
            break;
        }
    }

    /**
     * Returns whether this autocrafter is broken.
     */
    public boolean isBroken() {
        return broken;
    }

    /**
     * Marks this autocrafter as broken. A broken autocrafter is not saved to disk.
     */
    public void markBroken() {
        broken = true;
    }

    /**
     * The relative position of this autocrafter in the chunk.
     */
    public BlockPos getPosition() {
        return position;
    }

    /**
     * Get the position of this autocrafter as a long.
     */
    public long getPositionAsLong() {
        return position.toLong();
    }

    /**
     * Returns whether or not the in-chunk coordinates of this autocrafter equal
     * the input numbers.
     */
    boolean equals(BlockPos pos) {
        return this.position.equals(pos);
    }

    public ItemStack getItem() {
        return item;
    }

    @Override
    public String toString() {
        return "Autocrafter{" +
                "position=" + getPosition() +
                ", positionLong=" + getPositionAsLong() +
                ", item=" + item +
                ", broken=" + broken +
                '}';
    }
}

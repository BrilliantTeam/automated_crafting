package com.aeltumn.autocraft.helpers;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;

public final class PackerBridge {
    private static Method offer;
    private static boolean resolved;

    private PackerBridge() {
    }

    public static int offer(Inventory destination, Collection<ItemStack> items, boolean simulate) {
        Method method = method();
        if (method == null) return -1;
        try {
            return (int) method.invoke(null, destination, items, simulate);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private static Method method() {
        if (!resolved) {
            resolved = true;
            Plugin packer = Bukkit.getPluginManager().getPlugin("BrilliantPacker");
            if (packer != null && packer.isEnabled()) {
                try {
                    offer = packer.getClass().getMethod("offer", Inventory.class, Collection.class, boolean.class);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return offer;
    }
}

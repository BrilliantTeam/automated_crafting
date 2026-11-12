package com.aeltumn.autocraft.helpers;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;

public final class ExpHopperBridge {
    private static Method accepts;
    private static boolean resolved;

    private ExpHopperBridge() {
    }

    public static boolean allows(Inventory destination, Collection<ItemStack> items) {
        Method method = method();
        if (method == null) return true;
        try {
            return (boolean) method.invoke(null, destination, items);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private static Method method() {
        if (!resolved) {
            resolved = true;
            Plugin hopper = Bukkit.getPluginManager().getPlugin("BrilliantExpHopper");
            if (hopper != null && hopper.isEnabled()) {
                try {
                    accepts = hopper.getClass().getMethod("accepts", Inventory.class, Collection.class);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return accepts;
    }
}

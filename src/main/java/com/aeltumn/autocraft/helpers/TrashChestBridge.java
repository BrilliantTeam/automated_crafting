package com.aeltumn.autocraft.helpers;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public final class TrashChestBridge {
    private static Method accepts;
    private static boolean resolved;

    private TrashChestBridge() {
    }

    public static boolean accepts(Inventory destination) {
        Method method = method();
        if (method == null) return false;
        try {
            return (boolean) method.invoke(null, destination);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Method method() {
        if (!resolved) {
            resolved = true;
            Plugin trash = Bukkit.getPluginManager().getPlugin("BrilliantTrashChest");
            if (trash != null && trash.isEnabled()) {
                try {
                    accepts = trash.getClass().getMethod("accepts", Inventory.class);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return accepts;
    }
}

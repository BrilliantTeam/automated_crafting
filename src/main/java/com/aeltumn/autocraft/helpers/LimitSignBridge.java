package com.aeltumn.autocraft.helpers;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;

public final class LimitSignBridge {
    private static Method allowsAll;
    private static boolean resolved;

    private LimitSignBridge() {
    }

    public static boolean allows(@NotNull Inventory destination, @NotNull Collection<ItemStack> items) {
        Method method = method();
        if (method == null) return true;
        try {
            return (boolean) method.invoke(null, destination, items);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return true;
        }
    }

    private static @Nullable Method method() {
        if (!resolved) {
            resolved = true;
            if (Bukkit.getPluginManager().isPluginEnabled("BrilliantLimitSign")) {
                try {
                    allowsAll = Class.forName("Rice.Chen.LimitSign.LimitSign")
                            .getMethod("allowsAll", Inventory.class, Collection.class);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return allowsAll;
    }
}

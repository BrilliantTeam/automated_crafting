package com.aeltumn.autocraft;

import com.aeltumn.autocraft.api.CraftingRecipe;
import com.aeltumn.autocraft.api.RecipeType;
import com.aeltumn.autocraft.helpers.DropperOutput;
import com.aeltumn.autocraft.helpers.LRUCache;
import com.aeltumn.autocraft.impl.BukkitRecipe;
import com.aeltumn.autocraft.impl.recipes.FireworksRecipe;
import com.aeltumn.autocraft.impl.recipes.SuspicousStewRecipe;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;

public class RecipeLoader {
    private final AutomatedCrafting instance;
    private volatile Set<NamespacedKey> loaded = Set.of();
    private volatile Set<CraftingRecipe> recipes = Set.of();

    public RecipeLoader(final AutomatedCrafting inst) {
        instance = inst;

        //We load the recipes the first tick after the server is done starting so
        //all recipes are loaded from data packs. We don't load asynchronously.
        Bukkit.getGlobalRegionScheduler().run(inst, (ignored) -> {
            reload(null);
        });
    }

    /**
     * Get a list containing the names of all files that were loaded, includes .json!
     * For example:
     * [ acacia_boat.json, iron_ingot.json, furnace.json ]
     */
    @Deprecated(since = "2.6")
    public Set<String> getLoadedFileNames() {
        return loaded.stream().map(f -> f.getKey() + ".json").collect(Collectors.toSet());
    }

    /**
     * Returns a list of the keys of all loaded recipes.
     */
    public Set<NamespacedKey> getLoadedKeys() {
        return loaded;
    }

    /**
     * Returns the list of recipes that have been loaded.
     */
    public Set<CraftingRecipe> getLoadedRecipes() {
        return recipes;
    }

    /**
     * Get all recipes that will create the given itemstack. If the given itemstack
     * has a displayname and the recipe result does not the recipe will not be returned!
     */
    private final LRUCache<ItemStack, Set<CraftingRecipe>> lruRecipeCache = new LRUCache<>(125);
    public Set<CraftingRecipe> getRecipesFor(final ItemStack item) {
        if (lruRecipeCache.has(item))
            return lruRecipeCache.get(item);

        Set<CraftingRecipe> snapshot = recipes;
        Set<CraftingRecipe> recipeSet = snapshot.stream().filter(f -> f.creates(item)).collect(Collectors.toSet());
        if (snapshot == recipes) lruRecipeCache.put(item, recipeSet);
        return recipeSet;
    }

    /**
     * Reloads all recipes from the minecraft jar and otherwise from Bukkit.
     */
    protected synchronized void reload(final CommandSender listener) {
        if (listener != null)
        //    listener.sendMessage("(Re)loading recipes...");
        //instance.getLogger().info("(Re)loading recipes...");
            listener.sendMessage("§7｜§6系統§7｜§f飯娘：§7正在重新載入合成配方 ...");
        instance.getLogger().info("自動合成器：正在重新載入合成配方 ...");

        long t = System.currentTimeMillis();
        DropperOutput.reloadBrewing();
        Set<NamespacedKey> newLoaded = new HashSet<>();
        Set<CraftingRecipe> newRecipes = new HashSet<>();

        // Load recipes from Bukkit, skip any that we already know.
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof Keyed) add(newLoaded, newRecipes, new BukkitRecipe(recipe));
        }

        // Custom recipes
        add(newLoaded, newRecipes, new FireworksRecipe(NamespacedKey.minecraft("fireworks_duration_1"), 1));
        add(newLoaded, newRecipes, new FireworksRecipe(NamespacedKey.minecraft("fireworks_duration_2"), 2));
        add(newLoaded, newRecipes, new FireworksRecipe(NamespacedKey.minecraft("fireworks_duration_3"), 3));

        for (var ingredient : SuspicousStewRecipe.INGREDIENTS.keySet()) {
            add(newLoaded, newRecipes, new SuspicousStewRecipe(NamespacedKey.minecraft("suspicious_stew_" + ingredient.name().toLowerCase()), ingredient));
        }

        swap(newLoaded, newRecipes);

        // Check if any recipes were loaded
        long j = recipes.size();
        if (j > 0) {
            if (listener != null)
            //    listener.sendMessage("(Re)loaded " + j + " recipes, took " + (System.currentTimeMillis() - t) + " ms...");
            //instance.getLogger().info("(Re)loaded " + j + " recipes, took " + (System.currentTimeMillis() - t) + " ms...");
                listener.sendMessage("§7｜§6系統§7｜§f飯娘：§7成功載入 " + j + " 個合成配方，耗時 " + (System.currentTimeMillis() - t) + " ms");
            instance.getLogger().info("自動合成器：成功載入 " + j + " 個合成配方，耗時 " + (System.currentTimeMillis() - t) + " ms");
        }
    }

    /**
     * Loads a new recipe directly from a {@link CraftingRecipe} instance.
     */
    public synchronized void loadRecipe(CraftingRecipe recipe) {
        Set<NamespacedKey> newLoaded = new HashSet<>(loaded);
        Set<CraftingRecipe> newRecipes = new HashSet<>(recipes);
        if (add(newLoaded, newRecipes, recipe)) swap(newLoaded, newRecipes);
    }

    private static boolean add(Set<NamespacedKey> loaded, Set<CraftingRecipe> recipes, CraftingRecipe recipe) {
        // Ignore recipes that have already been loaded
        if (loaded.contains(recipe.getKey())) return false;

        // Ignore all unknown recipes
        if (recipe.getType() == RecipeType.UNKNOWN) return false;

        recipes.add(recipe);
        loaded.add(recipe.getKey());
        return true;
    }

    private void swap(Set<NamespacedKey> newLoaded, Set<CraftingRecipe> newRecipes) {
        loaded = Collections.unmodifiableSet(newLoaded);
        recipes = Collections.unmodifiableSet(newRecipes);
        lruRecipeCache.clear();
    }

    /**
     * Loads a new recipe from a {@link Recipe} instance.
     */
    public void loadRecipe(Recipe recipe) {
        if (recipe instanceof Keyed) {
            BukkitRecipe bukkitRecipe = new BukkitRecipe(recipe);
            loadRecipe(bukkitRecipe);
        }
    }
}

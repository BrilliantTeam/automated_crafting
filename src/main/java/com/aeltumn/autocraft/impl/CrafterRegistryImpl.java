package com.aeltumn.autocraft.impl;

import com.aeltumn.autocraft.AutomatedCrafting;
import com.aeltumn.autocraft.ConfigFile;
import com.aeltumn.autocraft.MachineSkin;
import com.aeltumn.autocraft.api.*;
import com.aeltumn.autocraft.helpers.ReflectionHelper;
import com.aeltumn.autocraft.helpers.SerializedItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Rotation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.block.Container;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public class CrafterRegistryImpl extends CrafterRegistry {
    public static final int VERSION = 2;
    private ScheduledTask mainTick;

    public CrafterRegistryImpl(JavaPlugin jp) {
        super();

        if (!ConfigFile.craftOnRedstonePulse()) {
            var speed = ConfigFile.ticksPerCraft();
            Runnable task = new MainCrafterTick(this);
            mainTick = Bukkit.getGlobalRegionScheduler().runAtFixedRate(jp, (ignored) -> task.run(), speed, speed);
        } else {
            mainTick = null;
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();

        if (mainTick != null) {
            mainTick.cancel();
        }
    }

    @Override
    public boolean isAutocrafter(Block block) {
        var bp = new BlockPos(block);
        return getAutocrafters(block.getWorld()).map(f -> f.get(bp) != null).orElse(false);
    }

    public boolean ready(Block block) {
        var m = getAutocrafters(block.getWorld()).map(f -> f.get(new BlockPos(block))).orElse(null);
        if (m == null || m.getItem() == null || !ConfigFile.isMaterialAllowed(m.getItem().getType())) return false;
        if (recipeLoader.getLoadedRecipes().isEmpty()) return true;
        var recipes = recipeLoader.getRecipesFor(m.getItem());
        return recipes != null && !recipes.isEmpty();
    }

    private static ItemFrame frame(Block block) {
        for (ItemFrame f : block.getWorld().getNearbyEntitiesByType(ItemFrame.class, block.getLocation().add(0.5, 0.5, 0.5), 1.5)) {
            if (f.getLocation().getBlock().getRelative(f.getAttachedFace()).equals(block)) return f;
        }
        return null;
    }

    private static String encode(ItemStack item) {
        return item == null || item.getType().isAir() ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack decode(String raw) {
        return raw.isEmpty() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode(raw));
    }

    public String carry(Block block) {
        var m = getAutocrafters(block.getWorld()).map(f -> f.get(new BlockPos(block))).orElse(null);
        if (m == null) return null;
        ItemFrame frame = frame(block);
        if (frame == null) return encode(m.getItem());
        return String.join("|", encode(m.getItem()), frame.getFacing().name(), String.valueOf(frame instanceof GlowItemFrame),
                frame.getRotation().name(), encode(frame.getItem()), String.valueOf(frame.isVisible()), String.valueOf(frame.isFixed()));
    }

    public void lift(Block block) {
        ItemFrame frame = frame(block);
        if (frame != null) frame.remove();
        destroy(block.getLocation());
    }

    public void place(Block block, String data, int rot) {
        String[] part = data.split("\\|", -1);
        ItemStack item = decode(part[0]);
        getOrCreateAutocrafters(block.getWorld()).destroy(new BlockPos(block));
        if (item != null) getOrCreateAutocrafters(block.getWorld()).add(new BlockPos(block), item);
        markDirty();
        if (part.length < 7) return;
        BlockFace face = MachineSkin.turn(BlockFace.valueOf(part[1]), rot);
        ItemStack shown = decode(part[4]);
        Class<? extends ItemFrame> type = Boolean.parseBoolean(part[2]) ? GlowItemFrame.class : ItemFrame.class;
        block.getWorld().spawn(block.getRelative(face).getLocation(), type, f -> {
            f.setFacingDirection(face, true);
            if (shown != null) f.setItem(shown, false);
            f.setRotation(Rotation.valueOf(part[3]));
            f.setVisible(Boolean.parseBoolean(part[5]));
            f.setFixed(Boolean.parseBoolean(part[6]));
        });
    }

    @Override
    public void tick(Block block) {
        var bp = new BlockPos(block);
        var crafter = getAutocrafters(block.getWorld()).map(f -> f.get(bp)).orElse(null);
        if (crafter == null) return;
        crafter.tick(block.getChunk());
    }

    @Override
    public boolean checkBlock(final Location location, final Player player) {
        final Block block = location.getBlock();
        final BlockPos pos = new BlockPos(block);
        final Autocrafter m = getAutocrafters(location.getWorld()).map(f -> f.get(pos)).orElse(null);
        if ((!(block.getState() instanceof Container)))
            return false;

        final Container container = (Container) block.getState();
        if (m == null) return false;
        if (container.isLocked() || block.getBlockPower() > 0) {
            if (container.isLocked())
                //player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("Autocrafter is locked"));
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("§7｜§6系統§7｜§f飯娘：§c此自動合成器已被鎖定。"));
            else
                //player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("Autocrafter has redstone signal blocking it"));
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("§7｜§6系統§7｜§f飯娘：§c有一個紅石訊號阻止了該操作，請先取消任何紅石訊號。"));
            return true;
        }

        if (!ConfigFile.isMaterialAllowed(m.getItem().getType())) {
            //player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("Crafting this item is disabled"));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("§7｜§6系統§7｜§f飯娘：§c此合成配方已被停用。"));
            return true;
        }

        final Set<CraftingRecipe> recipes = recipeLoader.getRecipesFor(m.getItem());
        if (recipes == null || recipes.size() == 0) {
            //player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("Autocrafter can't craft this item"));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("§7｜§6系統§7｜§f飯娘：§c此物品無法被設定為自動合成器配方。"));
            return false;
        }

        //Inform the player how many recipes are being accepted right now
        //player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("Autocrafter is accepting " + recipes.size() + " recipe(s)"));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, getText("§7｜§6系統§7｜§f飯娘：§7已成功設定自動合成器配方。"));
        AutomatedCrafting.INSTANCE.skin.show(block);
        return true;
    }

    private BaseComponent[] getText(final String text) {
        return TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', text));
    }

    @Override
    public boolean create(final Location l, final Player p, final ItemStack type) {
        if (!p.hasPermission("automatedcrafting.makeautocrafters") || type == null)
            return false;
        BlockPos el = new BlockPos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        AutocrafterPositions am = getOrCreateAutocrafters(l.getWorld());
        am.destroy(el); //Destroy old ones
        am.add(el, type);  //Add the new one
        markDirty();
        return true;
    }

    @Override
    public void destroy(final Location l) {
        BlockPos el = new BlockPos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        Optional<AutocrafterPositions> m = getAutocrafters(l.getWorld());
        m.ifPresent(am -> am.destroy(el));
        markDirty();
        AutomatedCrafting.INSTANCE.skin.hide(l.getBlock());
    }

    @Override
    public void load() {
        if (!AutomatedCrafting.INSTANCE.getDataFolder().exists()) AutomatedCrafting.INSTANCE.getDataFolder().mkdirs();
        File legacyFile = new File(AutomatedCrafting.INSTANCE.getDataFolder(), "droppers.json");
        boolean legacyLoaded = false;

        //Reset stored crafter information
        crafters = new ConcurrentHashMap<>();

        //Legacyload
        if (legacyFile.exists()) {
            try {
                FileReader fr = new FileReader(legacyFile);
                JsonReader jr = new JsonReader(fr);
                jr.beginObject();
                Gson g = new GsonBuilder().create();
                while (jr.hasNext()) {
                    String n = jr.nextName();
                    LegacyBlockPos lbp = g.fromJson(jr, LegacyBlockPos.class);
                    ItemStack it = AutomatedCrafting.GSON.fromJson(n, LegacySerializedItem.class).getItem();
                    AutocrafterPositions m = crafters.getOrDefault(lbp.world, new AutocrafterPositions());
                    m.add(new BlockPos(lbp.x, lbp.y, lbp.z), it);
                    if (!m.isEmpty())
                        crafters.put(lbp.world, m);
                }
                jr.endObject();
                jr.close();
                fr.close();
                AutomatedCrafting.INSTANCE.info("Loaded autocrafters from legacy configuration file!");
            } catch (Exception x) {
                AutomatedCrafting.INSTANCE.warning("An error occurred whilst legacy loading autocrafters from an old configuration file. Please rebuild all autocrafters!");
            }

            legacyFile.delete(); //Remove old file
            legacyLoaded = true;
        }

        //Load modern file
        boolean converted = false;
        if (file.exists()) {
            boolean legacyItemNames = false;

            try {
                FileReader fr = new FileReader(file);
                JsonReader jr = new JsonReader(fr);
                if (!jr.hasNext()) return;
                if (jr.peek() != JsonToken.BEGIN_OBJECT) return;
                jr.beginObject();
                jr.nextName();
                int version = jr.nextInt();
                if (version != VERSION) {
                    // enable the converted flag so we immediately save the updated
                    // configuration file
                    converted = true;
                    if (version == 1) {
                        legacyItemNames = true;
                    } else {
                        AutomatedCrafting.INSTANCE.warning("You were running an old unsupported version of AutomatedCrafting (file version " + version + ", current version: " + VERSION + ") and all exsisting autocrafters have been invalidated, sorry! (every autocrafter will need to be rebuilt)");
                        return;
                    }

                    AutomatedCrafting.INSTANCE.info("Loading configuration file from an older version of AutomatedCrafting. (file version " + version + ", current version: " + VERSION + ") The file will automatically be converted to the new version.");
                }
                while (jr.hasNext()) {
                    String world = jr.nextName();
                    AutocrafterPositions m = new AutocrafterPositions();
                    jr.beginObject();
                    while (jr.hasNext()) {
                        String n = jr.nextName(); //Chunk identifier code
                        ChunkIdentifier ci;
                        try {
                            ci = new ChunkIdentifier(Long.parseLong(n));
                        } catch (NumberFormatException ignored) {
                            //Skip through the data for this chunk
                            jr.beginObject();
                            while (jr.hasNext()) {
                                jr.nextName();
                                jr.skipValue();
                            }
                            jr.endObject();
                            continue;
                        }
                        jr.beginObject();
                        while (jr.hasNext()) {
                            String n2 = jr.nextName(); //Chunk identifier code
                            long l;
                            try {
                                l = Long.parseLong(n2);
                            } catch (NumberFormatException ignored) {
                                jr.skipValue();
                                continue;
                            }
                            //Update method of saving items to json
                            m.add(ci, l, ((SerializedItem) AutomatedCrafting.GSON.fromJson(jr, SerializedItem.class)).getItem(legacyItemNames));
                        }
                        jr.endObject();
                    }
                    jr.endObject();

                    //If this world has data we add it to the full list
                    if (!m.isEmpty())
                        crafters.put(world, m);
                }
                jr.endObject();
                jr.close();
                fr.close();
            } catch (Exception x) {
                x.printStackTrace();
                AutomatedCrafting.INSTANCE.warning("An error occurred whilst reading autocrafters from the configuration file. Please rebuild all autocrafters!");
            }
        }

        if (legacyLoaded || converted) {
            forceSave();
        }
    }

    @Override
    public void forceSave() {
        saveTime = Long.MAX_VALUE;

        try {
            File tmp = new File(file.getPath() + ".tmp");
            FileWriter fw = new FileWriter(tmp);
            JsonWriter jw = new JsonWriter(fw);
            jw.setIndent("  ");
            jw.beginObject();
            jw.name("version");
            jw.value(VERSION);

            for (String s : getWorldsRegistered()) {
                Optional<AutocrafterPositions> m = getAutocrafters(s);
                if (!m.isPresent()) continue;
                if (m.get().isEmpty()) continue;

                jw.name(s);
                jw.beginObject();
                for (ChunkIdentifier ci : m.get().listChunks()) {
                    jw.name(String.valueOf(ci.toLong()));
                    ArrayList<Autocrafter> positions = m.get().getInChunk(ci);
                    jw.beginObject();
                    for (Autocrafter a : positions) {
                        if (a.isBroken()) continue; //Don't save broken ones.
                        jw.name(String.valueOf(a.getPositionAsLong()));
                        jw.jsonValue(AutomatedCrafting.GSON.toJson(new SerializedItem(a.getItem())));
                    }
                    jw.endObject();
                }
                jw.endObject();
            }
            jw.endObject();
            jw.flush();
            jw.close();
            fw.close();
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable x) {
            x.printStackTrace();
        }

        saveTime = Long.MAX_VALUE; //Save again at the end of time.
    }

    //Used for legacy loading of old data files.
    public static class LegacyBlockPos {
        private int x, y, z;
        private String world;
    }

    //Used for legacy loading of old data files.
    public static class LegacySerializedItem {
        private static final Class<?> mojangsonParser = ReflectionHelper.getNMSClass("nbt.MojangsonParser").orElse(null);
        private static final Class<?> craftItemStack = ReflectionHelper.getCraftBukkitClass("inventory.CraftItemStack").orElse(null);
        private static final Class<?> nbtTagCompound = ReflectionHelper.getNMSClass("nbt.NBTTagCompound").orElse(null);
        private static final Class<?> itemStack = ReflectionHelper.getNMSClass("world.item.ItemStack").orElse(null);

        private Map<String, Object> item;
        private Map<String, Object> meta;
        private String nbt;

        public LegacySerializedItem(ItemStack item) {
            build(item);
        }

        public ItemStack getItem() {
            if (this.item == null) return null;
            ItemStack ret = ItemStack.deserialize(this.item);
            if (meta != null)
                ret.setItemMeta((ItemMeta) ConfigurationSerialization.deserializeObject(meta, ConfigurationSerialization.getClassByAlias("ItemMeta")));
            try {
                Object tag = mojangsonParser.getMethod("parse", String.class).invoke(null, nbt);
                Object nullObject = null;
                Object nmsStack = craftItemStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, ret);
                if (!tag.toString().equalsIgnoreCase("{}"))
                    nmsStack.getClass().getMethod("setTag", nbtTagCompound).invoke(nmsStack, tag);
                else nmsStack.getClass().getMethod("setTag", nbtTagCompound).invoke(nmsStack, nullObject);
                ret = (ItemStack) craftItemStack.getMethod("asCraftMirror", itemStack).invoke(null, nmsStack);
            } catch (Exception x) {
                x.printStackTrace();
            }
            return ret;
        }

        private void build(ItemStack item) {
            if (item == null) return;
            if (item.hasItemMeta()) meta = item.getItemMeta().serialize();
            ItemStack copy = item.clone();
            copy.setItemMeta(null);
            this.item = copy.serialize();
            try {
                Object nmsStack = craftItemStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
                Object tag = nbtTagCompound.newInstance();
                if ((boolean) nmsStack.getClass().getMethod("hasTag").invoke(nmsStack))
                    tag = nmsStack.getClass().getMethod("getTag").invoke(nmsStack);
                nbt = tag.toString();
            } catch (Exception x) {
                x.printStackTrace();
            }
        }
    }
}

package com.aeltumn.autocraft;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public final class MachineSkin implements Listener {
    private static final BlockFace[] AROUND = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final Plugin plugin;
    private final Function<Block, String> typeOf;
    private final boolean dispensePulse;
    private final NamespacedKey key;
    private final Map<String, Integer> pulses = new ConcurrentHashMap<>();
    private final Map<String, Boolean> spinning = new ConcurrentHashMap<>();
    private Function<Block, String> take;
    private Consumer<Block> drop;
    private Put put;

    public interface Put {
        void put(Block block, String data, int rot);
    }

    public MachineSkin(Plugin plugin, Function<Block, String> typeOf, boolean dispensePulse) {
        this.plugin = plugin;
        this.typeOf = typeOf;
        this.dispensePulse = dispensePulse;
        this.key = new NamespacedKey(plugin, "machine_skin");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public MachineSkin carry(Function<Block, String> take, Consumer<Block> drop, Put put) {
        this.take = take;
        this.drop = drop;
        this.put = put;
        return this;
    }

    public String carry(Block block) {
        return take == null || !housing(block.getType()) ? null : take.apply(block);
    }

    public void release(Block block) {
        if (drop != null) drop.accept(block);
        hide(block);
    }

    public void settle(Block block, String data, int rot) {
        if (put != null && housing(block.getType())) put.put(block, data, rot);
        ensure(block);
    }

    public static BlockFace turn(BlockFace face, int rot) {
        for (int i = 0; i < (rot & 3); i++) {
            face = switch (face) {
                case NORTH -> BlockFace.EAST;
                case EAST -> BlockFace.SOUTH;
                case SOUTH -> BlockFace.WEST;
                case WEST -> BlockFace.NORTH;
                default -> face;
            };
        }
        return face;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("skin", true);
    }

    private static boolean housing(Material type) {
        return type == Material.DISPENSER || type == Material.DROPPER;
    }

    private static String tag(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private String type(Block block) {
        if (!housing(block.getType())) return null;
        try {
            return typeOf.apply(block);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ItemStack model(String type, boolean active) {
        return Bukkit.getItemFactory().createItemStack("minecraft:paper[minecraft:item_model=\"brilliant:machine/" + type + (active ? "_active" : "") + "\"]");
    }

    private static Transformation shape(Block block) {
        Quaternionf turn = new Quaternionf();
        if (block.getBlockData() instanceof Directional directional) {
            BlockFace face = directional.getFacing();
            turn.rotationTo(new Vector3f(0, 0, 1), new Vector3f(face.getModX(), face.getModY(), face.getModZ()));
        }
        return new Transformation(new Vector3f(), turn, new Vector3f(1.004f), new Quaternionf());
    }

    private static Display.Brightness light(Block block) {
        int sky = 0, glow = 0;
        for (BlockFace face : AROUND) {
            Block near = block.getRelative(face);
            sky = Math.max(sky, near.getLightFromSky());
            glow = Math.max(glow, near.getLightFromBlocks());
        }
        return new Display.Brightness(glow, sky);
    }

    private String skinType(ItemDisplay d) {
        String raw = d.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null) return null;
        int bar = raw.indexOf('|');
        return bar < 0 ? "" : raw.substring(bar + 1);
    }

    private List<ItemDisplay> find(Block block) {
        String want = tag(block);
        return block.getWorld().getNearbyEntitiesByType(ItemDisplay.class, block.getLocation().add(0.5, 0.5, 0.5), 0.6).stream()
                .filter(d -> {
                    String raw = d.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                    return raw != null && raw.split("\\|", 2)[0].equals(want);
                })
                .toList();
    }

    public void show(Block block) {
        hide(block);
        String type = type(block);
        if (!enabled() || type == null) return;
        block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ItemDisplay.class, d -> {
            d.setPersistent(true);
            d.setItemStack(model(type, false));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(shape(block));
            d.setBrightness(light(block));
            d.setViewRange(1.5f);
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag(block) + "|" + type);
        });
    }

    public void hide(Block block) {
        String at = tag(block);
        spinning.remove(at);
        pulses.remove(at);
        for (ItemDisplay d : find(block)) d.remove();
    }

    public void ensure(Block block) {
        String type = enabled() ? type(block) : null;
        List<ItemDisplay> skins = find(block);
        if (type == null) {
            if (!skins.isEmpty()) hide(block);
            return;
        }
        if (skins.size() == 1 && type.equals(skinType(skins.get(0)))) return;
        show(block);
    }

    public void pulse(Block block, int ticks) {
        if (!enabled()) return;
        String type = type(block);
        if (type == null) return;
        List<ItemDisplay> skins = find(block);
        if (skins.isEmpty()) return;
        String at = tag(block);
        Integer prev = pulses.get(at);
        int id = prev == null ? 1 : prev + 1;
        pulses.put(at, id);
        for (ItemDisplay d : skins) {
            if (prev == null) {
                d.setBrightness(light(block));
                d.setItemStack(model(type, true));
            }
            d.getScheduler().runDelayed(plugin, t -> {
                if (!pulses.remove(at, id)) return;
                if (!Boolean.TRUE.equals(spinning.get(at))) d.setItemStack(model(type, false));
            }, null, Math.max(1, ticks));
        }
    }

    public void spin(Block block, boolean on) {
        String at = tag(block);
        Boolean was = spinning.get(at);
        if (was != null && was == on) return;
        if (on) spinning.put(at, true);
        else spinning.remove(at);
        String type = type(block);
        if (!enabled() || type == null || was == null && !on) return;
        for (ItemDisplay d : find(block)) {
            if (on) d.setBrightness(light(block));
            d.setItemStack(model(type, on));
        }
    }

    private void later(Block block, Runnable task) {
        Bukkit.getRegionScheduler().runDelayed(plugin, block.getLocation(), t -> task.run(), 2L);
    }

    private void recheckAround(Block center) {
        later(center, () -> {
            if (housing(center.getType())) ensure(center);
            for (BlockFace face : AROUND) {
                Block near = center.getRelative(face);
                if (housing(near.getType())) ensure(near);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Chunk chunk = event.getChunk();
        Set<String> skinned = new HashSet<>();
        for (Entity e : event.getEntities()) {
            if (!(e instanceof ItemDisplay d)) continue;
            String raw = d.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (raw == null) continue;
            String[] part = raw.split("\\|", 2)[0].split(",");
            Block block = chunk.getWorld().getBlockAt(Integer.parseInt(part[0]), Integer.parseInt(part[1]), Integer.parseInt(part[2]));
            String type = enabled() ? type(block) : null;
            if (type == null || !type.equals(skinType(d)) || !skinned.add(tag(block))) {
                d.remove();
                continue;
            }
            pulses.remove(tag(block));
            d.setItemStack(model(type, Boolean.TRUE.equals(spinning.get(tag(block)))));
        }
        if (!enabled()) return;
        for (BlockState state : chunk.getTileEntities(b -> housing(b.getType()) && !skinned.contains(tag(b)), false)) {
            Block block = state.getBlock();
            if (type(block) != null) show(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        recheckAround(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (housing(block.getType())) hide(block);
        else recheckAround(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent event) {
        recheckAround(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnhang(HangingBreakEvent event) {
        Hanging hanging = event.getEntity();
        recheckAround(hanging.getLocation().getBlock().getRelative(hanging.getAttachedFace()));
    }

    private void lost(List<Block> blocks) {
        for (Block block : blocks) if (housing(block.getType())) hide(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        lost(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        lost(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        lost(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        lost(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDispense(BlockDispenseEvent event) {
        if (dispensePulse) pulse(event.getBlock(), 20);
    }
}

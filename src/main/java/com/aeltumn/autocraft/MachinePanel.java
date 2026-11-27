package com.aeltumn.autocraft;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class MachinePanel implements Listener {
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build();
    private static final TextColor TITLE = TextColor.color(0xFAEDE7);
    private static final String PREFIX = "§7｜§6系統§7｜§f飯娘：";

    private final Predicate<Block> owns;

    public MachinePanel(Plugin plugin, Predicate<Block> owns) {
        this.owns = owns;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || !player.isSneaking() || !player.getInventory().getItemInMainHand().isEmpty()) return;
        if (!owns.test(block)) return;
        Dialog dialog = collect(player, block);
        if (dialog == null) return;
        event.setCancelled(true);
        player.showDialog(dialog);
        opened(player, dialog);
    }

    private static final Map<String, String> ICONS = Map.ofEntries(Map.entry("物品複製器", "duper"), Map.entry("物品打包器", "packer"),
            Map.entry("經驗搜集器", "exp_hopper"), Map.entry("物品回流器", "reflux"), Map.entry("物品傳輸器", "transporter_in"),
            Map.entry("動物餵食器", "feeder"), Map.entry("實體打擊器", "damager"), Map.entry("方塊破壞器", "breaker"),
            Map.entry("方塊放置器", "placer"), Map.entry("銷毀儲物箱", "trash"), Map.entry("紅石剝皮器", "stripper"),
            Map.entry("紅石土徑器", "pather"), Map.entry("物品過濾", "hopper"),
            Map.entry("輝煌合成器", "crafter"));

    public static Dialog collect(Player player, Block block) {
        List<Map.Entry<?, ?>> dialogs = new ArrayList<>();
        List<Map.Entry<?, ?>> actions = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            try {
                Object panel = plugin.getClass().getMethod("machinePanel", Player.class, Block.class).invoke(plugin, player, block);
                if (!(panel instanceof Map.Entry<?, ?> entry) || !(entry.getKey() instanceof String)) continue;
                if (entry.getValue() instanceof Dialog) dialogs.add(entry);
                else if (entry.getValue() instanceof Runnable) actions.add(entry);
            } catch (NoSuchMethodException | LinkageError ignored) {
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().warning("machinePanel failed: " + e);
            }
        }
        if (dialogs.isEmpty()) return null;
        if (dialogs.size() == 1 && actions.isEmpty()) return (Dialog) dialogs.getFirst().getValue();

        List<MachineView.MenuEntry> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : dialogs) {
            Dialog dialog = (Dialog) entry.getValue();
            entries.add(new MachineView.MenuEntry(ICONS.get((String) entry.getKey()), (String) entry.getKey(),
                    ClickEvent.callback(audience -> {
                        player.showDialog(dialog);
                        opened(player, dialog);
                    }, ONCE)));
        }
        for (Map.Entry<?, ?> entry : actions) {
            entries.add(new MachineView.MenuEntry(ICONS.get((String) entry.getKey()), (String) entry.getKey(),
                    ClickEvent.callback(audience -> ((Runnable) entry.getValue()).run(), ONCE)));
        }
        Component body = new MachineView(UiFont.shared()).menu(entries);
        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("選擇要設定的項目", TITLE)).pause(false)
                        .body(List.of(DialogBody.plainMessage(body, UiFont.shared().messageWidth()))).build())
                .type(DialogType.notice(ActionButton.builder(Component.text("關閉", NamedTextColor.GRAY)).width(150).build())));
    }

    public static void opened(Player player, Dialog dialog) {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            try {
                plugin.getClass().getMethod("machineOpened", Player.class, Dialog.class).invoke(plugin, player, dialog);
            } catch (NoSuchMethodException | LinkageError ignored) {
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().warning("machineOpened failed: " + e);
            }
        }
    }

    public static boolean canEdit(Player player, Block block, UUID owner) {
        if (player.getUniqueId().equals(owner)) return true;
        Plugin residence = Bukkit.getPluginManager().getPlugin("Residence");
        if (residence == null || !residence.isEnabled()) return true;
        try {
            ClassLoader loader = residence.getClass().getClassLoader();
            if ((boolean) Class.forName("com.bekvon.bukkit.residence.containers.ResAdmin", true, loader)
                    .getMethod("isResAdmin", Player.class).invoke(null, player)) return true;
            Class<?> flags = Class.forName("com.bekvon.bukkit.residence.containers.Flags", true, loader);
            Object build = flags.getField("build").get(null);
            Object perms = Class.forName("com.bekvon.bukkit.residence.protection.FlagPermissions", true, loader)
                    .getMethod("getPerms", Location.class, Player.class).invoke(null, block.getLocation(), player);
            return (boolean) perms.getClass().getMethod("playerHas", Player.class, flags, boolean.class).invoke(perms, player, build, true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static boolean permits(UUID player, Location loc, String... chain) {
        Plugin residence = Bukkit.getPluginManager().getPlugin("Residence");
        if (residence == null || !residence.isEnabled()) return true;
        try {
            ClassLoader loader = residence.getClass().getClassLoader();
            Class<?> flags = Class.forName("com.bekvon.bukkit.residence.containers.Flags", true, loader);
            Object first = flags.getField(chain[0]).get(null);
            if (!(boolean) flags.getMethod("isGlobalyEnabled").invoke(first)) return true;
            Object perms = Class.forName("com.bekvon.bukkit.residence.protection.FlagPermissions", true, loader)
                    .getMethod("getPerms", Location.class).invoke(null, loc);
            var has = perms.getClass().getMethod("playerHas", UUID.class, String.class, flags, boolean.class);
            boolean result = true;
            for (int i = chain.length - 1; i >= 0; i--) {
                result = (boolean) has.invoke(perms, player, loc.getWorld().getName(), flags.getField(chain[i]).get(null), result);
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static String misplaced(String name, String target) {
        return name + "的告示牌要貼在" + target + "上（貼側面、立上方或吊下方）。";
    }

    public static String occupied(String name) {
        return "這裡已經是" + name + "，要先解除原本的設定才能改成其他機器。";
    }

    public static String denied(String name) {
        return "您沒有調整這台" + name + "的權限！";
    }

    private static volatile List<Map.Entry<Plugin, Method>> typeProviders = List.of();
    private static volatile List<Map.Entry<Plugin, Method>> shellProviders = List.of();
    private static volatile int providerCount = -1;

    private static void scan() {
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        if (plugins.length == providerCount) return;
        List<Map.Entry<Plugin, Method>> types = new ArrayList<>();
        List<Map.Entry<Plugin, Method>> shells = new ArrayList<>();
        for (Plugin plugin : plugins) {
            Method[] methods;
            try {
                methods = plugin.getClass().getMethods();
            } catch (LinkageError e) {
                continue;
            }
            for (Method method : methods) {
                if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != Block.class) continue;
                if (method.getName().equals("machineType")) types.add(Map.entry(plugin, method));
                else if (method.getName().equals("machineShell")) shells.add(Map.entry(plugin, method));
            }
        }
        typeProviders = types;
        shellProviders = shells;
        providerCount = plugins.length;
    }

    public static String occupant(Block block, Plugin self) {
        scan();
        for (Map.Entry<Plugin, Method> provider : typeProviders) {
            Plugin plugin = provider.getKey();
            if (plugin == self || !plugin.isEnabled()) continue;
            try {
                if (provider.getValue().invoke(plugin, block) instanceof String name) return name;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return null;
    }

    public static boolean shelled(Block block, Plugin self) {
        scan();
        for (Map.Entry<Plugin, Method> provider : shellProviders) {
            Plugin plugin = provider.getKey();
            if (plugin == self || !plugin.isEnabled()) continue;
            try {
                if (Boolean.TRUE.equals(provider.getValue().invoke(plugin, block))) return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return false;
    }

    public static void reject(Plugin plugin, Block sign, Player player, String reason) {
        if (reason != null) tell(player, "§c" + reason);
        take(plugin, sign, player, null);
    }

    public static void consume(Plugin plugin, Block sign, Player player, String name, Supplier<Map.Entry<String, Dialog>> panel) {
        take(plugin, sign, player, () -> {
            Map.Entry<String, Dialog> entry = panel.get();
            if (entry != null) {
                player.showDialog(entry.getValue());
                opened(player, entry.getValue());
            } else {
                tell(player, "§c" + denied(name));
            }
        });
    }

    private static void take(Plugin plugin, Block sign, Player player, Runnable then) {
        Bukkit.getRegionScheduler().runDelayed(plugin, sign.getLocation(), task -> {
            if (!Tag.ALL_SIGNS.isTagged(sign.getType())) return;
            Material item = sign.getBlockData().getPlacementMaterial();
            boolean water = sign.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
            sign.setType(water ? Material.WATER : Material.AIR);
            player.getScheduler().run(plugin, t -> {
                if (player.getGameMode() != GameMode.CREATIVE && item.isItem()) {
                    player.getInventory().addItem(new ItemStack(item)).values()
                            .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
                }
                if (then != null) then.run();
            }, null);
        }, 1L);
    }

    private static void tell(Player player, String message) {
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(PREFIX + message));
    }
}

package com.aeltumn.autocraft;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MachineScreen implements Listener {
    private static final Pattern PAYLOAD = Pattern.compile("(?:^|[{,])\\s*t\\s*:\\s*['\"](\\d{1,10}):([a-z]{2,8})(?::(\\d{1,3}))?['\"]");
    private static final TextColor TITLE = TextColor.color(0xFAEDE7);
    private static final long IDLE_MS = 10 * 60 * 1000L;
    private static final double REACH = 8 * 8;

    public interface Source {
        MachineView.Machine view(Block block);

        boolean allowed(Player player, Block block);

        void act(Player player, Block block, String action, int index, String text);

        default String[] prompt(Block block, String action, int index) {
            return null;
        }

        void unset(Player player, Block block);
    }

    private static final class Session {
        final Location block;
        final int token = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        final long created = System.currentTimeMillis();
        volatile Dialog dialog;
        volatile String shown;
        volatile String title = "";
        volatile boolean live;
        volatile boolean sub;
        volatile long touched = System.currentTimeMillis();

        Session(Location block) {
            this.block = block;
        }
    }

    private final Plugin plugin;
    private final Source source;
    private final Key key;
    private final MachineView view = new MachineView(UiFont.shared());
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public MachineScreen(Plugin plugin, Source source) {
        this.plugin = plugin;
        this.source = source;
        this.key = Key.key(plugin.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", ""), "panel");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> refreshAll(), 10L, 10L);
    }

    public Map.Entry<String, Dialog> entry(Player player, Block block) {
        if (!source.allowed(player, block)) return null;
        Session current = sessions.get(player.getUniqueId());
        if (current != null && !current.live && current.block.equals(block.getLocation())
                && System.currentTimeMillis() - current.created < 1000 && current.dialog != null) {
            return Map.entry(current.title, current.dialog);
        }
        Session session = new Session(block.getLocation());
        Dialog dialog = panel(session);
        if (dialog == null) return null;
        sessions.put(player.getUniqueId(), session);
        return Map.entry(session.title, dialog);
    }

    public Map.Entry<String, Dialog> open(Player player, Block block) {
        Map.Entry<String, Dialog> entry = entry(player, block);
        if (entry != null) opened(player, entry.getValue());
        return entry;
    }

    public void opened(Player player, Dialog dialog) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.dialog != dialog) return;
        session.live = true;
        session.touched = System.currentTimeMillis();
    }

    private Dialog panel(Session session) {
        MachineView.Machine machine = source.view(session.block.getBlock());
        if (machine == null) return null;
        session.shown = machine.toString();
        session.title = machine.title();
        Component body = view.render(machine, action -> ClickEvent.custom(key, payload(session, action)));
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(base(Component.text(machine.title(), TITLE))
                        .body(List.of(DialogBody.plainMessage(body, UiFont.shared().messageWidth()))).build())
                .type(DialogType.notice(ActionButton.builder(Component.text("關閉", NamedTextColor.GRAY)).width(150)
                        .action(DialogAction.customClick(key, payload(session, "close"))).build())));
        session.dialog = dialog;
        return dialog;
    }

    private static DialogBase.Builder base(Component title) {
        return DialogBase.builder(title).canCloseWithEscape(true).pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE);
    }

    private static BinaryTagHolder payload(Session session, String action) {
        return BinaryTagHolder.binaryTagHolder("{t:\"" + session.token + ":" + action + "\"}");
    }

    private void refreshAll() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                sessions.remove(entry.getKey(), session);
                continue;
            }
            if (!session.live || session.sub) continue;
            if (now - session.touched > IDLE_MS) {
                session.live = false;
                continue;
            }
            Bukkit.getRegionScheduler().run(plugin, session.block, task -> push(player, session, false));
        }
    }

    private void push(Player player, Session session, boolean force) {
        if (sessions.get(player.getUniqueId()) != session) return;
        String before = session.shown;
        Dialog dialog = panel(session);
        if (dialog == null) {
            end(player, session);
            return;
        }
        if (!force && session.shown.equals(before)) return;
        player.getScheduler().run(plugin, task -> {
            if (sessions.get(player.getUniqueId()) == session && session.live && !session.sub) player.showDialog(dialog);
        }, null);
    }

    private void end(Player player, Session session) {
        if (!sessions.remove(player.getUniqueId(), session)) return;
        if (session.live) player.getScheduler().run(plugin, task -> player.closeDialog(), null);
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!key.equals(event.getIdentifier())) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        BinaryTagHolder tag = event.getTag();
        if (tag == null) return;
        Matcher m = PAYLOAD.matcher(tag.string());
        if (!m.find()) return;
        String action = m.group(2);
        Session session = sessions.get(player.getUniqueId());
        boolean mine = session != null && String.valueOf(session.token).equals(m.group(1));
        if (action.equals("close")) {
            if (mine) sessions.remove(player.getUniqueId(), session);
            player.getScheduler().run(plugin, task -> player.closeDialog(), null);
            return;
        }
        if (!mine) return;
        int index = m.group(3) == null ? -1 : Integer.parseInt(m.group(3));
        String text = (action.equals("save") || action.equals("put")) && event.getDialogResponseView() != null
                ? event.getDialogResponseView().getText("value") : null;
        session.touched = System.currentTimeMillis();
        session.live = true;
        player.getScheduler().run(plugin, task -> {
            if (!player.getWorld().equals(session.block.getWorld())
                    || player.getLocation().distanceSquared(session.block.clone().add(0.5, 0.5, 0.5)) > REACH) {
                end(player, session);
                return;
            }
            Bukkit.getRegionScheduler().run(plugin, session.block, t -> act(player, session, action, index, text));
        }, null);
    }

    private void act(Player player, Session session, String action, int index, String text) {
        Block block = session.block.getBlock();
        if (!source.allowed(player, block)) {
            end(player, session);
            return;
        }
        switch (action) {
            case "on", "off", "pick", "save", "put", "drop" -> source.act(player, block, action, index, text);
            case "edit", "add" -> {
                String[] p = source.prompt(block, action, index);
                if (p != null) {
                    sub(player, session, editor(session, p, (action.equals("edit") ? "save:" : "put:") + index));
                    return;
                }
            }
            case "remove" -> {
                String[] p = source.prompt(block, action, index);
                if (p != null) {
                    sub(player, session, confirm(session, p[0], p[1], "移除", "drop:" + index));
                    return;
                }
            }
            case "unset" -> {
                sub(player, session, confirm(session, "確定要解除" + session.title + "？",
                        "解除後會變回一般方塊，需要重新貼告示牌才能再設定", "解除", "clear"));
                return;
            }
            case "clear" -> {
                source.unset(player, block);
                end(player, session);
                return;
            }
            default -> {
            }
        }
        session.sub = false;
        push(player, session, true);
    }

    private void sub(Player player, Session session, Dialog dialog) {
        session.sub = true;
        player.getScheduler().run(plugin, task -> {
            if (sessions.get(player.getUniqueId()) == session) player.showDialog(dialog);
        }, null);
    }

    private Dialog editor(Session session, String[] prompt, String action) {
        return Dialog.create(builder -> builder.empty()
                .base(base(Component.text(prompt[0], TITLE))
                        .inputs(List.of(DialogInput.text("value", Component.text(prompt[1]))
                                .initial(prompt[2]).maxLength(64).width(240).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("儲存", MachineView.GREEN))
                                .action(DialogAction.customClick(key, payload(session, action))).build(),
                        ActionButton.builder(Component.text("返回", NamedTextColor.GRAY))
                                .action(DialogAction.customClick(key, payload(session, "back"))).build())));
    }

    private Dialog confirm(Session session, String title, String body, String yes, String action) {
        return Dialog.create(builder -> builder.empty()
                .base(base(Component.text(title, TITLE))
                        .body(List.of(DialogBody.plainMessage(Component.text(body, NamedTextColor.GRAY)))).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text(yes, MachineView.RED))
                                .action(DialogAction.customClick(key, payload(session, action))).build(),
                        ActionButton.builder(Component.text("取消", NamedTextColor.GRAY))
                                .action(DialogAction.customClick(key, payload(session, "back"))).build())));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL) sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventory(InventoryOpenEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}

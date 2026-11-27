package com.aeltumn.autocraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class MachineView {
    public static final TextColor TEXT = TextColor.color(0xEAECF0);
    public static final TextColor SUBTLE = TextColor.color(0xA2A9B1);
    public static final TextColor FAINT = TextColor.color(0x90959B);
    public static final TextColor ACCENT = TextColor.color(0xF6E0A4);
    public static final TextColor GREEN = TextColor.color(0xCFFFC0);
    public static final TextColor AMBER = TextColor.color(0xF6C236);
    public static final TextColor BLUE = TextColor.color(0x7AD3FF);
    public static final TextColor RED = TextColor.color(0xFF7A7A);
    static final TextColor LINE = TextColor.color(0x36393E);
    static final TextColor IDLE = TextColor.color(0x54595D);
    static final TextColor INK = TextColor.color(0x16181C);

    static final int LEFT = 12;
    static final int COLUMN = 139;
    static final int GAP = 6;
    static final int TIGHT = 3;
    static final int FIELD_X = 76;
    static final int FIELD_W = 120;
    static final int BELT = 24;

    public enum Status {
        RUNNING("運作中", GREEN), JAMMED("阻塞", AMBER), STARVED("缺料", BLUE), RECEIVING("接收中", GREEN),
        IDLE("待機", SUBTLE), OFF("已停用", SUBTLE), UNLINKED("未連結", RED);

        final String label;
        final TextColor color;

        Status(String label, TextColor color) {
            this.label = label;
            this.color = color;
        }

        boolean moving() {
            return this == RUNNING || this == RECEIVING || this == JAMMED || this == STARVED;
        }
    }

    public record Flow(String name, TextColor nameColor, boolean active, String icon, boolean dim, String label,
                       TextColor labelColor, Integer count, Component hover) {
        public Flow(String name, boolean active, String icon, String label, Integer count, Component hover) {
            this(name, ACCENT, active, icon, false, label, SUBTLE, count, hover);
        }
    }

    public sealed interface Row permits Chips, Field, Channel {
    }

    public record Chips(String label, List<String> options, String selected) implements Row {
    }

    public record Field(String label, String value) implements Row {
    }

    public record Channel(String label, String name, boolean last, boolean removable, boolean divider) implements Row {
    }

    public record Machine(String title, String category, String icon, String plate, String owner, Boolean enabled,
                          String about, String statLabel, String stat, boolean inward, List<Flow> flows, List<Row> rows,
                          Status status, String hint) {
    }

    public record MenuEntry(String icon, String name, ClickEvent<?> click) {
    }

    private record Plan(int[] diagram, String rule, int ruleLine, int[] rows, int status, int lines) {
    }

    public static Status status(String look, boolean enabled) {
        if (!enabled) return Status.OFF;
        if (look == null) return Status.IDLE;
        if (look.endsWith("_active")) return Status.RUNNING;
        if (look.endsWith("_blocked")) return Status.JAMMED;
        if (look.endsWith("_empty")) return Status.STARVED;
        return Status.IDLE;
    }

    public static String hint(Status status, String running, String jammed, String starved, String idle) {
        return switch (status) {
            case RUNNING, RECEIVING -> running;
            case JAMMED -> jammed;
            case STARVED -> starved;
            case OFF -> "已關閉，不會運作";
            case UNLINKED -> "沒有可用的連結對象";
            case IDLE -> idle;
        };
    }

    public static String owner(java.util.UUID owner) {
        return owner == null ? null : org.bukkit.Bukkit.getOfflinePlayer(owner).getName();
    }

    public static String rate(long period, int items) {
        if (period == 20) return items + " 個/秒";
        return period % 20 == 0 ? "每 " + period / 20 + " 秒 " + items + " 個" : "每 " + period + " tick " + items + " 個";
    }

    public static Component item(String verb, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return Component.text(verb + " ", FAINT).append(stack.effectiveName().color(TEXT))
                .append(Component.text(" x" + stack.getAmount(), FAINT));
    }

    public static String amount(int n) {
        if (n < 1000) return String.valueOf(n);
        String[] units = {"k", "M", "B"};
        double v = n;
        int u = -1;
        while (v >= 1000 && u < units.length - 1) {
            v /= 1000;
            u++;
        }
        String s = v < 10 ? String.valueOf(Math.floor(v * 10) / 10) : String.valueOf((long) Math.floor(v));
        return (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + units[u];
    }

    public static String name(String translationKey) {
        String name = UiFont.shared().translate(translationKey);
        return name != null ? name : translationKey.substring(translationKey.lastIndexOf('.') + 1);
    }

    public static String name(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasCustomName()) return plain(meta.customName());
        if (meta != null && meta.hasItemName()) return plain(meta.itemName());
        return name(stack.translationKey());
    }

    public static String name(org.bukkit.entity.Entity entity) {
        if (entity == null) return null;
        if (entity instanceof org.bukkit.entity.Player player) return player.getName();
        Component custom = entity.customName();
        return custom != null ? plain(custom) : name(entity.getType().translationKey());
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static Component hover(String hint, Component item) {
        Component first = Component.text(hint, TEXT);
        return item == null ? first : first.append(Component.newline()).append(item);
    }

    public static ItemStack first(Inventory inventory, java.util.function.Predicate<ItemStack> wanted) {
        if (inventory == null) return null;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.isEmpty() && wanted.test(stack)) return stack;
        }
        return null;
    }

    public static int count(Inventory inventory, java.util.function.Predicate<ItemStack> wanted) {
        int total = 0;
        if (inventory == null) return 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.isEmpty() && wanted.test(stack)) total += stack.getAmount();
        }
        return total;
    }

    private final UiFont font;

    public MachineView(UiFont font) {
        this.font = font;
    }

    int right() {
        return font.width - 9;
    }

    private int diagramX() {
        return right() - 186;
    }

    private Plan plan(Machine m) {
        int n = m.flows().size();
        int[] diagram = new int[n];
        for (int i = 0; i < n; i++) diagram[i] = n == 1 ? 5 : 4 + 2 * i;
        int last = n == 0 ? 4 : diagram[n - 1];
        boolean beside = last <= 8;
        int[] rows = new int[m.rows().size()];
        if (rows.length == 0) {
            int status = beside ? 11 : last + 2;
            return new Plan(diagram, null, 0, rows, status, status + 3);
        }
        int line = beside ? 12 : last + 4;
        for (int i = 0; i < rows.length; i++) {
            rows[i] = line;
            line += 3;
        }
        int status = line - 1;
        return new Plan(diagram, beside ? "rule_6" : "rule_4", beside ? 10 : last + 2, rows, status, status + 3);
    }

    Component render(Machine m, Function<String, ClickEvent<?>> act) {
        Plan plan = plan(m);
        UiFont.Line[] lines = new UiFont.Line[plan.lines()];
        for (int i = 0; i < lines.length; i++) lines[i] = font.line();
        background(lines, act);
        header(lines[1], m, act);
        tiles(lines[2], "rule_8", LEFT, right(), LINE);
        left(lines, m, act);
        diagram(lines, m, plan.diagram());
        if (plan.rule() != null) tiles(lines[plan.ruleLine()], plan.rule(), LEFT, right(), LINE);
        for (int r = 0; r < plan.rows().length; r++) {
            Row row = m.rows().get(r);
            if (row instanceof Channel c && c.divider()) tiles(lines[plan.rows()[r] - 2], "rule_8", LEFT, right(), LINE);
            row(lines[plan.rows()[r]], row, r, act);
        }
        statusLabel(lines[plan.status()], m.status(), m.hint());
        statusBar(lines[plan.status() + 1], m.status());
        return join(lines);
    }

    Component menu(List<MenuEntry> entries) {
        UiFont.Line[] lines = new UiFont.Line[4 + 3 * (entries.size() - 1) + 2];
        for (int i = 0; i < lines.length; i++) lines[i] = font.line();
        background(lines, a -> null);
        lines[1].at(LEFT).text("輝煌科技", ACCENT, UiFont.Action.NONE).text("｜", IDLE, UiFont.Action.NONE)
                .text("這一格有 " + entries.size() + " 台機器", SUBTLE, UiFont.Action.NONE);
        tiles(lines[2], "rule_8", LEFT, right(), LINE);
        for (int i = 0; i < entries.size(); i++) {
            UiFont.Line line = lines[4 + 3 * i];
            MenuEntry entry = entries.get(i);
            int x = LEFT;
            if (entry.icon() != null && font.has(entry.icon())) {
                line.at(x).glyph(entry.icon(), TEXT, UiFont.Action.NONE);
                x += 16 + GAP;
            }
            line.at(x).text(entry.name(), TEXT, UiFont.Action.NONE);
            line.chip(right() - font.chip("設定"), "設定", "chip", TEXT, TEXT,
                    UiFont.Action.of(entry.click(), Component.text("開啟" + entry.name() + "的設定", TEXT)));
        }
        return join(lines);
    }

    private static Component join(UiFont.Line[] lines) {
        TextComponent.Builder out = Component.text();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append(Component.newline());
            out.append(lines[i].build());
        }
        return out.build();
    }

    private void background(UiFont.Line[] lines, Function<String, ClickEvent<?>> act) {
        lines[0].text("‌", TEXT, UiFont.Action.of(act.apply("refresh"), null));
        for (int i = 0; i < lines.length; i++) {
            String piece = i == 0 ? "cap" : i == lines.length - 1 ? "foot" : i == 4 ? "tall" : i > 4 && i < 8 ? null : "row";
            if (piece == null) continue;
            lines[i].at(0).glyph(piece + "_l", TEXT, UiFont.Action.NONE);
            tiles(lines[i], piece + "_m", font.glyph(piece + "_l").width(), font.width - 1, TEXT);
            lines[i].at(font.width - 1).glyph(piece + "_r", TEXT, UiFont.Action.NONE);
        }
    }

    private void header(UiFont.Line line, Machine m, Function<String, ClickEvent<?>> act) {
        line.at(LEFT)
                .text("輝煌科技", ACCENT, UiFont.Action.NONE)
                .text("｜", IDLE, UiFont.Action.NONE)
                .text(m.category(), SUBTLE, UiFont.Action.NONE);
        int x = right() - font.chip("解除設定");
        line.chip(x, "解除設定", "chip", RED, RED,
                UiFont.Action.of(act.apply("unset"), Component.text("解除整台" + m.title() + "的設定", TEXT)));
    }

    private void tiles(UiFont.Line line, String name, int from, int to, TextColor color) {
        int w = font.glyph(name).width();
        for (int x = from; x < to; x += w) line.at(Math.min(x, to - w)).glyph(name, color, UiFont.Action.NONE);
    }

    private void left(UiFont.Line[] lines, Machine m, Function<String, ClickEvent<?>> act) {
        UiFont.Line line = lines[4];
        int x = LEFT;
        if (m.enabled() != null) {
            UiFont.Action on = UiFont.Action.of(act.apply("on"), Component.text("開啟" + m.title(), TEXT));
            UiFont.Action off = UiFont.Action.of(act.apply("off"), Component.text("關閉" + m.title(), TEXT));
            if (m.enabled()) line.chip(x, "ON", "fill", TEXT, INK, on);
            else line.chip(x, "ON", "chip", TEXT, SUBTLE, on);
            x += font.chip("ON") - 1;
            if (m.enabled()) line.chip(x, "OFF", "chip", TEXT, SUBTLE, off);
            else line.chip(x, "OFF", "fill", SUBTLE, INK, off);
            x += font.chip("OFF") + GAP;
        }
        if (m.owner() != null) {
            int room = LEFT + COLUMN - x - font.text("設定者 ") + 1;
            line.at(x).text("設定者 ", FAINT, UiFont.Action.NONE).text(font.fit(m.owner(), room), SUBTLE, UiFont.Action.NONE);
        }
        String[] about = wrap(m.about());
        lines[6].at(LEFT).text(about[0], SUBTLE, UiFont.Action.NONE);
        lines[7].at(LEFT).text(about[1], SUBTLE, UiFont.Action.NONE);
        int dot = font.glyph("dots_4").width();
        for (int dx = LEFT; dx + dot <= LEFT + 120; dx += dot) lines[8].at(dx).glyph("dots_4", IDLE, UiFont.Action.NONE);
        lines[9].at(LEFT).text(m.statLabel() + " ", FAINT, UiFont.Action.NONE).text(m.stat(), ACCENT, UiFont.Action.NONE);
    }

    private String big(String icon) {
        for (String name = icon; name != null; ) {
            if (font.has(name + "_big")) return name + "_big";
            int cut = name.lastIndexOf('_');
            name = cut > 0 ? name.substring(0, cut) : null;
        }
        return null;
    }

    private void diagram(UiFont.Line[] lines, Machine m, int[] at) {
        int dx = diagramX();
        lines[4].at(dx).glyph("plate", TEXT, UiFont.Action.NONE);
        String big = big(m.icon());
        if (big != null) lines[4].at(dx + 4).glyph(big, TEXT, UiFont.Action.NONE);
        lines[9].at(dx + (40 - (font.text(m.plate()) - 1)) / 2).text(m.plate(), FAINT, UiFont.Action.NONE);

        int nameX = dx + 40 + GAP;
        int beltX = nameX + 24 + TIGHT;
        int slotX = beltX + BELT + TIGHT;
        int labelX = slotX + 18 + GAP;
        for (int i = 0; i < at.length; i++) {
            UiFont.Line line = lines[at[i]];
            Flow flow = m.flows().get(i);
            UiFont.Action tip = flow.hover() == null ? UiFont.Action.NONE : UiFont.Action.of(null, flow.hover());
            line.at(nameX).text(font.fit(font.printable(flow.name()) ? flow.name() : "?", 25), flow.nameColor(), tip);
            tiles(line, m.inward() ? "belt_l" : "belt_r", beltX, beltX + BELT, flow.active() ? GREEN : IDLE);
            line.at(slotX).glyph("slot", TEXT, tip);
            if (flow.icon() != null && font.has(flow.icon())) line.at(slotX + 1).glyph(flow.icon(), flow.dim() ? IDLE : TEXT, tip);
            String label = font.fit(font.clean(flow.label()), right() - labelX + 1);
            String num = flow.count() != null && flow.count() > 0 ? " " + amount(flow.count()) : null;
            line.at(labelX).text(label, flow.labelColor(), tip);
            if (num != null && font.text(label + num) <= right() - labelX + 1) line.text(" ", SUBTLE, tip).text(num.substring(1), ACCENT, tip);
        }
    }

    private void row(UiFont.Line line, Row row, int index, Function<String, ClickEvent<?>> act) {
        switch (row) {
            case Chips c -> {
                line.at(LEFT).text(c.label(), FAINT, UiFont.Action.NONE);
                int x = FIELD_X;
                for (int o = 0; o < c.options().size(); o++) {
                    String option = c.options().get(o);
                    UiFont.Action pick = UiFont.Action.of(act.apply("pick:" + (index * 16 + o)), Component.text("改成" + option, TEXT));
                    if (option.equals(c.selected())) line.chip(x, option, "fill", TEXT, INK, pick);
                    else line.chip(x, option, "chip", TEXT, SUBTLE, pick);
                    x += font.chip(option) - 1;
                }
            }
            case Field f -> {
                line.at(LEFT).text(f.label(), FAINT, UiFont.Action.NONE);
                line.at(FIELD_X).glyph("field", TEXT, UiFont.Action.NONE);
                line.at(FIELD_X + UiFont.CHIP_PAD).text(font.fit(f.value(), FIELD_W - UiFont.CHIP_PAD * 2 + 1), TEXT, UiFont.Action.NONE);
                line.chip(FIELD_X + FIELD_W + GAP, "修改", "chip", TEXT, TEXT,
                        UiFont.Action.of(act.apply("edit:" + index), Component.text("修改" + f.label(), TEXT)));
            }
            case Channel c -> {
                if (c.label() != null) line.at(LEFT).text(c.label(), FAINT, UiFont.Action.NONE);
                line.at(FIELD_X).glyph("field", TEXT, UiFont.Action.NONE);
                String name = font.printable(c.name()) ? c.name() : "?";
                line.at(FIELD_X + UiFont.CHIP_PAD).text(font.fit(name, FIELD_W - UiFont.CHIP_PAD * 2 + 1), TEXT, UiFont.Action.NONE);
                int x = FIELD_X + FIELD_W + GAP;
                line.chip(x, "修改", "chip", TEXT, TEXT,
                        UiFont.Action.of(act.apply("edit:" + index), Component.text("修改這個頻道的名稱", TEXT)));
                x += font.chip("修改") + GAP;
                if (c.last()) {
                    line.chip(x, "新增", "chip", TEXT, TEXT,
                            UiFont.Action.of(act.apply("add:" + index), Component.text("再加一個頻道", TEXT)));
                }
                if (c.removable()) {
                    line.chip(right() - font.chip("移除"), "移除", "chip", RED, RED,
                            UiFont.Action.of(act.apply("remove:" + index), Component.text("移除這個頻道", TEXT)));
                }
            }
        }
    }

    private void statusLabel(UiFont.Line line, Status status, String hint) {
        int chev = font.glyph("chev").width();
        int w = 3 * chev + 2 * 2 + TIGHT + font.text(status.label) - 1;
        int x = (LEFT + right() - w) / 2;
        UiFont.Action tip = hint == null ? UiFont.Action.NONE : UiFont.Action.of(null, Component.text(hint, TEXT));
        for (int i = 0; i < 3; i++) line.at(x + i * (chev + 2)).glyph("chev", status.moving() ? status.color : IDLE, tip);
        line.at(x + 3 * chev + 2 * 2 + TIGHT).text(status.label, status.color, tip);
    }

    private void statusBar(UiFont.Line line, Status status) {
        if (status == Status.JAMMED) tiles(line, "hatch_6", LEFT, right(), AMBER);
        tiles(line, "rule_9", LEFT, right(), LINE);
        int mid = (LEFT + right()) / 2;
        tiles(line, "seg_8", mid - 55, mid + 55, status.color);
    }

    String[] wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!cur.isEmpty() && font.text(cur.toString() + c) - 1 > COLUMN) {
                if ("，。、）".indexOf(c) >= 0) {
                    char last = cur.charAt(cur.length() - 1);
                    cur.setLength(cur.length() - 1);
                    out.add(cur.toString());
                    cur = new StringBuilder().append(last);
                } else {
                    out.add(cur.toString());
                    cur = new StringBuilder();
                }
            }
            cur.append(c);
        }
        out.add(cur.toString());
        while (out.size() < 2) out.add("");
        if (out.size() > 2) out.set(1, font.fit(String.join("", out.subList(1, out.size())), COLUMN + 1));
        return new String[]{out.get(0), out.get(1)};
    }
}

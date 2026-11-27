package com.aeltumn.autocraft;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class UiFont {
    static final Key FONT = Key.key("brilliant", "machine_ui");
    static final int CHIP_PAD = 4;

    public record Glyph(char code, int advance, int width, int height, int top) {
    }

    final int width;
    final int margin;
    private final byte[] advances;
    private final Map<String, Glyph> glyphs = new HashMap<>();
    private final Map<Integer, Character> spaces = new HashMap<>();
    private final Properties names = new Properties();
    private static volatile UiFont shared;

    public static UiFont shared() {
        if (shared == null) {
            synchronized (UiFont.class) {
                if (shared == null) {
                    try (InputStream p = UiFont.class.getClassLoader().getResourceAsStream("ui-glyphs.properties");
                         InputStream w = UiFont.class.getClassLoader().getResourceAsStream("ui-widths.bin")) {
                        if (p == null || w == null) throw new IOException("找不到介面字型資料");
                        UiFont font = new UiFont(p, w);
                        try (InputStream n = UiFont.class.getClassLoader().getResourceAsStream("ui-names.properties")) {
                            if (n != null) font.names.load(new InputStreamReader(n, StandardCharsets.UTF_8));
                        }
                        shared = font;
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return shared;
    }

    public UiFont(InputStream properties, InputStream widths) throws IOException {
        Properties props = new Properties();
        props.load(new InputStreamReader(properties, StandardCharsets.UTF_8));
        advances = widths.readAllBytes();
        if (advances.length != 65536) throw new IOException("ui-widths.bin 長度錯誤");
        width = Integer.parseInt(props.getProperty("width"));
        margin = Integer.parseInt(props.getProperty("margin"));
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            if (key.startsWith("space.")) {
                spaces.put(Integer.parseInt(key.substring(6)), (char) Integer.parseInt(value, 16));
            } else if (key.startsWith("glyph.")) {
                String[] p = value.split(" ");
                glyphs.put(key.substring(6), new Glyph((char) Integer.parseInt(p[0], 16), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])));
            }
        }
    }

    int limit() {
        return width + 1;
    }

    int messageWidth() {
        return width;
    }

    boolean has(String name) {
        return glyphs.containsKey(name);
    }

    Glyph glyph(String name) {
        Glyph glyph = glyphs.get(name);
        if (glyph == null) throw new IllegalArgumentException("沒有字形 " + name);
        return glyph;
    }

    int text(String s) {
        int total = 0;
        for (int i = 0; i < s.length(); i++) total += advances[s.charAt(i)] & 0xFF;
        return total;
    }

    int chip(String label) {
        return text(label) - 1 + CHIP_PAD * 2;
    }

    String fit(String s, int max) {
        if (text(s) <= max) return s;
        String dots = "...";
        int limit = max - text(dots);
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); i++) {
            int w = advances[s.charAt(i)] & 0xFF;
            if (used + w > limit) break;
            out.append(s.charAt(i));
            used += w;
        }
        return out + dots;
    }

    String translate(String key) {
        String[][] prefixes = {{"item.minecraft.", "i."}, {"block.minecraft.", "b."}, {"entity.minecraft.", "e."}};
        for (String[] p : prefixes) {
            if (key.startsWith(p[0])) return names.getProperty(p[1] + key.substring(p[0].length()));
        }
        return null;
    }

    String clean(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowSurrogate(c)) continue;
            out.append(Character.isHighSurrogate(c) || (advances[c] & 0xFF) == 0 ? '?' : c);
        }
        return out.toString();
    }

    boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c) || (advances[c] & 0xFF) == 0) return false;
        }
        return true;
    }

    Line line() {
        return new Line();
    }

    public final class Line {
        private final TextComponent.Builder out = Component.text();
        private int cursor = margin;
        private StringBuilder pending;
        private TextColor pendingColor;
        private Action pendingAction;

        private void ui(String text, TextColor color, Action action) {
            if (pending != null && pendingAction == action && Objects.equals(pendingColor, color)) {
                pending.append(text);
                return;
            }
            flush();
            pending = new StringBuilder(text);
            pendingColor = color;
            pendingAction = action;
        }

        private void flush() {
            if (pending == null) return;
            Component c = Component.text(pending.toString()).font(FONT).shadowColor(ShadowColor.none());
            if (pendingColor != null) c = c.color(pendingColor);
            out.append(pendingAction.apply(c));
            pending = null;
        }

        Line at(int x) {
            int delta = x - cursor;
            if (x < 0 || x > limit()) throw new IllegalStateException("超出面板寬度：" + x);
            StringBuilder s = new StringBuilder();
            for (int p = 256; delta != 0; p >>= 1) {
                while (Math.abs(delta) >= p) {
                    int step = delta > 0 ? p : -p;
                    s.append(spaces.get(step));
                    delta -= step;
                }
            }
            if (!s.isEmpty()) {
                if (pending != null && pendingAction == Action.NONE) pending.append(s);
                else ui(s.toString(), null, Action.NONE);
            }
            cursor = x;
            return this;
        }

        Line glyph(String name, TextColor color, Action action) {
            Glyph glyph = UiFont.this.glyph(name);
            ui(String.valueOf(glyph.code()), color, action);
            advance(glyph.advance());
            return this;
        }

        Line text(String s, TextColor color, Action action) {
            flush();
            out.append(action.apply(Component.text(s).color(color).shadowColor(ShadowColor.none())));
            advance(UiFont.this.text(s));
            return this;
        }

        Line fill(String style, int x, int w, TextColor color, Action action) {
            Glyph part = UiFont.this.glyph(style + "_" + (w - 2 >= 32 ? 32 : 8));
            at(x).glyph(style + "_l", color, action);
            for (int px = x + 1; px < x + w - 1; px += part.width()) {
                at(Math.min(px, x + w - 1 - part.width())).glyph(style + "_" + part.width(), color, action);
            }
            return at(x + w - 1).glyph(style + "_r", color, action);
        }

        Line chip(int x, String label, String style, TextColor face, TextColor ink, Action action) {
            fill(style, x, UiFont.this.chip(label), face, action);
            return at(x + CHIP_PAD).text(label, ink, action);
        }

        Component build() {
            at(width - margin);
            flush();
            return out.build();
        }

        private void advance(int w) {
            cursor += w;
            if (cursor > limit()) throw new IllegalStateException("超出面板寬度：" + cursor);
        }
    }

    public interface Action {
        Action NONE = c -> c;

        Component apply(Component component);

        static Action of(ClickEvent<?> click, Component hover) {
            return c -> c.clickEvent(click).hoverEvent(hover == null ? null : HoverEvent.showText(hover));
        }
    }
}

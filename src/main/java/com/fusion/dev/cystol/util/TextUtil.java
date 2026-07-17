package com.fusion.dev.cystol.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TextUtil {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private TextUtil() {
    }

    public static String apply(String template, Map<String, String> placeholders) {
        if (template == null) {
            return "";
        }
        String out = template;
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                out = out.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
            }
        }
        return out;
    }

    public static Component component(String legacyWithAmpersand, Map<String, String> placeholders) {
        return LEGACY.deserialize(apply(legacyWithAmpersand, placeholders));
    }

    public static List<Component> componentList(List<String> lines, Map<String, String> placeholders) {
        List<Component> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            out.add(component(line, placeholders));
        }
        return out;
    }

    public static String colorize(String legacy) {
        return apply(legacy, Map.of());
    }
}

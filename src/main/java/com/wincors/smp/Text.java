package com.wincors.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;

public final class Text {
    /** Chat prefix (messages.yml -> prefix). */
    public static String PREFIX = "&8[&6Wincors&8] &r";
    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static Map<String, String> replacements = new HashMap<>();

    private Text() {}

    /** messages.yml -> replacements: every piece of plugin text is run through these find/replace rules. */
    static void setReplacements(Map<String, String> r) { replacements = r; }

    public static Component c(String s) {
        for (Map.Entry<String, String> en : replacements.entrySet()) {
            if (!en.getKey().isEmpty()) s = s.replace(en.getKey(), en.getValue());
        }
        return SER.deserialize(s);
    }

    public static Component item(String s) { return c(s).decoration(TextDecoration.ITALIC, false); }

    public static void msg(CommandSender to, String s) { to.sendMessage(c(PREFIX + s)); }
}

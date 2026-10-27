package com.wincors.smp;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Chat/action-bar messages from messages.yml. Use {placeholders}. */
public final class Msg {
    private static YamlConfiguration cfg;

    private Msg() {}

    public static void load(WincorsSMP plugin) {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        InputStream in = plugin.getResource("messages.yml");
        if (in != null) y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        cfg = y;
        Text.PREFIX = y.getString("prefix", Text.PREFIX);
        Map<String, String> rep = new HashMap<>();
        var sec = y.getConfigurationSection("replacements");
        if (sec != null) for (String k : sec.getKeys(false)) rep.put(k, sec.getString(k, ""));
        Text.setReplacements(rep);
    }

    /** t("key", "name1", "value1", "name2", "value2") */
    public static String t(String key, String... kv) {
        String s = cfg == null ? key : cfg.getString(key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }
}

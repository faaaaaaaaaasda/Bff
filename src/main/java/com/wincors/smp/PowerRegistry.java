package com.wincors.smp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** All powers, loaded from plugins/WincorsSMP/powers.yml. */
public final class PowerRegistry {
    private static final Map<Origin, List<Power>> MAP = new EnumMap<>(Origin.class);

    private PowerRegistry() {}

    public static List<Power> get(Origin o) {
        List<Power> l = MAP.get(o);
        return l == null ? List.of() : l;
    }

    /** Powers defined in code (the Calamity origin). */
    public static void set(Origin o, List<Power> list) { MAP.put(o, list); }

    public static void load(WincorsSMP plugin) {
        Logger log = plugin.getLogger();
        File f = new File(plugin.getDataFolder(), "powers.yml");
        if (!f.exists()) plugin.saveResource("powers.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        MAP.clear();
        int total = 0;
        for (Origin o : Origin.values()) {
            if (o == Origin.CALAMITY) continue;   // defined in code (Calamity.java)
            List<Power> list = new ArrayList<>();
            ConfigurationSection sec = y.getConfigurationSection(o.name());
            if (sec == null) {
                log.warning("powers.yml has no section for " + o.name());
                MAP.put(o, list);
                continue;
            }
            for (String key : sec.getKeys(false)) {
                String ctx = "powers.yml " + o.name() + "." + key;
                ConfigurationSection ps = sec.getConfigurationSection(key);
                if (ps == null) continue;
                int tier;
                try { tier = Integer.parseInt(key); } catch (NumberFormatException ex) { log.warning("Bad tier in " + ctx); continue; }
                if (tier < 1 || tier > 10) { log.warning("Tier must be 1-10 in " + ctx); continue; }
                String name = ps.getString("name", "Power " + tier);
                String desc = ps.getString("description", "");
                String type = ps.getString("type", "").toUpperCase();
                Power pw = null;
                try {
                    switch (type) {
                        case "POTION" -> {
                            PotionEffectType e = Parse.effectType(ps.getString("effect"));
                            if (e == null) log.warning("Unknown effect in " + ctx);
                            else pw = Power.potion(tier, name, desc, e, ps.getInt("amplifier", 0));
                        }
                        case "FLAG" -> pw = Power.flag(tier, name, desc, Power.Flag.valueOf(ps.getString("flag", "").toUpperCase()));
                        case "ACTIVE" -> {
                            Power.Active a = Actions.build(ps.getMapList("actions"), ctx);
                            if (a == null) log.warning("No valid actions in " + ctx);
                            else pw = Power.active(tier, name, desc, ps.getInt("cooldown", 20), a);
                        }
                        default -> log.warning("Unknown type '" + type + "' in " + ctx + " (use POTION, FLAG or ACTIVE)");
                    }
                } catch (IllegalArgumentException ex) {
                    log.warning("Invalid value in " + ctx + ": " + ex.getMessage());
                }
                if (pw != null) {
                    String iconName = ps.getString("icon");
                    if (iconName != null) {
                        pw.icon = Parse.material(iconName, null);
                        if (pw.icon == null) log.warning("Unknown icon '" + iconName + "' in " + ctx);
                    }
                    list.add(pw);
                    total++;
                }
            }
            list.sort(Comparator.comparingInt(p -> p.tier));
            MAP.put(o, list);
        }
        if (plugin.calamity() != null) MAP.put(Origin.CALAMITY, plugin.calamity().buildPowers());
        log.info("Loaded " + total + " powers from powers.yml");
    }
}

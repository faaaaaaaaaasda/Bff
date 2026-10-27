package com.wincors.smp;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;

/** The ten origins. Names, colors, descriptions, icons and weapons come from origins.yml. */
public enum Origin {
    PYRO("Pyro", "&c", "Masters of flame. Burn everything in your path."),
    AQUA("Aqua", "&9", "Children of the tide. Control water and healing."),
    TERRA("Terra", "&2", "Born of stone. Tanky, earthshaking power."),
    AERO("Aero", "&f", "Riders of the wind. Speed and mobility."),
    SHADOW("Shadow", "&8", "Stalkers of the dark. Stealth and life-drain."),
    STORM("Storm", "&e", "Wielders of lightning. Ranged burst damage."),
    FROST("Frost", "&b", "Heralds of winter. Freeze and control enemies."),
    VITALIS("Vitalis", "&a", "Voice of nature. Regeneration and poison."),
    BERSERKER("Berserker", "&4", "Pure rage. Brawlers who grow stronger in battle."),
    ARCANE("Arcane", "&5", "Bending space and time itself."),
    /** Operator-only: never random, never offered in the origin menus. */
    CALAMITY("Calamity", "&#FF2A2A", "Operator-only world-ending power: 30 abilities that destroy, kill and reshape the world.");

    /** On-hit effect of a legendary weapon. */
    public static final class Proc {
        public int cooldownSeconds = 6;
        public double extraDamage;
        public int fireTicks;
        public int freezeTicks;
        public double knockback;
        public double launch;
        public boolean lightning;
        public double healAttacker;
        public Particle particle = Particle.CRIT;
        public PotionEffect[] effects = new PotionEffect[0];
    }

    /** Legendary weapon definition. */
    public static final class WeaponDef {
        public String name = "Legendary Blade";
        public String model = "";
        public Material material = Material.NETHERITE_SWORD;
        public boolean unbreakable = true;
        public List<String> enchants = new ArrayList<>(List.of("SHARPNESS:5", "UNBREAKING:3"));
        public Proc proc = new Proc();
    }

    private String display;
    private String color;
    private String description;
    private String symbol = "\u2726";
    private String tagline = "";
    private Material icon = Material.PAPER;
    private WeaponDef weapon = new WeaponDef();

    Origin(String display, String color, String description) {
        this.display = display;
        this.color = color;
        this.description = description;
    }

    public String display() { return display; }
    public String color() { return color; }
    public String colored() { return color + display; }
    public String description() { return description; }
    public String symbol() { return symbol; }
    public String tagline() { return tagline; }
    /** Menu title: colour + bold + symbol + name. */
    public String title() { return color + "&l" + symbol + " " + display; }
    public Material icon() { return icon; }
    public WeaponDef weapon() { return weapon; }

    /** Applies one origin's section from origins.yml. */
    void configure(ConfigurationSection s) {
        if (s == null) return;
        display = s.getString("display", display);
        color = s.getString("color", color);
        description = s.getString("description", description);
        symbol = s.getString("symbol", symbol);
        tagline = s.getString("tagline", tagline);
        icon = Parse.material(s.getString("icon"), icon);
        ConfigurationSection w = s.getConfigurationSection("weapon");
        if (w == null) return;
        WeaponDef d = new WeaponDef();
        d.name = w.getString("name", display + " Blade");
        d.material = Parse.material(w.getString("material"), Material.NETHERITE_SWORD);
        d.model = w.getString("model", "");
        d.unbreakable = w.getBoolean("unbreakable", true);
        d.enchants = new ArrayList<>(w.getStringList("enchants"));
        ConfigurationSection p = w.getConfigurationSection("proc");
        if (p != null) {
            Proc pr = new Proc();
            pr.cooldownSeconds = p.getInt("cooldown-seconds", 6);
            pr.extraDamage = p.getDouble("extra-damage", 0);
            pr.fireTicks = p.getInt("fire-ticks", 0);
            pr.freezeTicks = p.getInt("freeze-ticks", 0);
            pr.knockback = p.getDouble("knockback", 0);
            pr.launch = p.getDouble("launch", 0);
            pr.lightning = p.getBoolean("lightning", false);
            pr.healAttacker = p.getDouble("heal-attacker", 0);
            pr.particle = Parse.particle(p.getString("particle"), Particle.CRIT);
            pr.effects = Parse.effects(p.getList("effects"));
            d.proc = pr;
        }
        weapon = d;
    }

    /** The ten normal origins (everything except the operator-only Calamity). */
    public static List<Origin> playable() {
        List<Origin> l = new ArrayList<>();
        for (Origin o : values()) if (o != CALAMITY) l.add(o);
        return l;
    }

    public static Origin parse(String s) {
        if (s == null) return null;
        for (Origin o : values()) {
            if (o.name().equalsIgnoreCase(s) || o.display.equalsIgnoreCase(s)) return o;
        }
        return null;
    }
}

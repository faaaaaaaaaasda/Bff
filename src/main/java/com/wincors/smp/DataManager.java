package com.wincors.smp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DataManager {
    public static final class PlayerData {
        public Origin origin;
        public int mana;
        public boolean weaponClaimed;
        public boolean initialized;
        public boolean vanished;
        public boolean pendingChoice;
    }

    private final WincorsSMP plugin;
    private final File file;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private boolean endOpen;
    private String maceId;
    private long graceUntil;
    private boolean pvpEnabled = true;
    private final Map<UUID, Long> personalGrace = new HashMap<>();
    private boolean dirty;

    public DataManager(WincorsSMP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        endOpen = y.getBoolean("end-open", plugin.getConfig().getBoolean("end.default-open", false));
        maceId = y.getString("mace-id");
        graceUntil = y.getLong("grace-until", 0L);
        pvpEnabled = y.getBoolean("pvp-enabled", true);
        ConfigurationSection pg = y.getConfigurationSection("personal-grace");
        if (pg != null) {
            for (String k : pg.getKeys(false)) {
                try { personalGrace.put(UUID.fromString(k), pg.getLong(k)); } catch (IllegalArgumentException ignored) { }
            }
        }
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                try {
                    PlayerData d = new PlayerData();
                    d.origin = Origin.parse(s.getString(k + ".origin"));
                    d.mana = s.getInt(k + ".mana");
                    d.weaponClaimed = s.getBoolean(k + ".weapon-claimed");
                    d.initialized = s.getBoolean(k + ".initialized");
                    d.vanished = s.getBoolean(k + ".vanished");
                    d.pendingChoice = s.getBoolean(k + ".pending-choice");
                    players.put(UUID.fromString(k), d);
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("end-open", endOpen);
        y.set("mace-id", maceId);
        y.set("grace-until", graceUntil);
        y.set("pvp-enabled", pvpEnabled);
        for (Map.Entry<UUID, Long> en : personalGrace.entrySet()) y.set("personal-grace." + en.getKey(), en.getValue());
        for (Map.Entry<UUID, PlayerData> en : players.entrySet()) {
            String k = "players." + en.getKey();
            PlayerData d = en.getValue();
            y.set(k + ".origin", d.origin == null ? null : d.origin.name());
            y.set(k + ".mana", d.mana);
            y.set(k + ".weapon-claimed", d.weaponClaimed);
            y.set(k + ".initialized", d.initialized);
            y.set(k + ".vanished", d.vanished);
            y.set(k + ".pending-choice", d.pendingChoice);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    public void startAutoSave() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); }, 600L, 600L);
    }

    public void markDirty() { dirty = true; }

    public PlayerData get(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerData());
    }

    public Map<UUID, PlayerData> all() { return players; }

    public long getGraceUntil() { return graceUntil; }

    public void setGraceUntil(long v) { graceUntil = v; dirty = true; }

    public boolean isPvpEnabled() { return pvpEnabled; }

    public void setPvpEnabled(boolean v) { pvpEnabled = v; dirty = true; }

    public Map<UUID, Long> personalGrace() { return personalGrace; }

    public void setPersonalGrace(UUID id, long until) {
        if (until <= 0) personalGrace.remove(id); else personalGrace.put(id, until);
        dirty = true;
    }

    public void cleanupPersonalGrace() {
        long now = System.currentTimeMillis();
        if (personalGrace.values().removeIf(u -> u <= now)) dirty = true;
    }

    public boolean isEndOpen() { return endOpen; }

    public void setEndOpen(boolean open) { this.endOpen = open; dirty = true; }

    public String getMaceId() { return maceId; }

    public void setMaceId(String id) { this.maceId = id; dirty = true; }
}

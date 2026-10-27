package com.wincors.smp;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class WincorsSMP extends JavaPlugin {
    private DataManager data;
    private ManaManager mana;
    private PowerManager powers;
    private CombatManager combat;
    private WeaponManager weapons;
    private EggManager egg;
    private RestrictionManager restrictions;
    private EndManager end;
    private GuiManager gui;
    private VanishManager vanish;
    private AdminTools tools;
    private GraceManager grace;
    private AntiCheat anticheat;
    private XrayMonitor xray;
    private ResourcePackManager pack;
    private XaeroControl xaero;
    private Calamity calamity;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Abilities.init(this);
        loadOrigins();
        Msg.load(this);
        PowerRegistry.load(this);
        data = new DataManager(this);
        data.load();

        mana = new ManaManager(this);
        powers = new PowerManager(this);
        combat = new CombatManager(this);
        weapons = new WeaponManager(this);
        egg = new EggManager(this);
        restrictions = new RestrictionManager(this);
        end = new EndManager(this);
        gui = new GuiManager(this);
        vanish = new VanishManager(this);
        tools = new AdminTools();
        grace = new GraceManager(this);
        anticheat = new AntiCheat(this);
        xray = new XrayMonitor(this);
        pack = new ResourcePackManager(this);
        xaero = new XaeroControl(this);
        calamity = new Calamity(this);
        PowerRegistry.set(Origin.CALAMITY, calamity.buildPowers());

        for (Listener l : new Listener[]{mana, powers, combat, weapons, egg, restrictions, end, gui, vanish, tools, grace, anticheat, xray, pack, xaero}) {
            getServer().getPluginManager().registerEvents(l, this);
        }

        mana.registerRecipe();
        powers.start();
        egg.start();
        grace.begin();
        anticheat.start();
        pack.start();
        xaero.start();
        calamity.start();
        restrictions.start();
        data.startAutoSave();

        AdminCommand admin = new AdminCommand(this);
        PluginCommand w = getCommand("wincors");
        if (w != null) { w.setExecutor(admin); w.setTabCompleter(admin); }
        PluginCommand vn = getCommand("vanish");
        if (vn != null) { vn.setExecutor(admin); vn.setTabCompleter(admin); }
        PlayerCommands pc = new PlayerCommands(this);
        for (String n : new String[]{"origin", "mana", "power", "menu"}) {
            PluginCommand pcmd = getCommand(n);
            if (pcmd != null) pcmd.setExecutor(pc);
        }
        getLogger().info("Wincors SMP enabled.");
    }

    private void loadOrigins() {
        java.io.File f = new java.io.File(getDataFolder(), "origins.yml");
        if (!f.exists()) saveResource("origins.yml", false);
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        for (Origin o : Origin.values()) o.configure(y.getConfigurationSection(o.name()));
    }

    /** Reloads config.yml, messages.yml, origins.yml, powers.yml, the recipe and restrictions. */
    public void reloadAll() {
        reloadConfig();
        Msg.load(this);
        loadOrigins();
        PowerRegistry.load(this);
        mana.registerRecipe();
        restrictions.reload();
        xray.reload();
        calamity.reload();
    }

    @Override
    public void onDisable() {
        if (vanish != null) vanish.shutdown();
        if (pack != null) pack.shutdown();
        if (mana != null) mana.unregisterRecipe();
        if (data != null) data.save();
    }

    public DataManager data() { return data; }
    public ManaManager mana() { return mana; }
    public PowerManager powers() { return powers; }
    public CombatManager combat() { return combat; }
    public WeaponManager weapons() { return weapons; }
    public EggManager egg() { return egg; }
    public RestrictionManager restrictions() { return restrictions; }
    public EndManager end() { return end; }
    public GuiManager gui() { return gui; }
    public VanishManager vanish() { return vanish; }
    public AdminTools tools() { return tools; }
    public GraceManager grace() { return grace; }
    public AntiCheat anticheat() { return anticheat; }
    public XrayMonitor xray() { return xray; }
    public ResourcePackManager pack() { return pack; }
    public XaeroControl xaero() { return xaero; }
    public Calamity calamity() { return calamity; }
}

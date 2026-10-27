package com.wincors.smp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /menu, /origin, /mana and /power for players. */
public final class PlayerCommands implements CommandExecutor {
    private final WincorsSMP plugin;

    public PlayerCommands(WincorsSMP plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            Text.msg(s, "Players only.");
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "menu" -> plugin.gui().openMain(p);
            case "origin" -> origin(p, a);
            case "mana" -> mana(p);
            case "power" -> power(p, a);
            case "calamity" -> calamity(p, a);
            default -> { }
        }
        return true;
    }

    private void origin(Player p, String[] a) {
        if (a.length == 0) { plugin.gui().openMain(p); return; }
        if (a[0].equalsIgnoreCase("choose")) {
            if (plugin.mana().canChoose(p)) plugin.gui().openChoose(p); else Text.msg(p, Msg.t("choose-locked"));
            return;
        }
        if (a[0].equalsIgnoreCase("list")) {
            Text.msg(p, "&6Origins:");
            for (Origin o : Origin.playable()) p.sendMessage(Text.c(" " + o.colored() + " &8- &7" + o.description()));
            return;
        }
        if (a[0].equalsIgnoreCase("info") && a.length >= 2) {
            Origin o = Origin.parse(a[1]);
            if (o == null) { Text.msg(p, "&cUnknown origin. Use /origin list"); return; }
            showPowers(p, o, 10);
            return;
        }
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) {
            Text.msg(p, "&7You have no origin. Gain mana (kill a player / craft a Mana Crystal) to awaken one.");
            return;
        }
        Text.msg(p, "Your origin: " + d.origin.colored() + " &8- &7" + d.origin.description());
        showPowers(p, d.origin, plugin.mana().tier(p));
        p.sendMessage(Text.c("&7Legendary weapon: &6" + WeaponManager.weaponName(d.origin) + " &7(at 10 mana)"));
    }

    private void showPowers(Player p, Origin o, int unlockedTier) {
        List<Power> list = PowerRegistry.get(o);
        p.sendMessage(Text.c("&8&m        &r " + o.title() + " &8\u00BB &7" + o.tagline() + " &8&m        "));
        for (Power pw : list) {
            boolean unlocked = pw.tier <= unlockedTier;
            String tag = pw.type == Power.Type.ACTIVE ? " &d[ACTIVE " + pw.cooldown + "s]" : "";
            p.sendMessage(Text.c(" &8[&6" + pw.tier + "&8] " + (unlocked ? o.color() : "&8") + pw.name + tag + " &8\u00BB &7" + pw.desc));
        }
    }

    private void mana(Player p) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin == null) {
            Text.msg(p, "&7You have no power. Gain mana to awaken one.");
            return;
        }
        Text.msg(p, "&bMana: &f" + d.mana + "/" + ManaManager.MAX + " &8| &6Power tier: &f" + plugin.mana().tier(p)
                + " &8| " + d.origin.colored());
        if (d.mana == 0) p.sendMessage(Text.c("&cWarning: dying at 0 mana makes you lose your power!"));
    }

    private void power(Player p, String[] a) {
        if (a.length == 0) {
            DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
            if (d.origin == null) plugin.gui().openMain(p); else plugin.gui().openPowers(p, d.origin);
            return;
        }
        switch (a[0].toLowerCase()) {
            case "use" -> plugin.powers().castSelected(p);
            case "next" -> plugin.powers().cycle(p);
            case "text" -> origin(p, new String[]{"text"});
            case "select" -> {
                if (a.length < 2) { Text.msg(p, "&cUsage: /power select <n>"); return; }
                try { plugin.powers().select(p, Integer.parseInt(a[1]) - 1); }
                catch (NumberFormatException ex) { Text.msg(p, "&cInvalid number."); }
            }
            default -> Text.msg(p, "&cUsage: /power [use|next|select <n>]");
        }
    }

    private void calamity(Player p, String[] a) {
        DataManager.PlayerData d = plugin.data().get(p.getUniqueId());
        if (d.origin != Origin.CALAMITY || !plugin.calamity().allowed(p)) {
            Text.msg(p, "&cCalamity is operator-only.");
            return;
        }
        if (a.length == 0) { plugin.gui().openCalamity(p); return; }
        switch (a[0].toLowerCase()) {
            case "undo", "rewind" -> plugin.calamity().undoLast(p);
            case "aura" -> plugin.calamity().toggleAuraFor(p);
            default -> {
                try {
                    plugin.powers().select(p, Integer.parseInt(a[0]) - 1);
                    plugin.powers().castSelected(p);
                } catch (NumberFormatException ex) {
                    Text.msg(p, "&cUsage: /calamity [1-30 | undo | aura]");
                }
            }
        }
    }
}

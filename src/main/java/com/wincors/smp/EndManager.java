package com.wincors.smp;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;

/** Admin-controlled open/close of The End. */
public final class EndManager implements Listener {
    private final WincorsSMP plugin;

    public EndManager(WincorsSMP plugin) { this.plugin = plugin; }

    public boolean isOpen() { return plugin.data().isEndOpen(); }

    public void setOpen(boolean open, CommandSender by) {
        plugin.data().setEndOpen(open);
        plugin.data().save();
        Bukkit.broadcast(Text.c(Text.PREFIX + Msg.t(open ? "end-opened" : "end-closed")));
        if (!open && plugin.getConfig().getBoolean("end.evacuate-on-close", true)) evacuate();
        Text.msg(by, "The End is now " + (open ? "&aopen" : "&cclosed") + "&r.");
    }

    /** Sends everyone in the End (without bypass) back to the overworld spawn. @return how many players moved */
    public int evacuate() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() == World.Environment.THE_END && !p.hasPermission("wincors.bypass")) {
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                Text.msg(p, Msg.t("end-evacuated"));
                n++;
            }
        }
        return n;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (isOpen() || e.getTo() == null || e.getTo().getWorld() == null) return;
        if (e.getTo().getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (e.getPlayer().hasPermission("wincors.bypass")) return;
        e.setCancelled(true);
        Text.msg(e.getPlayer(), Msg.t("end-blocked"));
    }
}

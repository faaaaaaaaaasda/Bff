# Wincors SMP Plugin

Paper 1.21.11 / Java 21. Build: `mvn clean package` (or push to GitHub, the Actions workflow builds it).

## Everything is configurable (plugins/WincorsSMP/ - run /wincors reload after editing)
- config.yml    mana, mana-crystal recipe, combat, restrictions (banned items, one-mace), dragon egg, End,
                movement, vanish, flag numbers, controls (which key does what), item names/lore, HUD on/off
- powers.yml    ALL 100 powers: names, descriptions, tiers, cooldowns, damage, radius, effects, particles, sounds
- origins.yml   origin names, colors, descriptions, GUI icons AND each legendary weapon (material, enchants, on-hit effect)
- messages.yml  every chat/action-bar message + HUD layout, plus a global find/replace for any other text
(The 10 origin keys and the 10 power tiers are fixed; everything inside them is editable.)

## Choosing your origin (config.yml -> origins)
selection-mode: choose  -> new players pick their origin in a menu (random = old behaviour)
allow-change: true      -> players may switch later (/origin choose or the main menu)
max-players-per-origin  -> cap how many players can share one origin (0 = unlimited)
Players who lose their power pick a new origin when they next gain mana. Admins: /wincors origin pick <player>.

## Ender pearls (config.yml -> ender-pearl)
Usable normally with a 5 minute cooldown. Throwing is blocked while in combat (block-in-pvp), but a pearl thrown before the fight still lands (cancel-landing-in-pvp: false).
The cooldown shows on the pearl item itself. /wincors cooldown reset <player|all> also clears it.

## Visual effects (config.yml -> visuals)
Powers use animated, colour-gradient particles: rotating magic circles, expanding shockwaves, double helixes, glowing shells, strike beams, dash ribbons and impact bursts.
Extra switches: visuals.colored-dust and visuals.magic-circles.
visuals.enabled turns them off; visuals.density scales the amount (0.5 = lighter). Each power uses its own
particle from powers.yml (the `particle` key).

## Look & feel
Each origin has a hex colour, symbol and tagline (origins.yml); every power has its own menu icon (powers.yml -> icon). Colours accept hex like &#FF5A36 everywhere (messages, items, menus).

## Keybinds (set these once in Minecraft: Options > Controls > Key Binds)
A server can't read raw keys, only actions, so rebind the actions:
- "Swap Item With Offhand"  -> G : use your selected power  (Sneak + G = cycle power)
- "Drop Selected Item"      -> V : while holding the Origin Core (nether star in slot 9) opens the menu
Right-clicking the Origin Core also opens the menu. Commands: /menu, /origin, /power, /mana.

## Movement powers (dashes, launches, blinks) - config.yml -> movement
direction-mode: look   (default) FREE AIM: goes exactly where your crosshair points, any angle
direction-mode: keys   W/A/S/D/Space choose the direction; no keys held = where you look
Also: dash-strength-multiplier, blink-distance-multiplier, fall-protection, launch-no-keys-up,
and powers.cooldown-multiplier (scales every active power cooldown). Edit, then /wincors reload.

## Mana Crystal recipe (harder, edit in config.yml -> recipe)
    Diamond Block | Echo Shard      | Diamond Block
    Echo Shard    | Netherite Ingot | Echo Shard
    Diamond Block | Echo Shard      | Diamond Block
Run /wincors reload after changing it.

## Admin (/wincors, op or wincors.admin) - running /wincors in-game opens the admin GUI. 100 actions:
Info:       info, top, stats, players, ping, egg (info/give)
Mana:       mana set/add/take/reset, origin set/random/clear/swap, resetplayer
Items:      crystal, crystalall, weapon give/reset, mace info/reset/give
Combat:     combat clear/status/tag/toggle, cooldown reset <player|all>
The End:    end open/close/toggle/status/evacuate
Vanish:     /vanish [player]  or  /vanish list   (alias /v, also /wincors vanish)
Invis:      /wincors invis [player]  - permanent potion invisibility (no particles) without vanishing
Tools:      heal, feed, fly, god, gamemode, speed
Moderation: tp, tphere, freeze, mute, clearinv, invsee, enderchest, kill, smite, broadcast
Calamity:   calamity give|remove|undo|status
Xaero maps: xaero status|on|off|resend
Textures:   pack info|send <player|all>|rebuild
Anti-cheat: ac alerts|status|reset|exempt|toggle|xray|log|info
Grace/PvP:  grace start|stop|status|extend [time], grace player <p> <time|off>, pvp on|off|status
Server:     lockdown on|off [reason], chat lock|unlock|clear, title <text|sub>, manaall, seen, tpall, kick
            config get/set, backup, save, reload, gui, help

## Vanish
Also gives the invisibility potion effect (config: vanish.potion-invisibility), so even admins see an invisible player.
Hidden from every player without wincors.admin (admins can still see each other), silent join/quit,
optional fake "left/joined the game" messages, mobs ignore you, no item pickup, no pressure plates,
saved across restarts, "VANISHED" shown in your action bar. A vanished player - or anyone wearing the invisibility POTION effect (config: vanish.hide-invisible-in-death-messages) - never appears in
death messages (their death message is removed; if they get a kill it just says "<victim> was slain"),
advancement announcements or the public mace/weapon/combat-log broadcasts. Admins still get the real death message tagged [Vanish]. Note: vanilla /list and the server-list
player count can still reveal vanished players.

## Grace period
/wincors grace start 2h  -> nobody can hurt anybody for 2 hours (saved across restarts, auto-ends with a broadcast).
Powers, projectiles, fire and splash potions are blocked too. /wincors grace player <p> 30m protects one player.
/wincors pvp off disables PvP until you turn it on again. Times: 30s 15m 2h 1d 1h30m.

## Anti-cheat & anti-xray (config.yml -> anticheat)
Behaviour checks with violation levels (VL), staff alerts (permission wincors.alerts, click an alert to teleport),
optional setback/kick/ban-command, and a log (plugins/WincorsSMP/anticheat.log):
  fly, speed, reach, cps (auto-clicker), nuker, blockreach, plus an ore-mining monitor for x-ray.
Your powers (dashes, launches, teleports, knockback) are exempted automatically.
Ops have wincors.bypass and are NOT checked while anticheat.bypass-permission is true - set it to false (or test with a non-op) to try it.
Alerts only by default; turn on punishments with anticheat.punish.kick-vl / command.

REAL anti-xray (hides ores from x-ray clients) is built into Paper - enable it (restart needed):
  config/paper-world-defaults.yml ->  anticheat:  anti-xray:  enabled: true  engine-mode: 2
The plugin checks this for you: /wincors ac info (and tells staff on join if it is off).
This plugin's checks are heuristics - they catch obvious cheating but are not a replacement for a dedicated packet-level anti-cheat.

## Custom textures (built into the plugin)
12 hand-drawn textures: the 10 legendary weapons, the Mana Crystal and the Origin Core.
The plugin builds plugins/WincorsSMP/WincorsSMP-pack.zip itself, can host it with a tiny built-in web server, and sends it to
players on join. Items are tagged with a custom model key, so players WITHOUT the pack just see normal vanilla items.
To enable: open TCP port 8165 and set resourcepack.self-host.public-host (your public IP/domain) in config.yml,
or host the zip yourself and set resourcepack.url. /wincors pack info shows the URL, hash and whether it is ready.
Weapons need NETHERITE_SWORD / NETHERITE_AXE as their base item to get a texture (Aqua's weapon is now a sword for that reason).
Edit the textures: src/main/resources/pack/assets/wincors/textures/item/*.png (32x32).

## New ability visuals
Wings on every ultimate (and halos), falling meteors, real jagged lightning bolts, ice/rock spikes, vortex pulls,
a clock for Time Freeze, dissolve/assemble teleports and speed lines for dashes. visuals.density / enabled still apply.

## Xaero's Minimap / World Map (config.yml -> xaero)
Uses Xaero's official server-control method (hidden system messages): the minimap is disabled completely, and fair-play turns
off cave mode on BOTH the minimap and the world map plus the entity radar. Xaero has no code that removes the world map screen
itself. Only official Xaero mods obey it; modified clients can ignore it. Waypoints are not touched by these codes.
/wincors xaero status|on|off|resend

## Calamity - the operator-only origin (config.yml -> calamity)
/wincors calamity give <player>   (the target must be an op / have wincors.calamity)   then   /calamity opens the 30 abilities.
30 abilities: Cataclysm, Annihilation Beam, Meteor Rain, Apocalypse Cone, Gravity Crush, Earth Splitter, Skyfall Pillar, Void Sphere,
Thunder God, Inferno, Black Hole, Shockwave Stomp, Judgment, Blink Strike, Tsunami, Earthquake, Magma Burst, Ice Age, Plague,
Time Stop, Dragon Breath, Soul Harvest, Colossus Form, Warp Step, Skyfall Dive, Annihilate Target, Chain Collapse, Worldbreaker,
Walking Calamity (toggle: disaster follows you), Rewind (undo your last destruction).
Holding the MACE makes every move bigger/deadlier (mace-multiplier) and doubles the block limit.
One move can destroy up to max-blocks-per-cast blocks (default 2000, hard limit 20000), spread over ticks so the server survives it.
Protected blocks (bedrock, barriers, command blocks, portals...) are never touched. Every move can be rewound (/calamity undo).
Set respect-protection: true to ask WorldGuard/claim plugins first; disabled-worlds to block it in spawn. ALWAYS keep world backups.
The Calamity is never random, never in the origin menus, and never loses its power when it dies.

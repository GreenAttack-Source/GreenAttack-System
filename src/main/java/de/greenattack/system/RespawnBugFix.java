package de.greenattack.system;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Internal respawn bug fix; intentionally has no config, command, or permission. */
public final class RespawnBugFix implements Listener {
   private static final java.util.Set<String> WORLDS = java.util.Set.of("farmwelt-1", "farmwelt-1_nether", "farmwelt-1_the_end");
   private final Map<UUID, Location> bedRespawns = new ConcurrentHashMap<>();

   @EventHandler(priority = EventPriority.LOWEST)
   public void onRespawnEarly(PlayerRespawnEvent event) {
      UUID id = event.getPlayer().getUniqueId();
      bedRespawns.remove(id);
      if (!event.isBedSpawn() && !event.isAnchorSpawn()) return;
      Location location = event.getRespawnLocation();
      if (location == null || location.getWorld() == null || !WORLDS.contains(location.getWorld().getName().toLowerCase(java.util.Locale.ROOT))) return;
      bedRespawns.put(id, location.clone());
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onRespawnLate(PlayerRespawnEvent event) {
      Location bed = bedRespawns.remove(event.getPlayer().getUniqueId());
      if (bed != null) event.setRespawnLocation(bed);
   }
}
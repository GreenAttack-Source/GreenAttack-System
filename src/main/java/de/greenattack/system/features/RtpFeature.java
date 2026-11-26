package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

public final class RtpFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final int TELEPORT_DELAY_TICKS = 60;
   private static final int MAX_WORLD_COORDINATE = 29999984;
   private static final Set<Material> HAZARDS = Set.of(
      Material.LAVA,
      Material.FIRE,
      Material.SOUL_FIRE,
      Material.MAGMA_BLOCK,
      Material.CACTUS,
      Material.CAMPFIRE,
      Material.SOUL_CAMPFIRE,
      Material.POWDER_SNOW,
      Material.SWEET_BERRY_BUSH,
      Material.WITHER_ROSE,
      Material.POINTED_DRIPSTONE
   );
   private final Map<UUID, Long> cooldowns = new HashMap<>();
   private final Map<UUID, RtpFeature.PendingRtp> pending = new HashMap<>();

   public RtpFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "rtp");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cRTP ist derzeit deaktiviert.");
         return true;
      }

      if (sender instanceof Player player) {
         String permission = this.config.getString("permissions.use", "system.rtp");
         if (!this.hasFeaturePermission(player, permission)) {
            this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
            return true;
         } else if (args.length != 0) {
            this.sendPath(player, "messages.usage", "&7Verwendung: &f/rtp");
            return true;
         } else {
            UUID playerId = player.getUniqueId();
            if (this.pending.containsKey(playerId)) {
               this.sendPath(player, "messages.already-searching", "&eDein Zufallsteleport läuft bereits. Bewege dich nicht.");
               return true;
            } else {
               World world = player.getWorld();
               if (!this.isAllowedWorld(world)) {
                  this.send(
                     player, this.text("messages.world-not-allowed", "&cRTP ist in der Welt &e%world% &cnicht verfügbar.").replace("%world%", world.getName())
                  );
                  return true;
               } else {
                  long now = System.currentTimeMillis();
                  Long expiresAt = this.cooldowns.get(playerId);
                  if (expiresAt != null && expiresAt > now) {
                     long seconds = Math.max(1L, (expiresAt - now + 999L) / 1000L);
                     this.send(
                        player,
                        this.text("messages.cooldown", "&cBitte warte noch &e%seconds% Sekunden &cbis zum nächsten RTP.")
                           .replace("%seconds%", Long.toString(seconds))
                     );
                     return true;
                  } else {
                     int minRadius = this.config.getInt("search.min-radius", 100);
                     int maxRadius = this.config.getInt("search.max-radius", 5000);
                     int attempts = this.config.getInt("search.max-attempts", 25);
                     if (minRadius >= 0 && maxRadius >= minRadius && maxRadius <= 29999984 && attempts >= 1) {
                        Location start = player.getLocation();
                        RtpFeature.PendingRtp request = new RtpFeature.PendingRtp(
                           player, world, start.getYaw(), start.getPitch(), minRadius, maxRadius, attempts
                        );
                        this.pending.put(playerId, request);
                        this.sendPath(player, "messages.preparing", "&aSuche einen sicheren Ort. Bewege dich nicht; der Teleport startet in 3 Sekunden.");
                        request.delayTask = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
                           if (this.isCurrent(request)) {
                              request.delayElapsed = true;
                              this.tryFinish(request);
                           }
                        }, 60L);
                        this.searchNextCandidate(request);
                        return true;
                     } else {
                        this.sendPath(player, "messages.invalid-config", "&cDie RTP-Konfiguration ist ungültig. Bitte informiere ein Teammitglied.");
                        return true;
                     }
                  }
               }
            }
         }
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
   }

   private boolean isAllowedWorld(World world) {
      List<String> allowedWorlds = this.config.getStringList("allowed-worlds");
      return allowedWorlds.isEmpty()
         || allowedWorlds.stream().filter(name -> name != null && !name.isBlank()).anyMatch(name -> name.trim().equalsIgnoreCase(world.getName()));
   }

   private void searchNextCandidate(RtpFeature.PendingRtp request) {
      if (this.isCurrent(request) && request.destination == null) {
         if (!request.player.isOnline()) {
            this.cancel(request, null);
         } else if (request.attemptsRemaining <= 0) {
            this.cancel(request, "messages.no-safe-location");
         } else {
            RtpFeature.Candidate candidate = this.nextCandidate(request);
            if (candidate == null) {
               this.cancel(request, "messages.invalid-config");
            } else {
               request.attemptsRemaining--;
               request.world.getChunkAtAsync(candidate.x >> 4, candidate.z >> 4, true, false).whenComplete((chunk, failure) -> this.runOnServerThread(() -> {
                  if (this.isCurrent(request)) {
                     if (failure == null && chunk != null && chunk.isLoaded()) {
                        Location safeLocation = this.safeLocation(request.world, candidate.x, candidate.z, request.startYaw, request.startPitch);
                        if (safeLocation == null) {
                           this.searchNextCandidate(request);
                        } else {
                           request.destination = safeLocation;
                           this.tryFinish(request);
                        }
                     } else {
                        this.searchNextCandidate(request);
                     }
                  }
               }));
            }
         }
      }
   }

   private RtpFeature.Candidate nextCandidate(RtpFeature.PendingRtp request) {
      WorldBorder border = request.world.getWorldBorder();
      Location center = border.getCenter();
      double borderRadius = Math.max(0.0, border.getSize() / 2.0 - 2.0);
      double effectiveMax = Math.min(request.maxRadius, borderRadius);
      if (effectiveMax < request.minRadius) {
         return null;
      }

      ThreadLocalRandom random = ThreadLocalRandom.current();
      double minSquared = (double)request.minRadius * request.minRadius;
      double maxSquared = effectiveMax * effectiveMax;
      double radius = maxSquared == minSquared ? request.minRadius : Math.sqrt(random.nextDouble(minSquared, maxSquared));
      double angle = random.nextDouble(0.0, Math.PI * 2);
      int x = (int)Math.floor(center.getX() + Math.cos(angle) * radius);
      int z = (int)Math.floor(center.getZ() + Math.sin(angle) * radius);
      return Math.abs(x) <= 29999984 && Math.abs(z) <= 29999984 ? new RtpFeature.Candidate(x, z) : null;
   }

   private Location safeLocation(World world, int x, int z, float yaw, float pitch) {
      int groundY = world.getHighestBlockYAt(x, z);
      if (groundY >= world.getMinHeight() && groundY + 2 < world.getMaxHeight()) {
         Block ground = world.getBlockAt(x, groundY, z);
         Block feet = world.getBlockAt(x, groundY + 1, z);
         Block head = world.getBlockAt(x, groundY + 2, z);
         if (!ground.getType().isSolid() || ground.isLiquid() || HAZARDS.contains(ground.getType())) {
            return null;
         } else if (!feet.isPassable() || feet.isLiquid() || HAZARDS.contains(feet.getType())) {
            return null;
         } else if (head.isPassable() && !head.isLiquid() && !HAZARDS.contains(head.getType())) {
            Location destination = new Location(world, x + 0.5, groundY + 1.0, z + 0.5, yaw, pitch);
            return world.getWorldBorder().isInside(destination) ? destination : null;
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private void tryFinish(RtpFeature.PendingRtp request) {
      if (this.isCurrent(request) && request.delayElapsed && request.destination != null && !request.destinationChunkLoading) {
         if (!request.player.isOnline()) {
            this.cancel(request, null);
         } else {
            Location destination = request.destination;
            request.destinationChunkLoading = true;
            request.world
               .getChunkAtAsync(destination.getBlockX() >> 4, destination.getBlockZ() >> 4, true, false)
               .whenComplete(
                  (chunk, failure) -> this.runOnServerThread(
                     () -> {
                        if (this.isCurrent(request)) {
                           request.destinationChunkLoading = false;
                           if (failure == null && chunk != null && chunk.isLoaded()) {
                              Location rechecked = this.safeLocation(
                                 request.world, destination.getBlockX(), destination.getBlockZ(), request.startYaw, request.startPitch
                              );
                              if (rechecked == null) {
                                 request.destination = null;
                                 this.searchNextCandidate(request);
                              } else {
                                 this.finishRequest(request);
                                 if (!request.player.teleport(rechecked, TeleportCause.COMMAND)) {
                                    this.sendPath(request.player, "messages.teleport-failed", "&cDer Teleport wurde abgebrochen.");
                                 } else {
                                    int cooldownSeconds = Math.max(0, Math.min(86400, this.config.getInt("search.cooldown-seconds", 30)));
                                    if (cooldownSeconds > 0) {
                                       this.cooldowns.put(request.player.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
                                    }

                                    this.sendPath(request.player, "messages.teleported", "&aDu wurdest sicher zufällig teleportiert.");
                                 }
                              }
                           } else {
                              request.destination = null;
                              this.searchNextCandidate(request);
                           }
                        }
                     }
                  )
               );
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlayerMove(PlayerMoveEvent event) {
      RtpFeature.PendingRtp request = this.pending.get(event.getPlayer().getUniqueId());
      if (request != null && event.getTo() != null) {
         Location from = event.getFrom();
         Location to = event.getTo();
         boolean changedWorld = from.getWorld() != to.getWorld();
         boolean changedPosition = Double.compare(from.getX(), to.getX()) != 0
            || Double.compare(from.getY(), to.getY()) != 0
            || Double.compare(from.getZ(), to.getZ()) != 0;
         if (changedWorld || changedPosition) {
            this.cancel(request, "messages.cancelled-movement");
         }
      }
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      RtpFeature.PendingRtp request = this.pending.get(event.getPlayer().getUniqueId());
      if (request != null) {
         this.cancel(request, null);
      }
   }

   private void runOnServerThread(Runnable task) {
      if (this.plugin.isEnabled()) {
         try {
            Bukkit.getScheduler().runTask(this.plugin, task);
         } catch (IllegalPluginAccessException var3) {
         }
      }
   }

   private boolean isCurrent(RtpFeature.PendingRtp request) {
      return this.plugin.isEnabled() && this.pending.get(request.player.getUniqueId()) == request;
   }

   private void cancel(RtpFeature.PendingRtp request, String messagePath) {
      if (this.isCurrent(request)) {
         this.finishRequest(request);
         if (messagePath != null && request.player.isOnline()) {
            String fallback = messagePath.equals("messages.cancelled-movement")
               ? "&cDer Zufallsteleport wurde abgebrochen, weil du dich bewegt hast."
               : "&cEs wurde kein sicherer RTP-Ort gefunden. Versuche es erneut.";
            this.sendPath(request.player, messagePath, fallback);
         }
      }
   }

   private void finishRequest(RtpFeature.PendingRtp request) {
      this.pending.remove(request.player.getUniqueId(), request);
      if (request.delayTask != null) {
         request.delayTask.cancel();
         request.delayTask = null;
      }
   }

   public void shutdownFeature() {
      for (RtpFeature.PendingRtp request : this.pending.values().toArray(RtpFeature.PendingRtp[]::new)) {
         this.finishRequest(request);
      }

      this.pending.clear();
      this.cooldowns.clear();
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      return List.of();
   }

   private record Candidate(int x, int z) {
   }

   private static final class PendingRtp {
      private final Player player;
      private final World world;
      private final float startYaw;
      private final float startPitch;
      private final int minRadius;
      private final int maxRadius;
      private int attemptsRemaining;
      private boolean delayElapsed;
      private boolean destinationChunkLoading;
      private Location destination;
      private BukkitTask delayTask;

      private PendingRtp(Player player, World world, float startYaw, float startPitch, int minRadius, int maxRadius, int attemptsRemaining) {
         this.player = player;
         this.world = world;
         this.startYaw = startYaw;
         this.startPitch = startPitch;
         this.minRadius = minRadius;
         this.maxRadius = maxRadius;
         this.attemptsRemaining = attemptsRemaining;
      }
   }
}

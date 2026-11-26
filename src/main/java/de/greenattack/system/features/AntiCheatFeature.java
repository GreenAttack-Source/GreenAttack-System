package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.RayTraceResult;

/** Lightweight movement and suspicious ore-mining checks. */
public final class AntiCheatFeature extends FeatureModule implements Listener {
   private static final String ALERT_PERMISSION = "system.anticheat.alerts";
   private static final double DEFAULT_MAX_HORIZONTAL_STEP = 2.5D;
   private static final int DEFAULT_ALERT_THRESHOLD = 3;
   private static final long DEFAULT_WINDOW_MILLIS = 600_000L;
   private static final double DEFAULT_CLUSTER_DISTANCE = 5.0D;
   private static final double DEFAULT_MAX_TARGET_DISTANCE = 6.0D;
   private final Map<UUID, Deque<OreSuspicion>> recentOreSuspicions = new HashMap<>();
   private final Map<UUID, Long> lastAlertAt = new HashMap<>();

   public AntiCheatFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "anticheat");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   @EventHandler(ignoreCancelled = true)
   public void onMove(PlayerMoveEvent event) {
      if (!this.isEnabled() || !this.config.getBoolean("movement.enabled", true)
            || event instanceof PlayerTeleportEvent) {
         return;
      }

      Player player = event.getPlayer();
      if (player.isOp() || player.isFlying() || player.getAllowFlight()
            || player.getGameMode() == GameMode.CREATIVE
            || player.getGameMode() == GameMode.SPECTATOR
            || player.isInsideVehicle() || player.isGliding()) {
         return;
      }

      Location from = event.getFrom();
      Location to = event.getTo();
      if (to == null || from.getWorld() == null || to.getWorld() == null
            || !from.getWorld().equals(to.getWorld())) {
         return;
      }

      // Falling, jumping and knockback change Y and must not count as fly evidence.
      double dx = to.getX() - from.getX();
      double dz = to.getZ() - from.getZ();
      double maxStep = Math.max(0.5D, this.config.getDouble("movement.max-horizontal-step", DEFAULT_MAX_HORIZONTAL_STEP));
      if (dx * dx + dz * dz > maxStep * maxStep) {
         player.sendMessage(this.text("messages.movement-warning", "&c[AntiCheat] Ungewöhnlich schnelle Bewegung erkannt."));
         this.plugin.getLogger().warning("Ungewöhnlich große horizontale Bewegung bei " + player.getName() + ".");
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onOreBreak(BlockBreakEvent event) {
      if (!this.isEnabled() || !this.config.getBoolean("xray.enabled", true)) {
         return;
      }

      Player player = event.getPlayer();
      if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
            || !isTrackedOre(event.getBlock().getType())) {
         return;
      }

      Block ore = event.getBlock();
      Location eye = player.getEyeLocation();
      double distance = eye.distance(ore.getLocation().add(0.5D, 0.5D, 0.5D));
      double maxTargetDistance = Math.max(1.0D, this.config.getDouble("xray.max-target-distance", DEFAULT_MAX_TARGET_DISTANCE));
      if (distance > maxTargetDistance) {
         return;
      }

      // A normal client can only break the block it is looking at. If another
      // solid block is hit first, the ore was hidden behind a wall at break time.
      RayTraceResult ray = player.getWorld().rayTraceBlocks(
            eye, eye.getDirection(), distance + 0.25D, FluidCollisionMode.NEVER, true);
      if (ray == null || ray.getHitBlock() == null || sameBlock(ray.getHitBlock(), ore)) {
         return;
      }

      recordSuspicion(player, ore);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.recentOreSuspicions.remove(event.getPlayer().getUniqueId());
      this.lastAlertAt.remove(event.getPlayer().getUniqueId());
   }

   private void recordSuspicion(Player player, Block ore) {
      long now = System.currentTimeMillis();
      long windowMillis = Math.max(10L, this.config.getLong("xray.window-seconds", 600L)) * 1000L;
      double clusterDistance = Math.max(1.0D, this.config.getDouble("xray.cluster-distance-blocks", DEFAULT_CLUSTER_DISTANCE));
      double clusterDistanceSquared = clusterDistance * clusterDistance;
      Deque<OreSuspicion> points = this.recentOreSuspicions.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
      points.removeIf(point -> now - point.timestamp > windowMillis);

      for (OreSuspicion point : points) {
         if (point.world.equals(ore.getWorld().getUID())
               && point.location.distanceSquared(ore.getLocation()) <= clusterDistanceSquared) {
            return; // Several blocks from one vein count as one suspicious find.
         }
      }

      points.addLast(new OreSuspicion(ore.getWorld().getUID(), ore.getLocation().clone(), now));
      int threshold = Math.max(1, this.config.getInt("xray.alert-threshold", DEFAULT_ALERT_THRESHOLD));
      if (points.size() < threshold) {
         return;
      }

      long cooldownMillis = Math.max(0L, this.config.getLong("xray.alert-cooldown-seconds", 60L)) * 1000L;
      long lastAlert = this.lastAlertAt.getOrDefault(player.getUniqueId(), 0L);
      if (now - lastAlert < cooldownMillis) {
         return;
      }

      this.lastAlertAt.put(player.getUniqueId(), now);
      String message = this.text("messages.xray-alert",
            "&cX-Ray-Verdacht: &f%player% &7hat mehrere Erze ohne freie Sicht abgebaut (&e%count%&7 Funde).");
      message = message.replace("%player%", player.getName()).replace("%count%", Integer.toString(points.size()));
      for (Player online : this.plugin.getServer().getOnlinePlayers()) {
         if (online.isOp() || this.hasFeaturePermission(online, ALERT_PERMISSION)) {
            this.send(online, message);
         }
      }
      this.plugin.getLogger().warning("X-Ray-Verdacht bei " + player.getName() + ": " + points.size()
            + " voneinander entfernte Erzfunde ohne Sichtlinie innerhalb von "
            + this.config.getLong("xray.window-seconds", 600L) + " Sekunden.");
      points.clear();
   }

   private static boolean isTrackedOre(Material material) {
      return material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS;
   }

   private static boolean sameBlock(Block first, Block second) {
      return first.getWorld().equals(second.getWorld()) && first.getX() == second.getX()
            && first.getY() == second.getY() && first.getZ() == second.getZ();
   }

   public void reloadFeature() {
      this.reload();
      this.recentOreSuspicions.clear();
      this.lastAlertAt.clear();
   }

   public void shutdownFeature() {
      this.recentOreSuspicions.clear();
      this.lastAlertAt.clear();
   }

   private record OreSuspicion(UUID world, Location location, long timestamp) {
   }
}

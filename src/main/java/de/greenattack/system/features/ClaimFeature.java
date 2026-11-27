package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Free chunk claims and per-claim trust controls for SMP worlds. */
public final class ClaimFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String USE_PERMISSION = "system.claim.use";
   private static final String ADMIN_PERMISSION = "system.claim.admin";
   private final Map<ChunkKey, Claim> claims = new HashMap<>();
   private final Map<UUID, ChunkKey> lastPlayerChunks = new HashMap<>();
   private final File claimsFile;

   public ClaimFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "claims");
      this.claimsFile = this.featureFile("claims.yml");
      this.loadClaims();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Claim-Feature ist deaktiviert.");
         return true;
      }
      if (!this.hasFeaturePermission(sender, this.config.getString("permissions.use", USE_PERMISSION))) {
         this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return true;
      }
      if (!(sender instanceof Player player)) {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
      if (args.length == 0 || args[0].equalsIgnoreCase("claim")) {
         this.claimCurrentChunk(player);
         return true;
      }

      switch (args[0].toLowerCase(Locale.ROOT)) {
         case "unclaim" -> this.unclaimCurrentChunk(player);
         case "info" -> this.showInfo(player);
         case "trust" -> this.handleTrust(player, args);
         case "settings" -> this.handleSettings(player, args);
         case "admin" -> this.handleAdmin(player, args);
         case "help" -> this.showHelp(player);
         default -> this.showHelp(player);
      }
      return true;
   }

   private void claimCurrentChunk(Player player) {
      Chunk chunk = player.getLocation().getChunk();
      if (this.isDisabledWorld(chunk.getWorld().getName())) {
         this.sendPath(player, "messages.world-disabled", "&cClaims sind in dieser Welt deaktiviert.");
         return;
      }
      ChunkKey key = key(chunk);
      Claim existing = this.claims.get(key);
      if (existing != null) {
         this.send(player, this.config.getString("messages.already-claimed", "&cDieser Chunk gehört bereits &e%owner%&c.")
            .replace("%owner%", existing.ownerName));
         return;
      }
      int maxClaims = Math.max(1, this.config.getInt("max-claims-per-player", 10));
      if (!player.hasPermission("system.claim.unlimited") && !player.hasPermission(ADMIN_PERMISSION)
         && this.countClaims(player.getUniqueId()) >= maxClaims) {
         this.send(player, this.config.getString("messages.claim-limit", "&cDu hast dein Limit von &e%limit% &cChunks erreicht.")
            .replace("%limit%", Integer.toString(maxClaims)));
         return;
      }

      Claim claim = new Claim(player.getUniqueId(), player.getName());
      this.claims.put(key, claim);
      if (!this.saveClaims()) {
         this.claims.remove(key);
         this.sendPath(player, "messages.save-failed", "&cDer Claim konnte nicht gespeichert werden.");
         return;
      }
      this.send(player, this.config.getString("messages.claimed", "&aChunk gesichert. Claims sind kostenlos. &7(&f%count%&7/&f%limit%&7)")
         .replace("%count%", Integer.toString(this.countClaims(player.getUniqueId())))
         .replace("%limit%", Integer.toString(maxClaims)));
   }

   private void unclaimCurrentChunk(Player player) {
      ChunkKey key = key(player.getLocation().getChunk());
      Claim claim = this.claims.get(key);
      if (claim == null) {
         this.sendPath(player, "messages.not-claimed", "&cDieser Chunk ist nicht gesichert.");
         return;
      }
      if (!claim.owner.equals(player.getUniqueId()) && !player.hasPermission(ADMIN_PERMISSION)) {
         this.send(player, this.config.getString("messages.not-owner", "&cNur &e%owner% &ckann diesen Claim verwalten.")
            .replace("%owner%", claim.ownerName));
         return;
      }
      this.claims.remove(key);
      if (!this.saveClaims()) {
         this.claims.put(key, claim);
         this.sendPath(player, "messages.save-failed", "&cDer Claim konnte nicht gespeichert werden.");
         return;
      }
      this.sendPath(player, "messages.unclaimed", "&aDer Chunk ist jetzt wieder frei.");
   }

   private void handleTrust(Player player, String[] args) {
      if (args.length < 2) {
         this.sendPath(player, "messages.trust-usage", "&7Verwendung: &f/claim trust <add|remove|list> [Spieler]");
         return;
      }
      ChunkKey key = key(player.getLocation().getChunk());
      Claim claim = this.claims.get(key);
      if (claim == null) {
         this.sendPath(player, "messages.not-claimed", "&cDieser Chunk ist nicht gesichert.");
         return;
      }
      if (!claim.owner.equals(player.getUniqueId()) && !player.hasPermission(ADMIN_PERMISSION)) {
         this.send(player, this.config.getString("messages.not-owner", "&cNur &e%owner% &ckann diesen Claim verwalten.")
            .replace("%owner%", claim.ownerName));
         return;
      }
      String action = args[1].toLowerCase(Locale.ROOT);
      if (action.equals("list")) {
         this.send(player, "&aVertraute Spieler: &f" + (claim.trusted.isEmpty() ? "keine" : String.join(", ", claim.trusted.values())));
         return;
      }
      if (args.length != 3 || !(action.equals("add") || action.equals("remove"))) {
         this.sendPath(player, "messages.trust-usage", "&7Verwendung: &f/claim trust <add|remove|list> [Spieler]");
         return;
      }
      Player target = Bukkit.getOnlinePlayers().stream().filter(online -> online.getName().equalsIgnoreCase(args[2])).findFirst().orElse(null);
      if (target == null) {
         this.sendPath(player, "messages.player-not-found", "&cDer Spieler muss online sein.");
         return;
      }
      if (target.getUniqueId().equals(claim.owner)) {
         this.sendPath(player, "messages.trust-owner", "&cDer Besitzer braucht keinen Trust-Eintrag.");
         return;
      }
      boolean wasTrusted = claim.trusted.containsKey(target.getUniqueId());
      if (action.equals("add")) {
         if (wasTrusted) {
            this.send(player, this.config.getString("messages.trust-exists", "&e%player% &7ist bereits vertraut.").replace("%player%", target.getName()));
            return;
         }
         claim.trusted.put(target.getUniqueId(), target.getName());
      } else {
         if (!wasTrusted) {
            this.send(player, this.config.getString("messages.trust-missing", "&e%player% &7ist diesem Claim nicht vertraut.").replace("%player%", target.getName()));
            return;
         }
         claim.trusted.remove(target.getUniqueId());
      }
      if (!this.saveClaims()) {
         if (action.equals("add")) {
            claim.trusted.remove(target.getUniqueId());
         } else {
            claim.trusted.put(target.getUniqueId(), target.getName());
         }
         this.sendPath(player, "messages.save-failed", "&cDer Claim konnte nicht gespeichert werden.");
         return;
      }
      String path = action.equals("add") ? "messages.trust-added" : "messages.trust-removed";
      String fallback = action.equals("add") ? "&a%player% darf diesen Chunk jetzt nutzen. (kostenlos)" : "&e%player% darf diesen Chunk nicht mehr nutzen.";
      this.send(player, this.config.getString(path, fallback).replace("%player%", target.getName()));
   }

   private void handleSettings(Player player, String[] args) {
      if (args.length < 3 || !(args[1].equalsIgnoreCase("title") || args[1].equalsIgnoreCase("subtitle"))) {
         this.sendPath(player, "messages.settings-usage", "&7Verwendung: &f/claim settings <title|subtitle> <Text|reset>");
         return;
      }
      ChunkKey key = key(player.getLocation().getChunk());
      Claim claim = this.claims.get(key);
      if (claim == null) {
         this.sendPath(player, "messages.not-claimed", "&cDieser Chunk ist nicht gesichert.");
         return;
      }
      if (!claim.owner.equals(player.getUniqueId()) && !player.hasPermission(ADMIN_PERMISSION)) {
         this.send(player, this.config.getString("messages.not-owner", "&cNur &e%owner% &ckann diesen Claim verwalten.")
            .replace("%owner%", claim.ownerName));
         return;
      }
      String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
      if (text.length() > 80) {
         this.sendPath(player, "messages.setting-too-long", "&cTitel und Untertitel dürfen höchstens 80 Zeichen lang sein.");
         return;
      }
      boolean title = args[1].equalsIgnoreCase("title");
      String previous = title ? claim.title : claim.subtitle;
      String value = text.equalsIgnoreCase("reset") ? "" : text;
      if (title) {
         claim.title = value;
      } else {
         claim.subtitle = value;
      }
      if (!this.saveClaims()) {
         if (title) {
            claim.title = previous;
         } else {
            claim.subtitle = previous;
         }
         this.sendPath(player, "messages.save-failed", "&cDer Claim konnte nicht gespeichert werden.");
         return;
      }
      this.sendPath(player, "messages.setting-saved", "&aTitel/Untertitel gespeichert. Änderungen sind kostenlos.");
   }

   private void handleAdmin(Player player, String[] args) {
      if (!player.hasPermission(ADMIN_PERMISSION)) {
         this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return;
      }
      if (args.length == 2 && args[1].equalsIgnoreCase("unclaim")) {
         ChunkKey key = key(player.getLocation().getChunk());
         Claim claim = this.claims.remove(key);
         if (claim == null) {
            this.sendPath(player, "messages.not-claimed", "&cDieser Chunk ist nicht gesichert.");
         } else if (!this.saveClaims()) {
            this.claims.put(key, claim);
            this.sendPath(player, "messages.save-failed", "&cDer Claim konnte nicht gespeichert werden.");
         } else {
            this.sendPath(player, "messages.admin-unclaimed", "&aClaim administrativ entfernt.");
         }
      } else {
         this.sendPath(player, "messages.admin-usage", "&7Verwendung: &f/claim admin unclaim");
      }
   }

   private void showInfo(Player player) {
      Claim claim = this.claims.get(key(player.getLocation().getChunk()));
      if (claim == null) {
         this.sendPath(player, "messages.not-claimed", "&cDieser Chunk ist nicht gesichert.");
         return;
      }
      String trusted = claim.trusted.isEmpty() ? "keine" : String.join(", ", claim.trusted.values());
      this.send(player, this.config.getString("messages.info", "&aClaim von &e%owner%&a. Vertraut: &f%trusted%")
         .replace("%owner%", claim.ownerName).replace("%trusted%", trusted));
   }

   private void showHelp(Player player) {
      this.sendPath(player, "messages.help", "&a/claim &7Chunk kostenlos sichern | &a/claim unclaim &7freigeben | &a/claim trust add <Spieler> &7vertrauen | &a/claim trust remove <Spieler> &7entfernen | &a/claim settings <title|subtitle> <Text|reset> &7Anzeige setzen | &a/claim info");
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.isEnabled() || !this.hasFeaturePermission(sender, this.config.getString("permissions.use", USE_PERMISSION))) {
         return List.of();
      }
      if (args.length == 1) {
         List<String> choices = new ArrayList<>(List.of("claim", "unclaim", "trust", "settings", "info", "help"));
         if (sender.hasPermission(ADMIN_PERMISSION)) {
            choices.add("admin");
         }
         return prefixMatches(choices, args[0]);
      }
      if (args.length == 2 && args[0].equalsIgnoreCase("trust")) {
         return prefixMatches(List.of("add", "remove", "list"), args[1]);
      }
      if (args.length == 3 && args[0].equalsIgnoreCase("trust") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
         return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).sorted(String.CASE_INSENSITIVE_ORDER).toList();
      }
      if (args.length == 2 && args[0].equalsIgnoreCase("settings")) {
         return prefixMatches(List.of("title", "subtitle"), args[1]);
      }
      if (args.length == 3 && args[0].equalsIgnoreCase("settings")) {
         return prefixMatches(List.of("reset"), args[2]);
      }
      if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission(ADMIN_PERMISSION)) {
         return prefixMatches(List.of("unclaim"), args[1]);
      }
      return List.of();
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onBlockBreak(BlockBreakEvent event) {
      this.protect(event.getPlayer(), event.getBlock().getLocation(), event::setCancelled);
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onBlockPlace(BlockPlaceEvent event) {
      this.protect(event.getPlayer(), event.getBlock().getLocation(), event::setCancelled);
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onInteract(PlayerInteractEvent event) {
      Block block = event.getClickedBlock();
      if (block != null) {
         this.protect(event.getPlayer(), block.getLocation(), event::setCancelled);
      }
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onInteractEntity(PlayerInteractEntityEvent event) {
      this.protect(event.getPlayer(), event.getRightClicked().getLocation(), event::setCancelled);
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onBucketEmpty(PlayerBucketEmptyEvent event) {
      this.protect(event.getPlayer(), event.getBlock().getLocation(), event::setCancelled);
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onBucketFill(PlayerBucketFillEvent event) {
      this.protect(event.getPlayer(), event.getBlock().getLocation(), event::setCancelled);
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onHangingPlace(HangingPlaceEvent event) {
      if (event.getPlayer() != null) {
         this.protect(event.getPlayer(), event.getEntity().getLocation(), event::setCancelled);
      }
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onHangingBreak(HangingBreakByEntityEvent event) {
      if (event.getRemover() instanceof Player player) {
         this.protect(player, event.getEntity().getLocation(), event::setCancelled);
      }
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onEntityDamage(EntityDamageByEntityEvent event) {
      Player player = null;
      if (event.getDamager() instanceof Player direct) {
         player = direct;
      } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
         player = shooter;
      }
      if (player != null) {
         this.protect(player, event.getEntity().getLocation(), event::setCancelled);
      }
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onEntityExplode(EntityExplodeEvent event) {
      event.blockList().removeIf(block -> this.isProtectedFromExplosions(block.getLocation()));
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onBlockExplode(BlockExplodeEvent event) {
      event.blockList().removeIf(block -> this.isProtectedFromExplosions(block.getLocation()));
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onBlockBurn(BlockBurnEvent event) {
      if (this.isProtectedFromExplosions(event.getBlock().getLocation())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onBlockFlow(BlockFromToEvent event) {
      Claim destination = this.claims.get(key(event.getToBlock().getLocation()));
      Claim source = this.claims.get(key(event.getBlock().getLocation()));
      if (this.isEnabled() && destination != null && (source == null || !source.owner.equals(destination.owner))) {
         event.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
   public void onEntityChangeBlock(EntityChangeBlockEvent event) {
      if (this.isProtectedFromExplosions(event.getBlock().getLocation())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onMove(PlayerMoveEvent event) {
      if (!this.isEnabled() || event.getTo() == null || sameChunk(event.getFrom(), event.getTo())) {
         return;
      }
      this.updateClaimTitle(event.getPlayer(), event.getTo());
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         Player player = event.getPlayer();
         if (player.isOnline()) {
            this.updateClaimTitle(player, player.getLocation());
         }
      });
   }

   @EventHandler
   public void onWorldChange(PlayerChangedWorldEvent event) {
      this.updateClaimTitle(event.getPlayer(), event.getPlayer().getLocation());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.lastPlayerChunks.remove(event.getPlayer().getUniqueId());
   }

   private void protect(Player player, Location location, java.util.function.Consumer<Boolean> cancel) {
      if (!this.isEnabled()) {
         return;
      }
      Claim claim = this.claims.get(key(location));
      if (claim != null && !this.canBuild(player, claim)) {
         cancel.accept(true);
         this.send(player, this.config.getString("messages.protected", "&cDieses Grundstück gehört &e%owner%&c.").replace("%owner%", claim.ownerName));
      }
   }

   private boolean isProtectedFromExplosions(Location location) {
      return this.isEnabled() && this.claims.containsKey(key(location));
   }

   private boolean canBuild(Player player, Claim claim) {
      return player.getUniqueId().equals(claim.owner) || claim.trusted.containsKey(player.getUniqueId()) || player.hasPermission(ADMIN_PERMISSION);
   }

   private void updateClaimTitle(Player player, Location location) {
      ChunkKey currentKey = key(location);
      ChunkKey previousKey = this.lastPlayerChunks.put(player.getUniqueId(), currentKey);
      if (currentKey.equals(previousKey)) {
         return;
      }
      Claim claim = this.claims.get(currentKey);
      if (claim == null) {
         return;
      }
      String title = claim.title.isBlank() ? this.config.getString("defaults.title", "&2Claim von %owner%") : claim.title;
      String subtitle = claim.subtitle.isBlank() ? this.config.getString("defaults.subtitle", "&7Claim geschützt") : claim.subtitle;
      title = title.replace("%owner%", claim.ownerName);
      subtitle = subtitle.replace("%owner%", claim.ownerName).replace("%title%", claim.title).replace("%subtitle%", claim.subtitle);
      player.sendTitle(color(title), color(subtitle), Math.max(0, this.config.getInt("title.fade-in", 5)),
         Math.max(0, this.config.getInt("title.stay", 45)), Math.max(0, this.config.getInt("title.fade-out", 10)));
   }

   private int countClaims(UUID owner) {
      int count = 0;
      for (Claim claim : this.claims.values()) {
         if (claim.owner.equals(owner)) {
            count++;
         }
      }
      return count;
   }

   private boolean isDisabledWorld(String worldName) {
      return this.config.getStringList("disabled-worlds").stream().anyMatch(world -> world.equalsIgnoreCase(worldName));
   }

   private void loadClaims() {
      this.claims.clear();
      if (!this.claimsFile.isFile()) {
         return;
      }
      YamlConfiguration data = YamlConfiguration.loadConfiguration(this.claimsFile);
      ConfigurationSection worlds = data.getConfigurationSection("claims");
      if (worlds == null) {
         return;
      }
      for (String worldName : worlds.getKeys(false)) {
         UUID worldId;
         try {
            worldId = UUID.fromString(worldName);
         } catch (IllegalArgumentException ignored) {
            continue;
         }
         ConfigurationSection chunks = worlds.getConfigurationSection(worldName);
         if (chunks == null) {
            continue;
         }
         for (String chunkName : chunks.getKeys(false)) {
            String[] coordinates = chunkName.split(",", 2);
            if (coordinates.length != 2) {
               continue;
            }
            try {
               int chunkX = Integer.parseInt(coordinates[0]);
               int chunkZ = Integer.parseInt(coordinates[1]);
               ConfigurationSection section = chunks.getConfigurationSection(chunkName);
               if (section == null) {
                  continue;
               }
               UUID owner = UUID.fromString(section.getString("owner", ""));
               Claim claim = new Claim(owner, section.getString("owner-name", "Spieler"));
               claim.title = section.getString("title", "");
               claim.subtitle = section.getString("subtitle", "");
               ConfigurationSection trusted = section.getConfigurationSection("trusted");
               if (trusted != null) {
                  for (String id : trusted.getKeys(false)) {
                     try {
                        claim.trusted.put(UUID.fromString(id), trusted.getString(id, id));
                     } catch (IllegalArgumentException ignored) {
                     }
                  }
               }
               this.claims.put(new ChunkKey(worldId, chunkX, chunkZ), claim);
            } catch (IllegalArgumentException ignored) {
               this.plugin.getLogger().warning("Ungültiger Claim-Eintrag wird übersprungen: " + worldName + "/" + chunkName);
            }
         }
      }
      this.plugin.getLogger().info(this.claims.size() + " Chunk-Claims geladen.");
   }

   private boolean saveClaims() {
      YamlConfiguration data = new YamlConfiguration();
      for (Map.Entry<ChunkKey, Claim> entry : this.claims.entrySet()) {
         ChunkKey key = entry.getKey();
         Claim claim = entry.getValue();
         String path = "claims." + key.worldId + "." + key.x + "," + key.z;
         data.set(path + ".owner", claim.owner.toString());
         data.set(path + ".owner-name", claim.ownerName);
         data.set(path + ".title", claim.title);
         data.set(path + ".subtitle", claim.subtitle);
         for (Map.Entry<UUID, String> trusted : claim.trusted.entrySet()) {
            data.set(path + ".trusted." + trusted.getKey(), trusted.getValue());
         }
      }
      try {
         File parent = this.claimsFile.getParentFile();
         if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Feature-Ordner konnte nicht erstellt werden.");
         }
         data.save(this.claimsFile);
         return true;
      } catch (IOException exception) {
         this.plugin.getLogger().severe("Claims konnten nicht gespeichert werden: " + exception.getMessage());
         return false;
      }
   }

   private static ChunkKey key(Chunk chunk) {
      return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
   }

   private static ChunkKey key(Location location) {
      return new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
   }

   private static boolean sameChunk(Location from, Location to) {
      return from.getWorld() == to.getWorld() && (from.getBlockX() >> 4) == (to.getBlockX() >> 4) && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4);
   }

   private static List<String> prefixMatches(List<String> options, String partial) {
      String lower = partial.toLowerCase(Locale.ROOT);
      return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
   }

   private static final class Claim {
      private final UUID owner;
      private final String ownerName;
      private final Map<UUID, String> trusted = new HashMap<>();
      private String title = "";
      private String subtitle = "";

      private Claim(UUID owner, String ownerName) {
         this.owner = owner;
         this.ownerName = ownerName;
      }
   }

   private record ChunkKey(UUID worldId, int x, int z) {
   }
}

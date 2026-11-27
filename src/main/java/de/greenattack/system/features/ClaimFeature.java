package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import de.greenattack.system.economy.EconomyService;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;

/** Free chunk claims and per-claim trust controls for SMP worlds. */
public final class ClaimFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String USE_PERMISSION = "claim.use";
   private static final String ADMIN_PERMISSION = "claim.admin";
   private static final String LEGACY_ADMIN_PERMISSION = "system.claim.admin";
   private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
   private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
   private final Map<ChunkKey, Claim> claims = new HashMap<>();
   private final Map<ChunkKey, Integer> connectedClaimCounts = new HashMap<>();
   private final Map<UUID, ChunkKey> lastPlayerChunks = new HashMap<>();
   private final File claimsFile;

   public ClaimFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "claims");
      this.claimsFile = this.featureFile("claims.yml");
      this.loadClaims();
      this.rebuildConnectedClaimCounts();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Claim-Feature ist deaktiviert.");
         return true;
      }
      if (!this.hasUsePermission(sender)) {
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
         case "info" -> {
            if (this.requirePermission(player, "claim.info")) this.showInfo(player);
         }
         case "trust" -> this.handleTrust(player, args);
         case "settings" -> this.handleSettings(player, args);
         case "admin" -> this.handleAdmin(player, args);
         case "help" -> {
            if (this.requirePermission(player, "claim.help")) this.showHelp(player);
         }
         default -> {
            if (this.requirePermission(player, "claim.help")) this.showHelp(player);
         }
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
      int maxClaims = this.claimLimit(player);
      boolean unlimited = maxClaims == Integer.MAX_VALUE;
      if (!unlimited && this.countClaims(player.getUniqueId()) >= maxClaims) {
         String limitMessage = this.config.getString("messages.claim-limit", "&cDu hast dein Claim-Limit erreicht.");
         if (limitMessage.contains("%limit%")) {
            limitMessage = "&cDu hast dein Claim-Limit erreicht.";
         }
         this.send(player, limitMessage);
         return;
      }

      double cost = this.claimCost();
      EconomyService economy = this.plugin.getEconomyService();
      if (cost > 0D) {
         if (economy == null) {
            this.sendPath(player, "messages.economy-unavailable", "&cDas Wirtschaftssystem ist nicht verfügbar.");
            return;
         }
         if (!economy.has(player, cost) || !economy.withdraw(player, cost)) {
            this.send(player, this.config.getString("messages.not-enough-money", "&cEin Claim kostet &e%cost%&c.")
               .replace("%cost%", economy.format(cost)));
            return;
         }
      }

      Claim claim = new Claim(player.getUniqueId(), player.getName());
      this.claims.put(key, claim);
      if (!this.saveClaims()) {
         this.claims.remove(key);
         if (cost > 0D && economy != null && !economy.deposit(player, cost)) {
            this.plugin.getLogger().severe("Rückerstattung von " + economy.format(cost) + " an " + player.getName() + " nach fehlgeschlagenem Claim-Speichern fehlgeschlagen.");
         }
         this.sendPath(player, "messages.save-failed", "&cDer Claim konnte nicht gespeichert werden.");
         return;
      }
      this.rebuildConnectedClaimCounts();
      this.send(player, this.config.getString("messages.claimed", "&aChunk gesichert. &7Claims: &f%count%&7/&f%limit%")
         .replace("%count%", Integer.toString(this.countClaims(player.getUniqueId())))
         .replace("%limit%", unlimited ? "∞" : Integer.toString(maxClaims))
         .replace("%cost%", cost > 0D && economy != null ? economy.format(cost) : ""));
   }

   private void unclaimCurrentChunk(Player player) {
      ChunkKey key = key(player.getLocation().getChunk());
      Claim claim = this.claims.get(key);
      if (claim == null) {
         this.sendPath(player, "messages.not-claimed", "&cDieser Chunk ist nicht gesichert.");
         return;
      }
      boolean owner = claim.owner.equals(player.getUniqueId());
      boolean allowed = owner
         ? this.hasPermission(player, "claim.unclaim")
         : this.hasPermission(player, "claim.admin.unclaim", LEGACY_ADMIN_PERMISSION);
      if (!allowed) {
         this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return;
      }
      this.claims.remove(key);
      if (!this.saveClaims()) {
         this.claims.put(key, claim);
         this.sendPath(player, "messages.save-failed", "&cDer Claim konnte nicht gespeichert werden.");
         return;
      }
      this.rebuildConnectedClaimCounts();
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
      String action = args[1].toLowerCase(Locale.ROOT);
      if (action.equals("list")) {
         if (!this.canRunClaimAction(player, claim, "claim.trust.list", "claim.admin.trust")) {
            return;
         }
         this.send(player, "&aVertraute Spieler: &f" + (claim.trusted.isEmpty() ? "keine" : String.join(", ", claim.trusted.values())));
         return;
      }
      if (args.length != 3 || !(action.equals("add") || action.equals("remove"))) {
         this.sendPath(player, "messages.trust-usage", "&7Verwendung: &f/claim trust <add|remove|list> [Spieler]");
         return;
      }
      if (!this.canRunClaimAction(player, claim, "claim.trust." + action, "claim.admin.trust")) {
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
      if (args.length < 3 || !args[1].equalsIgnoreCase("title")) {
         this.sendPath(player, "messages.settings-usage", "&7Verwendung: &f/claim settings title <Text|reset>");
         return;
      }
      ChunkKey key = key(player.getLocation().getChunk());
      Claim claim = this.claims.get(key);
      if (claim == null) {
         this.sendPath(player, "messages.not-claimed", "&cDieser Chunk ist nicht gesichert.");
         return;
      }
      if (!this.canRunClaimAction(player, claim, "claim.settings.title", "claim.admin.settings.title")) {
         return;
      }
      String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
      if (text.length() > 80) {
         this.sendPath(player, "messages.setting-too-long", "&cDer Titel darf höchstens 80 Zeichen lang sein.");
         return;
      }
      String previous = claim.title;
      String value = text.equalsIgnoreCase("reset") ? "" : text;
      claim.title = value;
      if (!this.saveClaims()) {
         claim.title = previous;
         this.sendPath(player, "messages.save-failed", "&cDer Claim konnte nicht gespeichert werden.");
         return;
      }
      this.sendPath(player, "messages.setting-saved", "&aDer Titel wurde gespeichert.");
   }

   private void handleAdmin(Player player, String[] args) {
      if (!this.hasPermission(player, "claim.admin.unclaim", LEGACY_ADMIN_PERMISSION)) {
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
            this.rebuildConnectedClaimCounts();
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
      this.sendPath(player, "messages.help", "&a/claim &7Chunk sichern | &a/claim unclaim &7freigeben | &a/claim trust add <Spieler> &7vertrauen | &a/claim trust remove <Spieler> &7entfernen | &a/claim trust list | &a/claim settings title <Text|reset> &7Titel setzen | &a/claim info");
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.isEnabled() || !this.hasUsePermission(sender)) {
         return List.of();
      }
      if (args.length == 1) {
         List<String> choices = new ArrayList<>(List.of("claim"));
         if (this.hasPermission(sender, "claim.unclaim") || this.hasPermission(sender, "claim.admin.unclaim", LEGACY_ADMIN_PERMISSION)) choices.add("unclaim");
         if (this.hasPermission(sender, "claim.trust.add") || this.hasPermission(sender, "claim.trust.remove") || this.hasPermission(sender, "claim.trust.list") || this.hasPermission(sender, "claim.admin.trust", LEGACY_ADMIN_PERMISSION)) choices.add("trust");
         if (this.hasPermission(sender, "claim.settings.title") || this.hasPermission(sender, "claim.admin.settings.title", LEGACY_ADMIN_PERMISSION)) choices.add("settings");
         if (this.hasPermission(sender, "claim.info")) choices.add("info");
         if (this.hasPermission(sender, "claim.help")) choices.add("help");
         if (this.hasPermission(sender, "claim.admin.unclaim", LEGACY_ADMIN_PERMISSION)) {
            choices.add("admin");
         }
         return prefixMatches(choices, args[0]);
      }
      if (args.length == 2 && args[0].equalsIgnoreCase("trust")) {
         List<String> choices = new ArrayList<>();
         boolean trustAdmin = this.hasPermission(sender, "claim.admin.trust", LEGACY_ADMIN_PERMISSION);
         if (this.hasPermission(sender, "claim.trust.add") || trustAdmin) choices.add("add");
         if (this.hasPermission(sender, "claim.trust.remove") || trustAdmin) choices.add("remove");
         if (this.hasPermission(sender, "claim.trust.list") || trustAdmin) choices.add("list");
         return prefixMatches(choices, args[1]);
      }
      if (args.length == 3 && args[0].equalsIgnoreCase("trust") && ((args[1].equalsIgnoreCase("add") && (this.hasPermission(sender, "claim.trust.add") || this.hasPermission(sender, "claim.admin.trust", LEGACY_ADMIN_PERMISSION))) || (args[1].equalsIgnoreCase("remove") && (this.hasPermission(sender, "claim.trust.remove") || this.hasPermission(sender, "claim.admin.trust", LEGACY_ADMIN_PERMISSION))))) {
         return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).sorted(String.CASE_INSENSITIVE_ORDER).toList();
      }
      if (args.length == 2 && args[0].equalsIgnoreCase("settings")) {
         List<String> choices = new ArrayList<>();
         if (this.hasPermission(sender, "claim.settings.title") || this.hasPermission(sender, "claim.admin.settings.title", LEGACY_ADMIN_PERMISSION)) choices.add("title");
         return prefixMatches(choices, args[1]);
      }
      if (args.length == 3 && args[0].equalsIgnoreCase("settings") && args[1].equalsIgnoreCase("title") && (this.hasPermission(sender, "claim.settings.title") || this.hasPermission(sender, "claim.admin.settings.title", LEGACY_ADMIN_PERMISSION))) {
         return prefixMatches(List.of("reset"), args[2]);
      }
      if (args.length == 2 && args[0].equalsIgnoreCase("admin") && this.hasPermission(sender, "claim.admin.unclaim", LEGACY_ADMIN_PERMISSION)) {
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
      this.updateClaimEntry(event.getPlayer(), event.getTo());
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         Player player = event.getPlayer();
         if (player.isOnline()) {
            this.updateClaimEntry(player, player.getLocation());
         }
      });
   }

   @EventHandler
   public void onWorldChange(PlayerChangedWorldEvent event) {
      this.updateClaimEntry(event.getPlayer(), event.getPlayer().getLocation());
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
      return player.getUniqueId().equals(claim.owner) || claim.trusted.containsKey(player.getUniqueId())
         || this.hasPermission(player, "claim.admin.bypass", LEGACY_ADMIN_PERMISSION);
   }

   private void updateClaimEntry(Player player, Location location) {
      if (!this.isEnabled()) {
         return;
      }
      ChunkKey currentKey = key(location);
      ChunkKey previousKey = this.lastPlayerChunks.put(player.getUniqueId(), currentKey);
      if (currentKey.equals(previousKey)) {
         return;
      }
      Claim claim = this.claims.get(currentKey);
      if (claim == null) {
         if (previousKey != null && this.claims.containsKey(previousKey)) {
            String freeZoneSubtitle = this.config.getString("defaults.free-zone-subtitle", "&2- Free Zone -");
            player.sendTitle("", color(freeZoneSubtitle), Math.max(0, this.config.getInt("title.fade-in", 5)),
               Math.max(0, this.config.getInt("title.stay", 45)), Math.max(0, this.config.getInt("title.fade-out", 10)));
         }
         return;
      }
      String title = claim.title.isBlank() ? this.config.getString("defaults.title", "&2Claim von %owner%") : claim.title;
      String subtitle = this.config.getString("defaults.subtitle", "&7Claim geschützt");
      title = title.replace("%owner%", claim.ownerName);
      subtitle = subtitle.replace("%owner%", claim.ownerName).replace("%title%", claim.title);
      player.sendTitle(color(title), color(subtitle), Math.max(0, this.config.getInt("title.fade-in", 5)),
         Math.max(0, this.config.getInt("title.stay", 45)), Math.max(0, this.config.getInt("title.fade-out", 10)));
      this.sendClaimActionBar(player, currentKey, claim);
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

   private void sendClaimActionBar(Player player, ChunkKey key, Claim claim) {
      if (!this.config.getBoolean("actionbar.enabled", true)) {
         return;
      }
      int regionSize = this.connectedClaimCounts.getOrDefault(key, 1);
      String extra = regionSize > 1
         ? this.config.getString("actionbar.multiple-claims-format", " &8(&f%count%&7 verbundene Claims&8)").replace("%count%", Integer.toString(regionSize))
         : "";
      String pluginPrefix = this.plugin.applyPlaceholders(player, this.plugin.getConfig().getString("messages.prefix", DEFAULT_PREFIX));
      String prefixText = stripColorCodes(pluginPrefix);
      String prefixGradient = this.config.getString(
         "actionbar.prefix-gradient", "<gradient:#41DD63:#29C9D7:#B64BEE>%prefix%</gradient>"
      ).replace("%prefix%", prefixText);
      Component prefixComponent = MINI_MESSAGE.deserialize(this.plugin.applyPlaceholders(player, prefixGradient));
      String format = this.config.getString("actionbar.format", "%prefix% | Chunk -> %spieler%%claims%");
      String message = format.replace("%spieler%", claim.ownerName)
         .replace("%owner%", claim.ownerName)
         .replace("%count%", Integer.toString(regionSize))
         .replace("%claims%", extra);
      String[] parts = message.split("%prefix%", -1);
      Component actionBar = Component.empty();
      for (int i = 0; i < parts.length; i++) {
         actionBar = actionBar.append(LEGACY.deserialize(color(this.plugin.applyPlaceholders(player, parts[i]))));
         if (i < parts.length - 1) {
            actionBar = actionBar.append(prefixComponent);
         }
      }
      player.sendActionBar(actionBar);
   }

   private int claimLimit(Player player) {
      if (this.hasPermission(player, "claim.claims.unlimited")
         || this.hasPermission(player, "claim.admin.unlimited", "system.claim.unlimited")) {
         return Integer.MAX_VALUE;
      }
      int permissionLimit = 0;
      for (org.bukkit.permissions.PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
         if (!permission.getValue()) {
            continue;
         }
         String node = permission.getPermission().toLowerCase(Locale.ROOT);
         if (node.startsWith("claim.claims.")) {
            String level = node.substring("claim.claims.".length());
            try {
               int parsed = Integer.parseInt(level);
               if (parsed > permissionLimit) {
                  permissionLimit = parsed;
               }
            } catch (NumberFormatException ignored) {
            }
         }
      }
      return permissionLimit > 0 ? permissionLimit : Math.max(1, this.config.getInt("max-claims-per-player", 10));
   }

   private double claimCost() {
      Object configured = this.config.get("cost.claim", "free");
      String value = String.valueOf(configured).trim();
      if (value.equalsIgnoreCase("free")) {
         return 0D;
      }
      try {
         double amount = Double.parseDouble(value);
         if (Double.isFinite(amount) && amount >= 0D) {
            return amount;
         }
      } catch (NumberFormatException ignored) {
      }
      this.plugin.getLogger().warning("Ungültiger Wert für features/claims/config.yml cost.claim: '" + value + "'. Verwende free.");
      return 0D;
   }

   private boolean hasUsePermission(CommandSender sender) {
      return this.hasPermission(sender, USE_PERMISSION)
         || this.hasFeaturePermission(sender, this.config.getString("permissions.use", USE_PERMISSION));
   }

   private boolean hasPermission(CommandSender sender, String permission) {
      return this.hasFeaturePermission(sender, permission);
   }

   private boolean hasPermission(CommandSender sender, String permission, String legacyPermission) {
      return this.hasPermission(sender, permission) || this.hasPermission(sender, legacyPermission);
   }

   private boolean requirePermission(CommandSender sender, String permission) {
      if (this.hasPermission(sender, permission)) {
         return true;
      }
      this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
      return false;
   }

   private boolean canRunClaimAction(Player player, Claim claim, String ownPermission, String adminPermission) {
      boolean owner = claim.owner.equals(player.getUniqueId());
      boolean admin = this.hasPermission(player, adminPermission, LEGACY_ADMIN_PERMISSION);
      if ((!owner && !admin) || (!this.hasPermission(player, ownPermission) && !admin)) {
         if (!owner && !admin) {
            this.sendNotOwner(player, claim);
         } else {
            this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         }
         return false;
      }
      return true;
   }

   private void sendNotOwner(Player player, Claim claim) {
      this.send(player, this.config.getString("messages.not-owner", "&cNur &e%owner% &ckann diesen Claim verwalten.")
         .replace("%owner%", claim.ownerName));
   }

   private void rebuildConnectedClaimCounts() {
      this.connectedClaimCounts.clear();
      Set<ChunkKey> remaining = new HashSet<>(this.claims.keySet());
      while (!remaining.isEmpty()) {
         ChunkKey start = remaining.iterator().next();
         Claim ownerClaim = this.claims.get(start);
         ArrayDeque<ChunkKey> queue = new ArrayDeque<>();
         List<ChunkKey> component = new ArrayList<>();
         remaining.remove(start);
         queue.add(start);
         while (!queue.isEmpty()) {
            ChunkKey current = queue.removeFirst();
            component.add(current);
            for (ChunkKey neighbor : List.of(
               new ChunkKey(current.worldId, current.x + 1, current.z),
               new ChunkKey(current.worldId, current.x - 1, current.z),
               new ChunkKey(current.worldId, current.x, current.z + 1),
               new ChunkKey(current.worldId, current.x, current.z - 1)
            )) {
               Claim neighborClaim = this.claims.get(neighbor);
               if (neighborClaim != null && neighborClaim.owner.equals(ownerClaim.owner) && remaining.remove(neighbor)) {
                  queue.addLast(neighbor);
               }
            }
         }
         for (ChunkKey key : component) {
            this.connectedClaimCounts.put(key, component.size());
         }
      }
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

   private static String stripColorCodes(String value) {
      return color(value)
         .replaceAll("(?i)(?:&|§)#[0-9a-f]{6}", "")
         .replaceAll("(?i)(?:&|§)x(?:&|§)[0-9a-f](?:&|§)[0-9a-f](?:&|§)[0-9a-f](?:&|§)[0-9a-f](?:&|§)[0-9a-f](?:&|§)[0-9a-f]", "")
         .replaceAll("(?i)§[0-9a-fk-or]", "")
         .replaceAll("<[^>]*>", "");
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
      private Claim(UUID owner, String ownerName) {
         this.owner = owner;
         this.ownerName = ownerName;
      }
   }

   private record ChunkKey(UUID worldId, int x, int z) {
   }
}

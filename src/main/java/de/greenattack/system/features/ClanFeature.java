package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import de.greenattack.system.economy.EconomyService;
import de.greenattack.system.features.ClanModel.AddResult;
import de.greenattack.system.features.ClanModel.ClanData;
import de.greenattack.system.features.ClanModel.CreateResult;
import de.greenattack.system.features.ClanModel.Defaults;
import de.greenattack.system.features.ClanModel.HomeData;
import de.greenattack.system.features.ClanModel.HomeResult;
import de.greenattack.system.features.ClanModel.JoinMode;
import de.greenattack.system.features.ClanModel.MemberData;
import de.greenattack.system.features.ClanModel.RoleData;
import de.greenattack.system.features.ClanModel.RoleResult;
import de.greenattack.system.features.ClanModel.Snapshot;
import de.greenattack.system.features.ClanStorage.DbSettings;
import de.greenattack.system.features.ClanStorage.SqlClanStorage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Clan-System mit Datenbank (MySQL/MariaDB): Rollen mit Gewichtung und Rechten, Clan-Homes (auch serverübergreifend),
 * Beitrittsmodus, Clan-Menü, Clan-Chat, Tag-Farben gegen Kristalle und LuckPerms-Tag-Suffix.
 */
public final class ClanFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   static final String ADMIN_PERMISSION = "system.clan.admin";
   static final String USE_PERMISSION = "system.clan.use";
   static final String CREATE_PERMISSION = "system.clan.create";
   static final String COLOR_PERMISSION = "system.clan.color";
   static final String DELETE_CONFIRM_BYPASS_PERMISSION = "clan.delete.confirm.bypass";
   private static final String CONNECT_CHANNEL = "greenattack:home_connect";
   private static final int CLAN_SUFFIX_PRIORITY = 0;
   private static final long DELETE_CONFIRM_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60L);

   static final Map<String, String> NAMED_COLORS = new java.util.LinkedHashMap<>();

   static {
      NAMED_COLORS.put("black", "0");
      NAMED_COLORS.put("dark_blue", "1");
      NAMED_COLORS.put("dark_green", "2");
      NAMED_COLORS.put("dark_aqua", "3");
      NAMED_COLORS.put("dark_red", "4");
      NAMED_COLORS.put("dark_purple", "5");
      NAMED_COLORS.put("gold", "6");
      NAMED_COLORS.put("gray", "7");
      NAMED_COLORS.put("dark_gray", "8");
      NAMED_COLORS.put("blue", "9");
      NAMED_COLORS.put("green", "a");
      NAMED_COLORS.put("aqua", "b");
      NAMED_COLORS.put("red", "c");
      NAMED_COLORS.put("pink", "d");
      NAMED_COLORS.put("light_purple", "d");
      NAMED_COLORS.put("yellow", "e");
      NAMED_COLORS.put("white", "f");
   }

   private final ScheduledThreadPoolExecutor worker;
   private final Object storageLock = new Object();
   private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();
   private final Set<UUID> chatToggled = ConcurrentHashMap.newKeySet();
   private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
   private final Map<UUID, PendingDeleteConfirmation> pendingDeleteConfirmations = new ConcurrentHashMap<>();
   private final Map<UUID, CompletableFuture<Void>> suffixUpdates = new ConcurrentHashMap<>();
   private final Map<UUID, Long> suffixRevisions = new ConcurrentHashMap<>();
   private ClanStorage storage;
   private String storageSignature = "";
   private volatile DbSettings dbSettings;
   private volatile Defaults defaults = new Defaults(1000, 1, new LinkedHashSet<>(List.of("members_view", "roles_view", "home_use")));
   private volatile Snapshot snapshot = Snapshot.empty(this.defaults);
   private volatile String bracketColor = "§7";
   private volatile boolean chatListenerActive;
   private volatile boolean snapshotLoaded;
   private int refreshFailures;
   private Object placeholderExpansion;
   private ClanMenus menus;
   private boolean luckPermsWarningLogged;

   static final class Invite {
      final String clanKey;
      final String clanName;
      final String inviterName;
      final long expiresAt;

      Invite(String clanKey, String clanName, String inviterName, long expiresAt) {
         this.clanKey = clanKey;
         this.clanName = clanName;
         this.inviterName = inviterName;
         this.expiresAt = expiresAt;
      }
   }

   static final class Prompt {
      final String type;
      final String context;
      final long createdAt = System.currentTimeMillis();

      Prompt(String type, String context) {
         this.type = type;
         this.context = context;
      }
   }

   private record PendingDeleteConfirmation(String clanKey, long expiresAt) {
   }

   static final class Res {
      final String status;
      final String a;
      final String b;

      Res(String status, String a, String b) {
         this.status = status;
         this.a = a;
         this.b = b;
      }
   }

   private interface StorageTask<T> {
      T run(ClanStorage storage) throws Exception;
   }

   public ClanFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "clans");
      this.worker = new ScheduledThreadPoolExecutor(1, runnable -> {
         Thread thread = new Thread(runnable, "GreenAttack-Clans");
         thread.setDaemon(true);
         return thread;
      });
      this.worker.setKeepAliveTime(30L, TimeUnit.SECONDS);
      this.worker.allowCoreThreadTimeOut(true);

      try {
         boolean configChanged = ConfigDefaults.merge(plugin, this.config, "features/clans/config.yml");
         configChanged |= this.removeLegacyVerificationSettings();
         if (configChanged) {
            this.save();
            plugin.getLogger().info("Clan-System: fehlende Einträge wurden in features/clans/config.yml ergänzt.");
         }
         for (String name : new String[] {"clan", "clanadmin", "cc"}) {
            PluginCommand command = plugin.getCommand(name);
            if (command == null) {
               plugin.getLogger().warning("Befehl /" + name + " fehlt in der plugin.yml, Clan-System ist unvollständig.");
            } else {
               command.setExecutor(this);
               command.setTabCompleter(this);
            }
         }

         this.applyConfig();
         int interval = Math.max(5, this.config.getInt("cache-refresh-seconds", 30));
         this.worker.scheduleWithFixedDelay(this::refreshTask, 3L, interval, TimeUnit.SECONDS);
         this.registerListeners();
         this.registerConnectChannel();
         this.registerPlaceholderExpansion();
      } catch (Throwable throwable) {
         plugin.getLogger().log(Level.SEVERE, "Clan-System konnte nicht vollständig gestartet werden", throwable);
      }
   }

   // ------------------------------------------------------------------ Start / Konfiguration

   private void applyConfig() {
      this.dbSettings = DbSettings.fromConfig(this.config.getBoolean("database.enabled", false),
         this.config.getString("database.type", "mysql"),
         this.config.getString("database.jdbc-url", ""),
         this.config.getString("database.host", "localhost"),
         this.config.getInt("database.port", 3306),
         this.config.getString("database.name", ""),
         this.config.getString("database.user", ""),
         this.config.getString("database.password", ""),
         this.config.getString("database.table-prefix", "gc_"));
      this.bracketColor = color(this.config.getString("bracket-color", "&7"));

      int ownerWeight = Math.max(10, this.config.getInt("owner-weight", 1000));
      int memberWeight = Math.max(0, Math.min(ownerWeight - 2, this.config.getInt("member-weight", 1)));
      Set<String> memberPermissions = new LinkedHashSet<>();
      for (String entry : this.config.getStringList("default-member-permissions")) {
         String key = entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
         if (ClanModel.PERMISSIONS.contains(key)) {
            memberPermissions.add(key);
         }
      }
      this.defaults = new Defaults(ownerWeight, memberWeight, memberPermissions);
   }

   private boolean removeLegacyVerificationSettings() {
      boolean changed = false;
      for (String path : new String[] {
         "unverify-on-tag-change", "messages.tag-unverified", "messages.unverified-done", "messages.unverified-notify",
         "messages.usage-unverify", "messages.usage-verify", "messages.verified-done", "messages.verified-no",
         "messages.verified-notify", "messages.verified-yes"
      }) {
         if (this.config.contains(path)) {
            this.config.set(path, null);
            changed = true;
         }
      }

      if (this.config.getString("messages.created", "").toLowerCase(Locale.ROOT).contains("verifiz")) {
         this.config.set("messages.created", "&aDer Clan &e%clan% &awurde gegründet. Dein Tag ist jetzt sichtbar. Öffne das Clan-Menü mit &f/clan&a.");
         changed = true;
      }
      if (this.config.getString("messages.created-notify", "").toLowerCase(Locale.ROOT).contains("verifiz")) {
         this.config.set("messages.created-notify", "&e%player% &7hat den Clan &e%clan% &7[%tag%] gegründet.");
         changed = true;
      }
      if (this.config.getString("messages.usage-admin", "").toLowerCase(Locale.ROOT).contains("verify")) {
         this.config.set("messages.usage-admin", "&7Verwendung: &f/clanadmin <disband <Clan> | kick <Spieler> | settag <Clan> <Tag> | setcolor <Clan> <Farbe>>");
         changed = true;
      }
      if (this.config.getString("messages.info-status", "").contains("%status%")) {
         this.config.set("messages.info-status", "&7Gegründet: &f%created% &8| &7Beitritt: &f%mode%");
         changed = true;
      }
      return changed;
   }

   private ClanStorage currentStorage() {
      DbSettings db = this.dbSettings;
      if (db == null) {
         return null;
      }
      Defaults current = this.defaults;
      String signature = db.signature() + "|" + current.ownerWeight + "|" + current.memberWeight + "|" + ClanModel.joinPermissions(current.memberPermissions);
      synchronized (this.storageLock) {
         if (this.storage == null || !signature.equals(this.storageSignature)) {
            this.storage = new SqlClanStorage(db, current);
            this.storageSignature = signature;
            this.plugin.getLogger().info("Clan-System nutzt die Datenbank " + db.describe() + ".");
         }
         return this.storage;
      }
   }

   private void refreshTask() {
      ClanStorage current = this.currentStorage();
      if (current == null) {
         return;
      }
      try {
         this.publishSnapshot(current.loadSnapshot());
         this.refreshFailures = 0;
      } catch (Exception exception) {
         this.refreshFailures++;
         if (this.refreshFailures == 1 || this.refreshFailures % 20 == 0) {
            this.plugin.getLogger().log(Level.WARNING, "Clan-System: Daten konnten nicht aus der Datenbank geladen werden", exception);
         }
      }
   }

   private void publishSnapshot(Snapshot updated) {
      this.snapshot = updated;
      this.snapshotLoaded = true;
      if (this.plugin.isEnabled()) {
         Bukkit.getScheduler().runTask(this.plugin, () -> this.syncLuckPermsSuffixes(updated));
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerJoin(PlayerJoinEvent event) {
      if (this.snapshotLoaded) {
         this.reconcileLuckPermsSuffix(event.getPlayer().getUniqueId(), this.snapshot);
      }
   }

   private void syncLuckPermsSuffixes(Snapshot current) {
      Set<UUID> candidates = new LinkedHashSet<>();
      for (Player player : Bukkit.getOnlinePlayers()) {
         candidates.add(player.getUniqueId());
      }
      org.bukkit.configuration.ConfigurationSection applied = this.config.getConfigurationSection("luckperms-suffix.applied");
      if (applied != null) {
         for (String rawId : applied.getKeys(false)) {
            try {
               candidates.add(UUID.fromString(rawId));
            } catch (IllegalArgumentException ignored) {
               this.plugin.getLogger().warning("Ungültige UUID in luckperms-suffix.applied: " + rawId);
            }
         }
      }
      for (UUID id : candidates) {
         this.reconcileLuckPermsSuffix(id, current);
      }
   }

   private void reconcileLuckPermsSuffix(UUID playerId, Snapshot current) {
      if (!this.plugin.getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
         if (this.config.getBoolean("luckperms-suffix.enabled", true) && !this.luckPermsWarningLogged) {
            this.plugin.getLogger().warning("Clan-Suffixe sind aktiviert, aber LuckPerms ist nicht verfügbar.");
            this.luckPermsWarningLogged = true;
         }
         return;
      }

      String previous = this.config.getString("luckperms-suffix.applied." + playerId);
      String desired = null;
      MemberData member = current.members.get(playerId);
      ClanData clan = member == null ? null : current.clans.get(member.clanKey);
      if (this.isEnabled() && this.config.getBoolean("luckperms-suffix.enabled", true) && clan != null) {
         String format = this.config.getString("luckperms-suffix.format", " [%tag%]");
         String coloredTag = legacyColor(clan.color) + clan.tag.toUpperCase(Locale.ROOT) + "§r";
         String suffix = color(format.replace("%tag%", coloredTag));
         if (!suffix.isEmpty()) {
            desired = suffix;
         }
      }
      if (Objects.equals(previous, desired)) {
         return;
      }

      String path = "luckperms-suffix.applied." + playerId;
      this.config.set(path, desired);
      if (!this.save()) {
         this.config.set(path, previous);
         this.plugin.getLogger().warning("Clan-Suffix für " + playerId + " wurde nicht geändert, weil die Clan-Konfiguration nicht gespeichert werden konnte.");
         return;
      }
      this.queueLuckPermsSuffixUpdate(playerId, previous, desired);
   }

   private void queueLuckPermsSuffixUpdate(UUID playerId, String previous, String desired) {
      long revision = this.suffixRevisions.merge(playerId, 1L, Long::sum);
      CompletableFuture<Void> earlier = this.suffixUpdates.get(playerId);
      CompletableFuture<Void> next = (earlier == null ? CompletableFuture.completedFuture(null) : earlier.handle((ignored, error) -> null))
         .thenCompose(ignored -> this.modifyLuckPermsSuffix(playerId, previous, desired));
      this.suffixUpdates.put(playerId, next);
      next.whenCompleteAsync((ignored, error) -> {
         if (!this.plugin.isEnabled()) {
            return;
         }
         Bukkit.getScheduler().runTask(this.plugin, () -> {
            this.suffixUpdates.remove(playerId, next);
            if (error != null && this.suffixRevisions.getOrDefault(playerId, 0L) == revision) {
               String path = "luckperms-suffix.applied." + playerId;
               this.config.set(path, previous);
               this.save();
               this.plugin.getLogger().log(Level.WARNING, "LuckPerms-Clan-Suffix konnte für " + playerId + " nicht aktualisiert werden.", error);
            }
         });
      });
   }

   private CompletableFuture<Void> modifyLuckPermsSuffix(UUID playerId, String previous, String desired) {
      try {
         Plugin luckPerms = this.plugin.getServer().getPluginManager().getPlugin("LuckPerms");
         if (luckPerms == null || !luckPerms.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms ist nicht aktiv."));
         }
         ClassLoader loader = luckPerms.getClass().getClassLoader();
         Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader);
         Object api = providerClass.getMethod("get").invoke(null);
         Class<?> apiClass = Class.forName("net.luckperms.api.LuckPerms", true, loader);
         Object userManager = apiClass.getMethod("getUserManager").invoke(api);
         Class<?> userManagerClass = Class.forName("net.luckperms.api.model.user.UserManager", true, loader);

         Consumer<Object> mutation = user -> {
            try {
               Class<?> holderClass = Class.forName("net.luckperms.api.model.PermissionHolder", true, loader);
               Object userData = holderClass.getMethod("data").invoke(user);
               Class<?> nodeMapClass = Class.forName("net.luckperms.api.model.data.NodeMap", true, loader);
               Class<?> nodeClass = Class.forName("net.luckperms.api.node.Node", true, loader);
               Class<?> suffixClass = Class.forName("net.luckperms.api.node.types.SuffixNode", true, loader);
               Class<?> builderClass = Class.forName("net.luckperms.api.node.NodeBuilder", true, loader);
               java.lang.reflect.Method build = builderClass.getMethod("build");
               if (previous != null && !previous.isEmpty()) {
                  Object builder = suffixClass.getMethod("builder", String.class, int.class).invoke(null, previous, CLAN_SUFFIX_PRIORITY);
                  nodeMapClass.getMethod("remove", nodeClass).invoke(userData, build.invoke(builder));
               }
               if (desired != null && !desired.isEmpty()) {
                  Object builder = suffixClass.getMethod("builder", String.class, int.class).invoke(null, desired, CLAN_SUFFIX_PRIORITY);
                  nodeMapClass.getMethod("add", nodeClass).invoke(userData, build.invoke(builder));
               }
            } catch (ReflectiveOperationException exception) {
               throw new IllegalStateException("LuckPerms-Suffix konnte nicht gesetzt werden.", exception);
            }
         };

         Object future = userManagerClass.getMethod("modifyUser", UUID.class, Consumer.class).invoke(userManager, playerId, mutation);
         if (future instanceof CompletableFuture<?> result) {
            @SuppressWarnings("unchecked")
            CompletableFuture<Void> typed = (CompletableFuture<Void>) result;
            return typed;
         }
         return CompletableFuture.failedFuture(new IllegalStateException("LuckPerms lieferte keinen asynchronen Speichervorgang."));
      } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
         return CompletableFuture.failedFuture(exception);
      }
   }

   private void registerListeners() {
      this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
      try {
         this.plugin.getServer().getPluginManager().registerEvents(new ChatListener(this), this.plugin);
         this.chatListenerActive = true;
      } catch (Throwable throwable) {
         this.chatListenerActive = false;
         this.plugin.getLogger().warning("Clan-Chat-Umschalter und Chat-Eingaben im Menü nicht verfügbar (" + throwable + "). Alle Befehle funktionieren weiterhin.");
      }
      try {
         this.menus = new ClanMenus(this);
         this.plugin.getServer().getPluginManager().registerEvents(new ClanMenus.MenuListener(), this.plugin);
      } catch (Throwable throwable) {
         this.menus = null;
         this.plugin.getLogger().warning("Das Clan-Menü ist nicht verfügbar (" + throwable + "). Alle Befehle funktionieren weiterhin.");
      }
   }

   private void registerConnectChannel() {
      try {
         this.plugin.getServer().getMessenger().registerOutgoingPluginChannel(this.plugin, CONNECT_CHANNEL);
      } catch (Throwable throwable) {
         this.plugin.getLogger().warning("Der Velocity-Kanal für Clan-Homes auf anderen Servern konnte nicht geöffnet werden: " + throwable);
      }
   }

   private void registerPlaceholderExpansion() {
      if (!this.plugin.getConfig().getBoolean("placeholderapi.enabled", true) || !this.plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
         return;
      }
      try {
         Class<?> expansionClass = Class.forName("de.greenattack.system.features.ClanPlaceholderExpansion", true, ClanFeature.class.getClassLoader());
         Object expansion = expansionClass.getConstructor(ClanFeature.class).newInstance(this);
         boolean registered = (Boolean) expansionClass.getMethod("register").invoke(expansion);
         if (registered) {
            this.placeholderExpansion = expansion;
            this.plugin.getLogger().info("Clan-Placeholder für PlaceholderAPI sind aktiv (%clan_tag%).");
         } else {
            this.plugin.getLogger().warning("Die Clan-Placeholder konnten nicht bei PlaceholderAPI registriert werden.");
         }
      } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
         this.plugin.getLogger().warning("Die Clan-Placeholder konnten nicht geladen werden: " + exception);
      }
   }

   // ------------------------------------------------------------------ Zugriff für Menü und Placeholder

   Snapshot snapshot() {
      return this.snapshot;
   }

   Defaults defaults() {
      return this.defaults;
   }

   boolean can(Player player, String permission) {
      MemberData member = this.snapshot.members.get(player.getUniqueId());
      return member != null && this.snapshot.can(member, permission);
   }

   int maxHomes() {
      return Math.max(0, this.config.getInt("max-homes", 3));
   }

   long colorCost(String stored) {
      return Math.max(0L, stored.startsWith("#") ? this.config.getLong("color-cost-hex", 250L) : this.config.getLong("color-cost-normal", 100L));
   }

   String msg(String path, String fallback, String... pairs) {
      String text = color(this.config.getString(path, fallback));
      for (int index = 0; index + 1 < pairs.length; index += 2) {
         text = text.replace("%" + pairs[index] + "%", pairs[index + 1]);
      }
      return text;
   }

   void tell(CommandSender sender, String path, String fallback, String... pairs) {
      this.send(sender, this.msg(path, fallback, pairs));
   }

   String placeholder(UUID playerId, String parameter) {
      if (parameter == null || !this.isEnabled()) {
         return "";
      }
      Snapshot current = this.snapshot;
      MemberData member = current.members.get(playerId);
      ClanData clan = member == null ? null : current.clans.get(member.clanKey);
      if (clan == null) {
         return "";
      }

      switch (parameter.toLowerCase(Locale.ROOT)) {
         case "tag":
            return this.formatTag(clan);
         case "tag_plain":
            return clan.tag.toUpperCase(Locale.ROOT);
         case "name":
            return clan.name;
         case "role":
            return current.role(clan.key, member.roleKey).name;
         case "members":
            return Integer.toString(current.membersOf(clan.key).size());
         default:
            return null;
      }
   }

   String formatTag(ClanData clan) {
      return this.bracketColor + "[" + legacyColor(clan.color) + clan.tag.toUpperCase(Locale.ROOT) + this.bracketColor + "]§r";
   }

   // ------------------------------------------------------------------ Befehle

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      this.reload();
      this.applyConfig();
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Clan-System ist derzeit deaktiviert.");
         return true;
      }
      if (this.dbSettings == null) {
         this.sendPath(sender, "messages.db-not-configured", "&cDas Clan-System braucht eine Datenbank. Bitte database.enabled in features/clans/config.yml aktivieren.");
         return true;
      }

      switch (command.getName().toLowerCase(Locale.ROOT)) {
         case "clan":
            this.handleClan(sender, args);
            break;
         case "cc":
            this.handleChat(sender, args, 0);
            break;
         case "clanadmin":
            this.handleAdmin(sender, args);
            break;
         default:
            return false;
      }
      return true;
   }

   private void handleClan(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, USE_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      if (args.length == 0) {
         if (sender instanceof Player player && this.menus != null) {
            this.menus.openMain(player);
         } else {
            this.sendHelp(sender);
         }
         return;
      }

      switch (args[0].toLowerCase(Locale.ROOT)) {
         case "help":
            this.sendHelp(sender);
            break;
         case "menu":
            if (sender instanceof Player player && this.menus != null) {
               this.menus.openMain(player);
            } else {
               this.sendHelp(sender);
            }
            break;
         case "create":
            this.handleCreate(sender, args);
            break;
         case "invite":
            this.handleInvite(sender, args);
            break;
         case "accept":
            this.handleAccept(sender);
            break;
         case "deny":
            this.handleDeny(sender);
            break;
         case "join":
            this.handleJoin(sender, args);
            break;
         case "leave":
            this.handleLeave(sender);
            break;
         case "delete":
            this.handleDelete(sender, args);
            break;
         case "info":
            this.handleInfo(sender, args);
            break;
         case "list":
            this.handleList(sender, false);
            break;
         case "top":
            this.handleList(sender, true);
            break;
         case "members":
            this.handleMembers(sender);
            break;
         case "chat":
            this.handleChat(sender, args, 1);
            break;
         case "kick": {
            Player player = this.requirePlayer(sender);
            if (player != null && this.requireArgs(sender, args, 2, "kick <Spieler>")) {
               this.kick(player, args[1], null);
            }
            break;
         }
         case "setrole": {
            Player player = this.requirePlayer(sender);
            if (player != null && this.requireArgs(sender, args, 3, "setrole <Spieler> <Rolle>")) {
               this.setRole(player, args[1], args[2], null);
            }
            break;
         }
         case "roles":
            this.handleRolesList(sender);
            break;
         case "role":
            this.handleRole(sender, args);
            break;
         case "color": {
            Player player = this.requirePlayer(sender);
            if (player != null && this.requireArgs(sender, args, 2, "color <Farbe oder #RRGGBB>")) {
               String parsed = parseColor(args[1]);
               if (parsed == null) {
                  this.tell(sender, "messages.invalid-color", "&cUnbekannte Farbe. Erlaubt sind Farbnamen wie pink, gold, aqua oder Hex wie #ff8800.");
               } else {
                  this.buyColor(player, parsed, null);
               }
            }
            break;
         }
         case "rename": {
            Player player = this.requirePlayer(sender);
            if (player != null && this.requireArgs(sender, args, 2, "rename <NeuerName>")) {
               this.renameClan(player, args[1], null);
            }
            break;
         }
         case "settag": {
            Player player = this.requirePlayer(sender);
            if (player != null && this.requireArgs(sender, args, 2, "settag <NeuerTag>")) {
               this.changeTag(player, args[1], null);
            }
            break;
         }
         case "settings":
            this.handleSettings(sender, args);
            break;
         case "sethome": {
            Player player = this.requirePlayer(sender);
            if (player != null && this.requireArgs(sender, args, 2, "sethome <Name>")) {
               this.setHome(player, args[1], null);
            }
            break;
         }
         case "delhome": {
            Player player = this.requirePlayer(sender);
            if (player != null && this.requireArgs(sender, args, 2, "delhome <Name>")) {
               this.deleteHome(player, args[1], null);
            }
            break;
         }
         case "home": {
            Player player = this.requirePlayer(sender);
            if (player != null) {
               this.teleportHome(player, args.length >= 2 ? args[1] : null);
            }
            break;
         }
         case "homes":
            this.handleHomesList(sender);
            break;
         case "transfer": {
            Player player = this.requirePlayer(sender);
            if (player != null && this.requireArgs(sender, args, 2, "transfer <Spieler>")) {
               this.transfer(player, args[1]);
            }
            break;
         }
         default:
            this.sendHelp(sender);
            break;
      }
   }

   private boolean requireArgs(CommandSender sender, String[] args, int count, String usage) {
      if (args.length < count) {
         this.tell(sender, "messages.usage-generic", "&7Verwendung: &f/clan %usage%", "usage", usage);
         return false;
      }
      return true;
   }

   private void sendHelp(CommandSender sender) {
      this.send(sender, this.msg("messages.help-header", "&aClan-Befehle:"));
      List<String> custom = this.config.getStringList("messages.help");
      if (custom != null && !custom.isEmpty()) {
         for (String line : custom) {
            this.send(sender, color(line));
         }
         return;
      }
      for (String line : new String[] {
         "&f/clan &7- Clan-Menü", "&f/clan create <Name> <Tag> &7- Clan gründen", "&f/clan invite <Spieler> &7| &f/clan accept &7| &f/clan deny",
         "&f/clan join <Clan> &7- offenem Clan beitreten", "&f/clan leave &7- Clan verlassen (nicht als Owner)",
         "&f/clan delete [confirm] &7- eigenen Clan löschen (nur Owner)", "&f/clan info [Clan] &7| &f/clan members",
         "&f/clan list &7| &f/clan top", "&f/clan chat [Nachricht] &7| &f/cc <Nachricht>", "&f/clan kick <Spieler> &7| &f/clan setrole <Spieler> <Rolle>",
         "&f/clan roles &7| &f/clan role <create|delete|weight|perm|info> ...", "&f/clan home [Name] &7| &f/clan sethome|delhome <Name> &7| &f/clan homes",
         "&f/clan color <Farbe> &7| &f/clan rename <Name> &7| &f/clan settag <Tag>", "&f/clan settings join <invite|close|open>",
         "&f/clan transfer <Spieler>"}) {
         this.send(sender, color(line));
      }
   }

   // ------------------------------------------------------------------ Hilfsmethoden für Ergebnisse

   private Player requirePlayer(CommandSender sender) {
      if (sender instanceof Player player) {
         return player;
      }
      this.tell(sender, "messages.player-only", "&cDiesen Befehl kann nur ein Spieler verwenden.");
      return null;
   }

   /** Gemeinsame Fehlermeldungen. Liefert true, wenn der Status behandelt wurde. */
   private boolean commonError(CommandSender sender, Res result) {
      switch (result.status) {
         case "NOT_IN_CLAN":
            this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
            return true;
         case "NO_RIGHTS":
            this.tell(sender, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
            return true;
         case "HIERARCHY":
            this.tell(sender, "messages.hierarchy", "&cDas geht nur mit Mitgliedern und Rollen, die unter deiner Rolle stehen.");
            return true;
         case "TARGET_NOT_FOUND":
            this.tell(sender, "messages.member-not-found", "&cIn deinem Clan gibt es keinen Spieler &e%player%&c.", "player", result.a);
            return true;
         case "SELF":
            this.tell(sender, "messages.action-self", "&cDas geht nicht mit dir selbst.");
            return true;
         case "UNKNOWN_ROLE":
            this.tell(sender, "messages.unknown-role", "&cDie Rolle &e%role% &cgibt es nicht.", "role", result.a);
            return true;
         case "UNKNOWN_CLAN":
            this.tell(sender, "messages.unknown-clan", "&cDen Clan &e%clan% &cgibt es nicht.", "clan", result.a);
            return true;
         default:
            return false;
      }
   }

   private static MemberData actorOf(Snapshot fresh, Player player) {
      return fresh.members.get(player.getUniqueId());
   }

   private void finish(Runnable after) {
      if (after != null) {
         after.run();
      }
   }

   private double chargeMoney(Player player, double cost, String failPath, String failText) {
      if (cost <= 0D) {
         return 0D;
      }
      EconomyService economy = this.plugin.getEconomyService();
      if (economy == null) {
         this.tell(player, "messages.economy-missing", "&cDas Wirtschaftssystem ist nicht verfügbar.");
         return -1D;
      }
      if (!economy.has(player, cost) || !economy.withdraw(player, cost)) {
         this.tell(player, failPath, failText, "cost", economy.format(cost));
         return -1D;
      }
      return cost;
   }

   private void refundMoney(Player player, double cost) {
      EconomyService economy = this.plugin.getEconomyService();
      if (cost > 0D && economy != null && !economy.deposit(player, cost)) {
         this.plugin.getLogger().severe("Erstattung von " + cost + " an " + player.getName() + " fehlgeschlagen. Bitte manuell prüfen.");
      }
   }

   double creationCost() {
      Object configured = this.config.get("create-cost", 250000);
      String value = String.valueOf(configured).trim();
      if (value.equalsIgnoreCase("free")) {
         return 0D;
      }

      try {
         double cost = Double.parseDouble(value);
         if (Double.isFinite(cost)) {
            return Math.max(0D, cost);
         }
      } catch (NumberFormatException ignored) {
         // Use the default below when the config contains neither "free" nor a number.
      }

      this.plugin.getLogger().warning("Ungültiger Wert für features/clans/config.yml create-cost: '" + value + "'. Verwende 250000.");
      return 250000D;
   }

   String creationCostLabel() {
      double cost = this.creationCost();
      if (cost == 0D) {
         return "Kostenlos";
      }
      EconomyService economy = this.plugin.getEconomyService();
      return economy == null ? Long.toString((long) cost) : economy.format(cost);
   }

   private String formatCost(double cost) {
      EconomyService economy = this.plugin.getEconomyService();
      if (economy != null) {
         return economy.format(cost);
      }
      return cost == (long) cost ? Long.toString((long) cost) : Double.toString(cost);
   }

   // ------------------------------------------------------------------ Erstellen

   private void handleCreate(CommandSender sender, String[] args) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      if (!this.hasFeaturePermission(sender, CREATE_PERMISSION)) {
         this.tell(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      if (args.length != 3) {
         this.tell(sender, "messages.usage-create", "&7Verwendung: &f/clan create <Name> <Tag>");
         return;
      }

      int nameMin = this.config.getInt("name.min", 3);
      int nameMax = this.config.getInt("name.max", 16);
      int tagMin = this.config.getInt("tag.min", 2);
      int tagMax = this.config.getInt("tag.max", 5);
      String name = args[1];
      String tag = args[2].toUpperCase(Locale.ROOT);
      if (!name.matches("[A-Za-z0-9_-]{" + nameMin + "," + nameMax + "}")) {
         this.tell(sender, "messages.invalid-name", "&cDer Clan-Name darf nur Buchstaben, Zahlen, _ und - enthalten (%min% bis %max% Zeichen).",
            "min", Integer.toString(nameMin), "max", Integer.toString(nameMax));
         return;
      }
      if (!tag.matches("[A-Z0-9]{" + tagMin + "," + tagMax + "}")) {
         this.tell(sender, "messages.invalid-tag", "&cDer Tag darf nur Buchstaben und Zahlen enthalten (%min% bis %max% Zeichen).",
            "min", Integer.toString(tagMin), "max", Integer.toString(tagMax));
         return;
      }
      if (this.snapshot.members.containsKey(player.getUniqueId())) {
         this.tell(sender, "messages.already-in-clan", "&cDu bist bereits in einem Clan.");
         return;
      }

      final double cost = this.creationCost();
      EconomyService economy = this.plugin.getEconomyService();
      if (cost > 0D && economy == null) {
         this.tell(sender, "messages.economy-missing", "&cDas Wirtschaftssystem ist nicht verfügbar.");
         return;
      }
      if (cost > 0D && (!economy.has(player, cost) || !economy.withdraw(player, cost))) {
         this.tell(sender, "messages.not-enough-money", "&cDie Clan-Gründung kostet &e%cost%&c. Dir fehlt Geld.", "cost", economy.format(cost));
         return;
      }

      JoinMode mode = JoinMode.parse(this.config.getString("default-join-mode", "invite"));
      final UUID id = player.getUniqueId();
      final String playerName = player.getName();
      final ClanData data = new ClanData(name.toLowerCase(Locale.ROOT), name, tag, this.defaultColor(), System.currentTimeMillis(), mode);
      final Runnable refund = () -> this.refundMoney(player, cost);
      final String shownTag = tag;

      this.async(sender, storage -> {
         CreateResult result = storage.createClan(data, id, playerName, tag.toLowerCase(Locale.ROOT));
         if (result == CreateResult.OK) {
            this.publishSnapshot(storage.loadSnapshot());
         }
         return result;
      }, result -> {
         switch (result) {
            case OK:
               this.tell(sender, "messages.created", "&aDer Clan &e%clan% &awurde gegründet. Dein Tag wird sichtbar, sobald ein Admin den Clan verifiziert hat. Öffne das Clan-Menü mit &f/clan&a.",
                  "clan", name, "cost", this.formatCost(cost));
               this.notifyAdmins(this.msg("messages.created-notify", "&e%player% &7hat den Clan &e%clan% &7[%tag%] gegründet.",
                  "player", playerName, "clan", name, "tag", shownTag));
               break;
            case ALREADY_IN_CLAN:
               refund.run();
               this.tell(sender, "messages.already-in-clan", "&cDu bist bereits in einem Clan.");
               break;
            case TAG_TAKEN:
               refund.run();
               this.tell(sender, "messages.tag-taken", "&cDen Tag &e%tag% &cgibt es bereits. Du hast nichts bezahlt.", "tag", shownTag);
               break;
            default:
               refund.run();
               this.tell(sender, "messages.name-taken", "&cDen Clan &e%clan% &cgibt es bereits. Du hast nichts bezahlt.", "clan", name);
               break;
         }
      }, refund);
   }

   private String defaultColor() {
      String parsed = parseColor(this.config.getString("default-color", "pink"));
      return parsed == null ? "&d" : parsed;
   }

   private void notifyAdmins(String message) {
      for (Player online : Bukkit.getOnlinePlayers()) {
         if (this.hasFeaturePermission(online, ADMIN_PERMISSION)) {
            this.send(online, message);
         }
      }
   }

   // ------------------------------------------------------------------ Einladungen und Beitritt

   private void handleInvite(CommandSender sender, String[] args) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      if (!this.requireArgs(sender, args, 2, "invite <Spieler>")) {
         return;
      }

      Snapshot current = this.snapshot;
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         return;
      }
      if (!current.can(me, "invite")) {
         this.tell(sender, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
         return;
      }
      ClanData clan = current.clans.get(me.clanKey);
      if (clan.joinMode == JoinMode.CLOSED) {
         this.tell(sender, "messages.clan-closed", "&cDein Clan ist geschlossen. Ändere den Beitrittsmodus mit &f/clan settings joinmode invite&c.");
         return;
      }

      Player target = Bukkit.getPlayerExact(args[1]);
      if (target == null) {
         this.tell(sender, "messages.player-offline", "&cDer Spieler &e%player% &cist nicht online.", "player", args[1]);
         return;
      }
      if (target.getUniqueId().equals(player.getUniqueId())) {
         this.tell(sender, "messages.invite-self", "&cDu kannst dich nicht selbst einladen.");
         return;
      }
      if (current.members.containsKey(target.getUniqueId())) {
         this.tell(sender, "messages.target-in-clan", "&e%player% &cist bereits in einem Clan.", "player", target.getName());
         return;
      }
      if (current.membersOf(me.clanKey).size() >= this.config.getInt("max-members", 20)) {
         this.tell(sender, "messages.clan-full", "&cDein Clan ist voll.");
         return;
      }

      long seconds = Math.max(10, this.config.getInt("invite-expire-seconds", 60));
      this.invites.put(target.getUniqueId(), new Invite(me.clanKey, clan.name, player.getName(), System.currentTimeMillis() + seconds * 1000L));
      this.tell(sender, "messages.invite-sent", "&aDu hast &e%player% &ain den Clan eingeladen.", "player", target.getName());
      this.tell(target, "messages.invite-received", "&e%inviter% &7hat dich in den Clan &e%clan% &7eingeladen. &f/clan accept &7oder &f/clan deny &7(%seconds%s)",
         "inviter", player.getName(), "clan", clan.name, "seconds", Long.toString(seconds));
   }

   private Invite takeInvite(UUID id) {
      Invite invite = this.invites.remove(id);
      return invite == null || invite.expiresAt < System.currentTimeMillis() ? null : invite;
   }

   private void handleAccept(CommandSender sender) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      final Invite invite = this.takeInvite(player.getUniqueId());
      if (invite == null) {
         this.tell(sender, "messages.no-invite", "&cDu hast keine offene Einladung.");
         return;
      }
      this.joinClan(player, invite.clanKey, invite.clanName, false);
   }

   private void handleJoin(CommandSender sender, String[] args) {
      Player player = this.requirePlayer(sender);
      if (player == null || !this.requireArgs(sender, args, 2, "join <Clan>")) {
         return;
      }
      this.joinClan(player, args[1].toLowerCase(Locale.ROOT), args[1], true);
   }

   void joinClan(Player player, String clanKey, String shownName, boolean mustBeOpen) {
      final UUID id = player.getUniqueId();
      final String playerName = player.getName();
      final int max = this.config.getInt("max-members", 20);
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         ClanData clan = fresh.clans.get(clanKey);
         if (clan == null) {
            return new Res("CLAN_GONE", shownName, "");
         }
         if (clan.joinMode == JoinMode.CLOSED) {
            return new Res("CLOSED", clan.name, "");
         }
         if (mustBeOpen && clan.joinMode != JoinMode.OPEN) {
            return new Res("NOT_OPEN", clan.name, "");
         }
         AddResult result = storage.addMember(clanKey, id, playerName, max);
         if (result == AddResult.OK) {
            this.publishSnapshot(storage.loadSnapshot());
         }
         return new Res(result.name(), clan.name, clanKey);
      }, result -> {
         switch (result.status) {
            case "OK":
               this.tell(player, "messages.joined", "&aDu bist dem Clan &e%clan% &abeigetreten.", "clan", result.a);
               this.sendToClan(result.b, this.msg("messages.joined-notify", "&e%player% &7ist dem Clan beigetreten.", "player", playerName), id);
               break;
            case "ALREADY_IN_CLAN":
               this.tell(player, "messages.already-in-clan", "&cDu bist bereits in einem Clan.");
               break;
            case "FULL":
               this.tell(player, "messages.clan-full", "&cDer Clan ist voll.");
               break;
            case "CLOSED":
               this.tell(player, "messages.clan-closed-join", "&cDer Clan &e%clan% &cist geschlossen.", "clan", result.a);
               break;
            case "NOT_OPEN":
               this.tell(player, "messages.clan-not-open", "&cDer Clan &e%clan% &cist nicht offen. Du brauchst eine Einladung.", "clan", result.a);
               break;
            default:
               this.tell(player, "messages.clan-gone", "&cDen Clan &e%clan% &cgibt es nicht (mehr).", "clan", result.a);
               break;
         }
      });
   }

   private void handleDeny(CommandSender sender) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      Invite invite = this.takeInvite(player.getUniqueId());
      if (invite == null) {
         this.tell(sender, "messages.no-invite", "&cDu hast keine offene Einladung.");
         return;
      }
      this.tell(sender, "messages.invite-denied", "&7Du hast die Einladung von &e%clan% &7abgelehnt.", "clan", invite.clanName);
      Player inviter = Bukkit.getPlayerExact(invite.inviterName);
      if (inviter != null) {
         this.tell(inviter, "messages.invite-denied-notify", "&e%player% &7hat deine Einladung abgelehnt.", "player", player.getName());
      }
   }

   // ------------------------------------------------------------------ Verlassen / Übergeben / Auflösen

   private void handleLeave(CommandSender sender) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      final UUID id = player.getUniqueId();
      final String playerName = player.getName();
      this.async(sender, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData me = fresh.members.get(id);
         if (me == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         ClanData clan = fresh.clans.get(me.clanKey);
         String clanName = clan == null ? me.clanKey : clan.name;
         if (ClanModel.OWNER.equals(me.roleKey)) {
            return new Res("OWNER_CANNOT_LEAVE", clanName, "");
         }
         storage.removeMember(id);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("LEFT", clanName, me.clanKey);
      }, result -> {
         if (this.commonError(sender, result)) {
            return;
         }
         if (result.status.equals("OWNER_CANNOT_LEAVE")) {
            this.tell(sender, "messages.owner-delete-required",
               "&cDer Clan-Leader kann nicht austreten. Übertrage die Leitung mit &f/clan transfer <Spieler> &coder lösche den Clan mit &f/clan delete&c.");
            return;
         }
         this.chatToggled.remove(id);
         this.tell(sender, "messages.left", "&aDu hast den Clan &e%clan% &averlassen.", "clan", result.a);
         this.sendToClan(result.b, this.msg("messages.left-notify", "&e%player% &7hat den Clan verlassen.", "player", playerName), id);
      });
   }

   void transfer(Player player, String targetName) {
      final UUID id = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData me = fresh.members.get(id);
         if (me == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!ClanModel.OWNER.equals(me.roleKey)) {
            return new Res("NO_RIGHTS", "", "");
         }
         MemberData target = null;
         for (MemberData member : fresh.membersOf(me.clanKey)) {
            if (member.name.equalsIgnoreCase(targetName)) {
               target = member;
            }
         }
         if (target == null) {
            return new Res("TARGET_NOT_FOUND", targetName, "");
         }
         if (target.uuid.equals(id)) {
            return new Res("SELF", "", "");
         }
         storage.transferOwnership(me.clanKey, id, target.uuid);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", target.name, me.clanKey);
      }, result -> {
         if (!this.commonError(player, result)) {
            this.tell(player, "messages.transfer-done", "&e%player% &aist jetzt der neue Owner. Du bist wieder Member.", "player", result.a);
            this.sendToClan(result.b, this.msg("messages.transfer-notify", "&e%player% &7ist jetzt der Owner des Clans.", "player", result.a), null);
         }
      });
   }

   private void handleDelete(CommandSender sender, String[] args) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      MemberData me = this.snapshot.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         return;
      }
      if (!ClanModel.OWNER.equals(me.roleKey)) {
         this.tell(sender, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
         return;
      }

      UUID playerId = player.getUniqueId();
      if (args.length == 1) {
         if (player.hasPermission(DELETE_CONFIRM_BYPASS_PERMISSION)) {
            this.pendingDeleteConfirmations.remove(playerId);
            this.disband(player);
            return;
         }
         ClanData clan = this.snapshot.clans.get(me.clanKey);
         this.pendingDeleteConfirmations.put(playerId,
            new PendingDeleteConfirmation(me.clanKey, System.currentTimeMillis() + DELETE_CONFIRM_TIMEOUT_MILLIS));
         this.tell(sender, "messages.delete-confirm",
            "&cBist du wirklich sicher, dass du den Clan &e%clan% &clöschen möchtest? Bestätige innerhalb von 60 Sekunden mit &f/clan delete confirm&c.",
            "clan", clan == null ? me.clanKey : clan.name);
         return;
      }
      if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
         PendingDeleteConfirmation pending = this.pendingDeleteConfirmations.remove(playerId);
         boolean bypass = player.hasPermission(DELETE_CONFIRM_BYPASS_PERMISSION);
         if (!bypass && (pending == null || pending.expiresAt() < System.currentTimeMillis() || !pending.clanKey().equals(me.clanKey))) {
            this.tell(sender, "messages.delete-confirm-expired", "&cEs gibt keine gültige Löschbestätigung. Starte mit &f/clan delete&c.");
            return;
         }
         this.disband(player);
         return;
      }
      this.tell(sender, "messages.usage-generic", "&7Verwendung: &f/clan %usage%", "usage", "delete [confirm]");
   }

   void disband(Player player) {
      final UUID id = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData now = fresh.members.get(id);
         if (now == null || !ClanModel.OWNER.equals(now.roleKey)) {
            return new Res("NO_RIGHTS", "", "");
         }
         ClanData clan = fresh.clans.get(now.clanKey);
         StringBuilder ids = new StringBuilder();
         for (MemberData member : fresh.membersOf(now.clanKey)) {
            ids.append(member.uuid).append(',');
         }
         storage.disband(now.clanKey);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", clan == null ? now.clanKey : clan.name, ids.toString());
      }, result -> {
         if (this.commonError(player, result)) {
            return;
         }
         String message = this.msg("messages.deleted", "&aDer Clan &e%clan% &awurde gelöscht.", "clan", result.a);
         for (String part : result.b.split(",")) {
            if (!part.isEmpty()) {
               try {
                  UUID memberId = UUID.fromString(part);
                  this.chatToggled.remove(memberId);
                  Player online = Bukkit.getPlayer(memberId);
                  if (online != null) {
                     this.send(online, message);
                  }
               } catch (IllegalArgumentException ignored) {
                  // ignorieren
               }
            }
         }
      });
   }

   // ------------------------------------------------------------------ Info / Listen

   private void handleInfo(CommandSender sender, String[] args) {
      Snapshot current = this.snapshot;
      ClanData clan;
      if (args.length >= 2) {
         clan = current.clans.get(args[1].toLowerCase(Locale.ROOT));
      } else {
         Player player = this.requirePlayer(sender);
         if (player == null) {
            return;
         }
         MemberData me = current.members.get(player.getUniqueId());
         if (me == null) {
            this.tell(sender, "messages.not-in-clan-info", "&cDu bist in keinem Clan. Mit &f/clan info <Clan> &csiehst du andere Clans.");
            return;
         }
         clan = current.clans.get(me.clanKey);
      }
      if (clan == null) {
         this.tell(sender, "messages.unknown-clan", "&cDen Clan &e%clan% &cgibt es nicht.", "clan", args.length >= 2 ? args[1] : "");
         return;
      }
      this.sendInfo(sender, current, clan);
   }

   void sendInfo(CommandSender sender, Snapshot current, ClanData clan) {
      List<MemberData> members = new ArrayList<>(current.membersOf(clan.key));
      String owner = "-";
      int online = 0;
      for (MemberData member : members) {
         if (Bukkit.getPlayer(member.uuid) != null) {
            online++;
         }
         if (ClanModel.OWNER.equals(member.roleKey)) {
            owner = member.name;
         }
      }
      String created = new SimpleDateFormat("dd.MM.yyyy").format(new Date(clan.createdAt));
      this.send(sender, this.msg("messages.info-header", "&aClan &e%clan% &7[%tag%&7]", "clan", clan.name, "tag", legacyColor(clan.color) + clan.tag.toUpperCase(Locale.ROOT)));
      this.send(sender, this.msg("messages.info-owner", "&7Owner: &f%owner%", "owner", owner));
      this.send(sender, this.msg("messages.info-status", "&7Gegründet: &f%created% &8| &7Beitritt: &f%mode%",
         "created", created, "mode", this.joinModeLabel(clan.joinMode)));
      this.send(sender, this.msg("messages.info-members", "&7Mitglieder: &f%online%&7/&f%count% &7online",
         "online", Integer.toString(online), "count", Integer.toString(members.size())));
   }

   String joinModeLabel(JoinMode mode) {
      switch (mode) {
         case CLOSED:
            return this.msg("messages.mode-closed", "geschlossen");
         case OPEN:
            return this.msg("messages.mode-open", "offen");
         default:
            return this.msg("messages.mode-invite", "nur mit Einladung");
      }
   }

   private void handleMembers(CommandSender sender) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      Snapshot current = this.snapshot;
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         return;
      }
      if (!current.can(me, "members_view")) {
         this.tell(sender, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
         return;
      }
      List<MemberData> members = new ArrayList<>(current.membersOf(me.clanKey));
      members.sort(Comparator.<MemberData>comparingInt(member -> -current.weight(member)).thenComparing(member -> member.name.toLowerCase(Locale.ROOT)));
      this.send(sender, this.msg("messages.members-header", "&aMitglieder von &e%clan%&a:", "clan", current.clans.get(me.clanKey).name));
      for (MemberData member : members) {
         this.send(sender, this.msg("messages.members-line", "&8- %state%%player% &7(%role%)",
            "state", Bukkit.getPlayer(member.uuid) != null ? "§a" : "§7", "player", member.name, "role", current.role(member.clanKey, member.roleKey).name));
      }
   }

   private void handleList(CommandSender sender, boolean top) {
      Snapshot current = this.snapshot;
      List<ClanData> clans = new ArrayList<>(current.clans.values());
      if (clans.isEmpty()) {
         this.tell(sender, "messages.list-empty", "&7Es gibt noch keine Clans.");
         return;
      }
      clans.sort(Comparator.<ClanData>comparingInt(clan -> -current.membersOf(clan.key).size()).thenComparing(clan -> clan.key));
      int limit = Math.max(1, Math.min(50, this.config.getInt(top ? "top-size" : "list-size", top ? 10 : 30)));
      this.send(sender, this.msg(top ? "messages.top-header" : "messages.list-header", top ? "&aGrößte Clans:" : "&aAlle Clans:"));
      String line = this.msg("messages.list-line", "&8#%rank% &e%clan% &8- &f%count% &7Mitglieder &8(%mode%&8)");
      for (int index = 0; index < clans.size() && index < limit; index++) {
         ClanData clan = clans.get(index);
         this.send(sender, line.replace("%rank%", Integer.toString(index + 1)).replace("%clan%", clan.name)
            .replace("%count%", Integer.toString(current.membersOf(clan.key).size())).replace("%mode%", this.joinModeLabel(clan.joinMode)));
      }
   }

   // ------------------------------------------------------------------ Clan-Chat

   private void handleChat(CommandSender sender, String[] args, int from) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      if (!this.hasFeaturePermission(sender, USE_PERMISSION)) {
         this.tell(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      MemberData me = this.snapshot.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         return;
      }

      if (args.length <= from) {
         if (!this.chatListenerActive) {
            this.tell(sender, "messages.chat-toggle-unavailable", "&cDer Umschalter ist nicht verfügbar. Nutze &f/cc <Nachricht>&c.");
            return;
         }
         if (this.chatToggled.remove(player.getUniqueId())) {
            this.tell(sender, "messages.chat-off", "&7Clan-Chat &causgeschaltet&7. Du schreibst wieder im normalen Chat.");
         } else {
            this.chatToggled.add(player.getUniqueId());
            this.tell(sender, "messages.chat-on", "&7Clan-Chat &aeingeschaltet&7. Alles, was du schreibst, sieht nur dein Clan. Wieder aus mit &f/clan chat&7.");
         }
         return;
      }
      this.deliverClanChat(player, String.join(" ", Arrays.copyOfRange(args, from, args.length)));
   }

   void deliverClanChat(Player player, String rawMessage) {
      Snapshot current = this.snapshot;
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         return;
      }
      String text = rawMessage.replace("§", "");
      String line = this.msg("messages.chat-format", "&8[&dClan&8] &7%role% &f%player%&8: &f%message%",
         "role", current.role(me.clanKey, me.roleKey).name, "player", player.getName(), "message", text);
      this.sendToClan(me.clanKey, line, null);
   }

   private void sendToClan(String clanKey, String message, UUID except) {
      for (MemberData member : this.snapshot.membersOf(clanKey)) {
         if (except != null && except.equals(member.uuid)) {
            continue;
         }
         Player online = Bukkit.getPlayer(member.uuid);
         if (online != null) {
            this.send(online, message);
         }
      }
   }

   boolean isChatToggled(UUID id) {
      return this.chatListenerActive && this.chatToggled.contains(id);
   }

   void runSync(Runnable runnable) {
      this.sync(runnable);
   }

   // ------------------------------------------------------------------ Mitglieder verwalten

   void kick(Player player, String targetName, Runnable after) {
      final UUID actorId = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData actor = fresh.members.get(actorId);
         if (actor == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(actor, "kick")) {
            return new Res("NO_RIGHTS", "", "");
         }
         MemberData target = findMember(fresh, actor.clanKey, targetName);
         if (target == null) {
            return new Res("TARGET_NOT_FOUND", targetName, "");
         }
         if (target.uuid.equals(actorId)) {
            return new Res("SELF", "", "");
         }
         if (fresh.weight(target) >= fresh.weight(actor)) {
            return new Res("HIERARCHY", "", "");
         }
         storage.removeMember(target.uuid);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", target.name, target.uuid.toString());
      }, result -> {
         if (!this.commonError(player, result)) {
            this.tell(player, "messages.kick-done", "&e%player% &awurde aus dem Clan geworfen.", "player", result.a);
            UUID targetId = UUID.fromString(result.b);
            this.chatToggled.remove(targetId);
            Player online = Bukkit.getPlayer(targetId);
            if (online != null) {
               this.tell(online, "messages.kick-target", "&cDu wurdest aus dem Clan geworfen.");
            }
         }
         this.finish(after);
      });
   }

   void setRole(Player player, String targetName, String roleName, Runnable after) {
      final UUID actorId = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData actor = fresh.members.get(actorId);
         if (actor == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(actor, "role_assign")) {
            return new Res("NO_RIGHTS", "", "");
         }
         MemberData target = findMember(fresh, actor.clanKey, targetName);
         if (target == null) {
            return new Res("TARGET_NOT_FOUND", targetName, "");
         }
         if (target.uuid.equals(actorId)) {
            return new Res("SELF", "", "");
         }
         RoleData role = fresh.roleByName(actor.clanKey, roleName);
         if (role == null) {
            return new Res("UNKNOWN_ROLE", roleName, "");
         }
         if (ClanModel.OWNER.equals(role.key)) {
            return new Res("OWNER_ROLE", "", "");
         }
         int weight = fresh.weight(actor);
         if (role.weight >= weight || fresh.weight(target) >= weight) {
            return new Res("HIERARCHY", "", "");
         }
         storage.setMemberRole(target.uuid, role.key);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", target.name, role.name + "|" + target.uuid);
      }, result -> {
         if (result.status.equals("OWNER_ROLE")) {
            this.tell(player, "messages.owner-role", "&cDie Owner-Rolle gibst du mit &f/clan transfer <Spieler> &cab.");
         } else if (!this.commonError(player, result)) {
            String[] parts = result.b.split("\\|", 2);
            this.tell(player, "messages.setrole-done", "&e%player% &ahat jetzt die Rolle &e%role%&a.", "player", result.a, "role", parts[0]);
            Player online = Bukkit.getPlayer(UUID.fromString(parts[1]));
            if (online != null) {
               this.tell(online, "messages.setrole-target", "&aDeine Rolle im Clan ist jetzt &e%role%&a.", "role", parts[0]);
            }
         }
         this.finish(after);
      });
   }

   private static MemberData findMember(Snapshot fresh, String clanKey, String name) {
      for (MemberData member : fresh.membersOf(clanKey)) {
         if (member.name.equalsIgnoreCase(name)) {
            return member;
         }
      }
      return null;
   }

   // ------------------------------------------------------------------ Rollen

   private void handleRolesList(CommandSender sender) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      Snapshot current = this.snapshot;
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         return;
      }
      if (!current.can(me, "roles_view")) {
         this.tell(sender, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
         return;
      }
      this.send(sender, this.msg("messages.roles-header", "&aRollen deines Clans:"));
      for (RoleData role : current.rolesOf(me.clanKey)) {
         this.send(sender, this.msg("messages.roles-line", "&e%role% &7(Gewicht %weight%) &8- &f%perms%",
            "role", role.name, "weight", Integer.toString(role.weight), "perms", this.permissionList(role)));
      }
   }

   String permissionList(RoleData role) {
      if (ClanModel.OWNER.equals(role.key)) {
         return this.msg("messages.all-permissions", "alle Rechte");
      }
      List<String> labels = new ArrayList<>();
      for (String permission : ClanModel.PERMISSIONS) {
         if (role.permissions.contains(permission)) {
            labels.add(ClanModel.permissionLabel(permission));
         }
      }
      return labels.isEmpty() ? this.msg("messages.no-permissions", "keine Rechte") : String.join(", ", labels);
   }

   private void handleRole(CommandSender sender, String[] args) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      if (args.length < 3) {
         this.tell(sender, "messages.usage-role", "&7Verwendung: &f/clan role <create <Name> <Gewicht> | delete <Name> | weight <Name> <Gewicht> | perm <Name> <Recht> [on|off] | info <Name>>");
         return;
      }
      String sub = args[1].toLowerCase(Locale.ROOT);
      switch (sub) {
         case "create":
         case "weight": {
            Integer weight = args.length >= 4 && args[3].matches("-?[0-9]{1,6}") ? Integer.valueOf(args[3]) : null;
            if (weight == null) {
               this.tell(sender, "messages.usage-role", "&7Verwendung: &f/clan role <create <Name> <Gewicht> | delete <Name> | weight <Name> <Gewicht> | perm <Name> <Recht> [on|off] | info <Name>>");
            } else if (sub.equals("create")) {
               this.roleCreate(player, args[2], weight, null);
            } else {
               this.roleWeight(player, args[2], weight, null);
            }
            break;
         }
         case "delete":
            this.roleDelete(player, args[2], null);
            break;
         case "perm": {
            if (args.length < 4 || !ClanModel.PERMISSIONS.contains(args[3].toLowerCase(Locale.ROOT))) {
               this.tell(sender, "messages.invalid-permission", "&cUnbekanntes Recht. Möglich: &f%list%", "list", String.join(", ", ClanModel.PERMISSIONS));
               break;
            }
            Boolean enable = args.length >= 5 ? (args[4].equalsIgnoreCase("on") ? Boolean.TRUE : args[4].equalsIgnoreCase("off") ? Boolean.FALSE : null) : null;
            this.rolePerm(player, args[2], args[3].toLowerCase(Locale.ROOT), enable, null);
            break;
         }
         case "info": {
            Snapshot current = this.snapshot;
            MemberData me = current.members.get(player.getUniqueId());
            if (me == null) {
               this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
            } else if (!current.can(me, "roles_view")) {
               this.tell(sender, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
            } else {
               RoleData role = current.roleByName(me.clanKey, args[2]);
               if (role == null) {
                  this.tell(sender, "messages.unknown-role", "&cDie Rolle &e%role% &cgibt es nicht.", "role", args[2]);
               } else {
                  this.send(sender, this.msg("messages.roles-line", "&e%role% &7(Gewicht %weight%) &8- &f%perms%",
                     "role", role.name, "weight", Integer.toString(role.weight), "perms", this.permissionList(role)));
               }
            }
            break;
         }
         default:
            this.tell(sender, "messages.usage-role", "&7Verwendung: &f/clan role <create <Name> <Gewicht> | delete <Name> | weight <Name> <Gewicht> | perm <Name> <Recht> [on|off] | info <Name>>");
            break;
      }
   }

   void roleCreate(Player player, String roleName, int weight, Runnable after) {
      if (!roleName.matches("[A-Za-z0-9_-]{2,16}")) {
         this.tell(player, "messages.invalid-role-name", "&cRollennamen dürfen nur Buchstaben, Zahlen, _ und - enthalten (2 bis 16 Zeichen).");
         this.finish(after);
         return;
      }
      final int minWeight = this.defaults.memberWeight + 1;
      final int maxRoles = Math.max(1, this.config.getInt("max-roles", 10));
      final UUID actorId = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData actor = fresh.members.get(actorId);
         if (actor == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(actor, "roles_edit")) {
            return new Res("NO_RIGHTS", "", "");
         }
         int actorWeight = fresh.weight(actor);
         if (weight < minWeight || weight >= actorWeight) {
            return new Res("BAD_WEIGHT", minWeight + "", (actorWeight - 1) + "");
         }
         RoleData role = new RoleData(roleName.toLowerCase(Locale.ROOT), roleName, weight, new LinkedHashSet<>(), false);
         RoleResult result = storage.createRole(actor.clanKey, role, maxRoles);
         if (result == RoleResult.OK) {
            this.publishSnapshot(storage.loadSnapshot());
         }
         return new Res(result.name(), roleName, Integer.toString(maxRoles));
      }, result -> {
         this.roleResult(player, result, "role-created", "&aDie Rolle &e%role% &awurde erstellt. Rechte vergibst du mit &f/clan role perm %role% <Recht> on&a.");
         this.finish(after);
      });
   }

   void roleDelete(Player player, String roleName, Runnable after) {
      final UUID actorId = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData actor = fresh.members.get(actorId);
         if (actor == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(actor, "roles_edit")) {
            return new Res("NO_RIGHTS", "", "");
         }
         RoleData role = fresh.roleByName(actor.clanKey, roleName);
         if (role == null) {
            return new Res("UNKNOWN_ROLE", roleName, "");
         }
         if (role.system) {
            return new Res("SYSTEM_ROLE", role.name, "");
         }
         if (role.weight >= fresh.weight(actor)) {
            return new Res("HIERARCHY", "", "");
         }
         storage.deleteRole(actor.clanKey, role.key);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", role.name, "");
      }, result -> {
         this.roleResult(player, result, "role-deleted", "&aDie Rolle &e%role% &awurde gelöscht. Ihre Mitglieder sind jetzt Member.");
         this.finish(after);
      });
   }

   void roleWeight(Player player, String roleName, int weight, Runnable after) {
      final int minWeight = this.defaults.memberWeight + 1;
      final UUID actorId = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData actor = fresh.members.get(actorId);
         if (actor == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(actor, "roles_edit")) {
            return new Res("NO_RIGHTS", "", "");
         }
         RoleData role = fresh.roleByName(actor.clanKey, roleName);
         if (role == null) {
            return new Res("UNKNOWN_ROLE", roleName, "");
         }
         if (role.system) {
            return new Res("SYSTEM_ROLE", role.name, "");
         }
         int actorWeight = fresh.weight(actor);
         if (role.weight >= actorWeight) {
            return new Res("HIERARCHY", "", "");
         }
         if (weight < minWeight || weight >= actorWeight) {
            return new Res("BAD_WEIGHT", minWeight + "", (actorWeight - 1) + "");
         }
         storage.updateRole(actor.clanKey, role.key, weight, role.permissions);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", role.name, Integer.toString(weight));
      }, result -> {
         this.roleResult(player, result, "role-weight-done", "&aDie Rolle &e%role% &ahat jetzt das Gewicht &e%value%&a.");
         this.finish(after);
      });
   }

   void rolePerm(Player player, String roleName, String permission, Boolean enable, Runnable after) {
      final UUID actorId = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData actor = fresh.members.get(actorId);
         if (actor == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(actor, "roles_edit")) {
            return new Res("NO_RIGHTS", "", "");
         }
         RoleData role = fresh.roleByName(actor.clanKey, roleName);
         if (role == null) {
            return new Res("UNKNOWN_ROLE", roleName, "");
         }
         if (ClanModel.OWNER.equals(role.key)) {
            return new Res("OWNER_FIXED", role.name, "");
         }
         if (!ClanModel.OWNER.equals(actor.roleKey) && role.weight >= fresh.weight(actor)) {
            return new Res("HIERARCHY", "", "");
         }
         boolean turnOn = enable == null ? !role.permissions.contains(permission) : enable;
         if (turnOn && !fresh.can(actor, permission)) {
            return new Res("PERMISSION_LACKING", ClanModel.permissionLabel(permission), "");
         }
         Set<String> updated = new LinkedHashSet<>(role.permissions);
         if (turnOn) {
            updated.add(permission);
         } else {
            updated.remove(permission);
         }
         storage.updateRole(actor.clanKey, role.key, role.weight, updated);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", role.name, ClanModel.permissionLabel(permission) + "|" + (turnOn ? "an" : "aus"));
      }, result -> {
         if (result.status.equals("OK")) {
            String[] parts = result.b.split("\\|", 2);
            this.tell(player, "messages.role-perm-done", "&aRecht &e%perm% &afür &e%role% &a: &f%state%", "perm", parts[0], "role", result.a, "state", parts[1]);
         } else {
            this.roleResult(player, result, "", "");
         }
         this.finish(after);
      });
   }

   private void roleResult(Player player, Res result, String okPath, String okText) {
      switch (result.status) {
         case "OK":
            this.tell(player, "messages." + okPath, okText, "role", result.a, "value", result.b);
            break;
         case "EXISTS":
            this.tell(player, "messages.role-exists", "&cEine Rolle mit diesem Namen gibt es schon.");
            break;
         case "LIMIT":
            this.tell(player, "messages.role-limit", "&cDein Clan kann höchstens %max% eigene Rollen haben.", "max", result.b);
            break;
         case "BAD_WEIGHT":
            this.tell(player, "messages.role-bad-weight", "&cDas Gewicht muss zwischen &e%min% &cund &e%max% &cliegen (unter deiner eigenen Rolle).", "min", result.a, "max", result.b);
            break;
         case "SYSTEM_ROLE":
            this.tell(player, "messages.role-system", "&cDie Rolle &e%role% &cist eine Standardrolle und kann so nicht geändert werden.", "role", result.a);
            break;
         case "OWNER_FIXED":
            this.tell(player, "messages.role-owner-fixed", "&cDie Owner-Rolle hat immer alle Rechte und kann nicht bearbeitet werden.");
            break;
         case "PERMISSION_LACKING":
            this.tell(player, "messages.role-perm-lacking", "&cDu kannst nur Rechte vergeben, die du selbst hast: &e%perm%", "perm", result.a);
            break;
         default:
            this.commonError(player, result);
            break;
      }
   }

   // ------------------------------------------------------------------ Farbe, Name, Tag, Einstellungen

   void buyColor(Player player, String parsed, Runnable after) {
      if (!this.hasFeaturePermission(player, COLOR_PERMISSION)) {
         this.tell(player, "messages.no-permission", "&cDazu hast du keine Rechte.");
         this.finish(after);
         return;
      }
      Snapshot current = this.snapshot;
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(player, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         this.finish(after);
         return;
      }
      if (!current.can(me, "color")) {
         this.tell(player, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
         this.finish(after);
         return;
      }
      ClanData clan = current.clans.get(me.clanKey);
      if (clan != null && clan.color.equalsIgnoreCase(parsed)) {
         this.tell(player, "messages.same-color", "&cDein Clan hat diese Farbe schon.");
         this.finish(after);
         return;
      }

      KristallFeature kristall = this.plugin.getKristallFeature();
      if (kristall == null || !kristall.isEnabled()) {
         this.tell(player, "messages.kristall-missing", "&cDas Kristall-System ist nicht verfügbar.");
         this.finish(after);
         return;
      }
      final long cost = this.colorCost(parsed);
      final UUID id = player.getUniqueId();
      try {
         if (!kristall.withdrawBalance(id, cost)) {
            this.tell(player, "messages.not-enough-kristalle", "&cEine neue Tag-Farbe kostet &b%cost% Kristalle&c. Du hast &b%balance%&c.",
               "cost", Long.toString(cost), "balance", Long.toString(kristall.getBalance(id)));
            this.finish(after);
            return;
         }
      } catch (IOException exception) {
         this.plugin.getLogger().severe("Kristallabbuchung für " + player.getName() + " fehlgeschlagen: " + exception.getMessage());
         this.tell(player, "messages.kristall-save-failed", "&cDein Kristallkonto konnte nicht gespeichert werden.");
         this.finish(after);
         return;
      }

      final Runnable refund = () -> {
         try {
            kristall.depositBalance(id, cost);
         } catch (IOException exception) {
            this.plugin.getLogger().severe("Kristallerstattung für " + id + " fehlgeschlagen (" + cost + "): " + exception.getMessage() + ". Bitte Konto manuell prüfen.");
         }
      };
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData now = fresh.members.get(id);
         if (now == null || !fresh.can(now, "color")) {
            return new Res("NO_RIGHTS", "", "");
         }
         storage.setColor(now.clanKey, parsed);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", now.clanKey, "");
      }, result -> {
         if (result.status.equals("OK")) {
            this.tell(player, "messages.color-done", "&aDie Tag-Farbe wurde geändert. &7(%cost% Kristalle)", "cost", Long.toString(cost));
         } else {
            refund.run();
            this.tell(player, "messages.no-rights", "&cDazu hast du im Clan keine Rechte. Die Kristalle wurden erstattet.");
         }
         this.finish(after);
      }, () -> {
         refund.run();
         this.finish(after);
      });
   }

   void renameClan(Player player, String newName, Runnable after) {
      int nameMin = this.config.getInt("name.min", 3);
      int nameMax = this.config.getInt("name.max", 16);
      if (!newName.matches("[A-Za-z0-9_-]{" + nameMin + "," + nameMax + "}")) {
         this.tell(player, "messages.invalid-name", "&cDer Clan-Name darf nur Buchstaben, Zahlen, _ und - enthalten (%min% bis %max% Zeichen).",
            "min", Integer.toString(nameMin), "max", Integer.toString(nameMax));
         this.finish(after);
         return;
      }
      MemberData me = this.snapshot.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(player, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         this.finish(after);
         return;
      }
      if (!this.snapshot.can(me, "rename")) {
         this.tell(player, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
         this.finish(after);
         return;
      }

      double cost = this.chargeMoney(player, Math.max(0D, this.config.getDouble("rename-cost", 0D)), "messages.not-enough-money-rename", "&cDie Umbenennung kostet &e%cost%&c. Dir fehlt Geld.");
      if (cost < 0D) {
         this.finish(after);
         return;
      }
      final double paid = cost;
      final UUID id = player.getUniqueId();
      final Runnable refund = () -> this.refundMoney(player, paid);
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData now = fresh.members.get(id);
         if (now == null || !fresh.can(now, "rename")) {
            return new Res("NO_RIGHTS", "", "");
         }
         String newKey = newName.toLowerCase(Locale.ROOT);
         if (fresh.clans.get(now.clanKey).name.equals(newName)) {
            return new Res("SAME", newName, "");
         }
         if (!storage.renameClan(now.clanKey, newKey, newName)) {
            return new Res("NAME_TAKEN", newName, "");
         }
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", newName, newKey);
      }, result -> {
         switch (result.status) {
            case "OK":
               this.tell(player, "messages.rename-done", "&aDein Clan heißt jetzt &e%clan%&a.", "clan", result.a);
               this.sendToClan(result.b, this.msg("messages.rename-notify", "&7Der Clan heißt jetzt &e%clan%&7.", "clan", result.a), id);
               break;
            case "NAME_TAKEN":
               refund.run();
               this.tell(player, "messages.name-taken-free", "&cDen Clan &e%clan% &cgibt es bereits.", "clan", result.a);
               break;
            case "SAME":
               refund.run();
               this.tell(player, "messages.same-name", "&cDein Clan heißt schon so.");
               break;
            default:
               refund.run();
               this.commonError(player, result);
               break;
         }
         this.finish(after);
      }, () -> {
         refund.run();
         this.finish(after);
      });
   }

   void changeTag(Player player, String rawTag, Runnable after) {
      int tagMin = this.config.getInt("tag.min", 2);
      int tagMax = this.config.getInt("tag.max", 5);
      final String tag = rawTag.toUpperCase(Locale.ROOT);
      if (!tag.matches("[A-Z0-9]{" + tagMin + "," + tagMax + "}")) {
         this.tell(player, "messages.invalid-tag", "&cDer Tag darf nur Buchstaben und Zahlen enthalten (%min% bis %max% Zeichen).",
            "min", Integer.toString(tagMin), "max", Integer.toString(tagMax));
         this.finish(after);
         return;
      }
      final UUID id = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData now = fresh.members.get(id);
         if (now == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(now, "tag")) {
            return new Res("NO_RIGHTS", "", "");
         }
         ClanData clan = fresh.clans.get(now.clanKey);
         if (clan.tag.equals(tag)) {
            return new Res("SAME", tag, "");
         }
         if (!storage.setTag(now.clanKey, tag, tag.toLowerCase(Locale.ROOT))) {
            return new Res("TAG_TAKEN", tag, "");
         }
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", tag, "");
      }, result -> {
         switch (result.status) {
            case "OK":
               this.tell(player, "messages.tag-done", "&aDer Tag deines Clans ist jetzt &e%tag%&a. Das war kostenlos.", "tag", result.a);
               break;
            case "TAG_TAKEN":
               this.tell(player, "messages.tag-taken-free", "&cDen Tag &e%tag% &cgibt es bereits.", "tag", result.a);
               break;
            case "SAME":
               this.tell(player, "messages.same-tag", "&cDein Clan hat diesen Tag schon.");
               break;
            default:
               this.commonError(player, result);
               break;
         }
         this.finish(after);
      });
   }

   private void handleSettings(CommandSender sender, String[] args) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      Snapshot current = this.snapshot;
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         return;
      }
      if (args.length < 3) {
         this.tell(sender, "messages.settings-joinmode", "&7Beitritt: &f%mode% &8| &7Ändern: &f/clan settings join <invite|close|open>",
            "mode", this.joinModeLabel(current.clans.get(me.clanKey).joinMode));
         return;
      }
      JoinMode mode = args[1].equalsIgnoreCase("join") || args[1].equalsIgnoreCase("joinmode") ? JoinMode.parse(args[2]) : null;
      if (mode == null) {
         this.tell(sender, "messages.settings-joinmode", "&7Beitritt: &f%mode% &8| &7Ändern: &f/clan settings join <invite|close|open>",
            "mode", this.joinModeLabel(current.clans.get(me.clanKey).joinMode));
         return;
      }
      this.setJoinMode(player, mode, null);
   }

   void setJoinMode(Player player, JoinMode mode, Runnable after) {
      final UUID id = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData now = fresh.members.get(id);
         if (now == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(now, "settings")) {
            return new Res("NO_RIGHTS", "", "");
         }
         storage.setJoinMode(now.clanKey, mode);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", mode.name(), "");
      }, result -> {
         if (!this.commonError(player, result)) {
            this.tell(player, "messages.joinmode-done", "&aBeitritt: &e%mode%", "mode", this.joinModeLabel(JoinMode.valueOf(result.a)));
         }
         this.finish(after);
      });
   }

   // ------------------------------------------------------------------ Clan-Homes

   private boolean validHomeName(String name) {
      return name != null && name.matches("[A-Za-z0-9_-]{1,16}");
   }

   void setHome(Player player, String name, Runnable after) {
      if (!this.validHomeName(name)) {
         this.tell(player, "messages.invalid-home-name", "&cHome-Namen dürfen nur Buchstaben, Zahlen, _ und - enthalten (1 bis 16 Zeichen).");
         this.finish(after);
         return;
      }
      Location location = player.getLocation();
      if (location == null || location.getWorld() == null) {
         this.finish(after);
         return;
      }
      final String serverId = this.config.getString("server-id", "").trim();
      final int maxHomes = this.maxHomes();
      final UUID id = player.getUniqueId();
      final String playerName = player.getName();
      final String worldName = location.getWorld().getName();
      final double x = location.getX();
      final double y = location.getY();
      final double z = location.getZ();
      final float yaw = location.getYaw();
      final float pitch = location.getPitch();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData now = fresh.members.get(id);
         if (now == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(now, "home_set")) {
            return new Res("NO_RIGHTS", "", "");
         }
         HomeResult result = storage.setHome(new HomeData(now.clanKey, name, serverId, worldName, x, y, z, yaw, pitch, playerName), maxHomes);
         if (result == HomeResult.OK) {
            this.publishSnapshot(storage.loadSnapshot());
         }
         return new Res(result.name(), name, Integer.toString(maxHomes));
      }, result -> {
         if (result.status.equals("OK")) {
            this.tell(player, "messages.home-set", "&aClan-Home &e%home% &agesetzt.", "home", result.a);
         } else if (result.status.equals("LIMIT")) {
            this.tell(player, "messages.home-limit", "&cDein Clan kann höchstens &e%max% &cHomes haben. Lösche erst eins mit &f/clan delhome <Name>&c.", "max", result.b);
         } else {
            this.commonError(player, result);
         }
         this.finish(after);
      });
   }

   void deleteHome(Player player, String name, Runnable after) {
      final UUID id = player.getUniqueId();
      this.async(player, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         MemberData now = fresh.members.get(id);
         if (now == null) {
            return new Res("NOT_IN_CLAN", "", "");
         }
         if (!fresh.can(now, "home_set")) {
            return new Res("NO_RIGHTS", "", "");
         }
         HomeData home = fresh.home(now.clanKey, name);
         if (home == null) {
            return new Res("NO_HOME", name, "");
         }
         storage.deleteHome(now.clanKey, home.name);
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", home.name, "");
      }, result -> {
         if (result.status.equals("OK")) {
            this.tell(player, "messages.home-deleted", "&aClan-Home &e%home% &awurde gelöscht.", "home", result.a);
         } else if (result.status.equals("NO_HOME")) {
            this.tell(player, "messages.home-unknown", "&cEs gibt kein Clan-Home &e%home%&c.", "home", result.a);
         } else {
            this.commonError(player, result);
         }
         this.finish(after);
      });
   }

   void teleportHome(Player player, String name) {
      Snapshot current = this.snapshot;
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(player, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         return;
      }
      if (!current.can(me, "home_use")) {
         this.tell(player, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
         return;
      }
      List<HomeData> homes = current.homesOf(me.clanKey);
      HomeData home = null;
      if (name == null) {
         if (homes.size() == 1) {
            home = homes.get(0);
         } else if (homes.isEmpty()) {
            this.tell(player, "messages.homes-empty", "&7Dein Clan hat noch kein Home. Setze eins mit &f/clan sethome <Name>&7.");
            return;
         } else {
            this.tell(player, "messages.home-choose", "&7Dein Clan hat mehrere Homes. Nutze &f/clan home <Name>&7. Siehe &f/clan homes&7.");
            return;
         }
      } else {
         home = current.home(me.clanKey, name);
      }
      if (home == null) {
         this.tell(player, "messages.home-unknown", "&cEs gibt kein Clan-Home &e%home%&c.", "home", name == null ? "" : name);
         return;
      }

      String localServer = this.config.getString("server-id", "").trim();
      if (!home.serverId.isEmpty() && !localServer.isEmpty() && !home.serverId.equalsIgnoreCase(localServer)) {
         if (this.requestConnect(player, home.serverId, "/clan home " + home.name)) {
            this.tell(player, "messages.home-remote", "&eVerbinde dich mit &6%server% &efür das Clan-Home &6%home%&e.", "server", home.serverId, "home", home.name);
         } else {
            this.tell(player, "messages.home-remote-failed", "&cDer Serverwechsel konnte nicht gestartet werden.");
         }
         return;
      }
      World world = Bukkit.getWorld(home.world);
      if (world == null) {
         this.tell(player, "messages.home-world-missing", "&cDie Welt dieses Homes ist gerade nicht verfügbar.");
         return;
      }
      player.teleport(new Location(world, home.x, home.y, home.z, home.yaw, home.pitch));
      this.tell(player, "messages.home-teleported", "&aDu wurdest zum Clan-Home &e%home% &ateleportiert.", "home", home.name);
   }

   private boolean requestConnect(Player player, String serverName, String command) {
      if (serverName == null || !serverName.matches("[A-Za-z0-9_-]{1,64}") || command.length() > 256) {
         return false;
      }
      try {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream();
         try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("connect");
            output.writeUTF(player.getUniqueId().toString());
            output.writeUTF(player.getUniqueId().toString());
            output.writeUTF(serverName);
            output.writeUTF(command);
         }
         player.sendPluginMessage(this.plugin, CONNECT_CHANNEL, bytes.toByteArray());
         return true;
      } catch (IOException | RuntimeException exception) {
         this.plugin.getLogger().warning("Velocity-Transfernachricht für Clan-Home fehlgeschlagen: " + exception.getMessage());
         return false;
      }
   }

   private void handleHomesList(CommandSender sender) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return;
      }
      Snapshot current = this.snapshot;
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.tell(sender, "messages.not-in-clan", "&cDu bist in keinem Clan.");
         return;
      }
      if (!current.can(me, "home_use")) {
         this.tell(sender, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
         return;
      }
      List<HomeData> homes = current.homesOf(me.clanKey);
      if (homes.isEmpty()) {
         this.tell(sender, "messages.homes-empty", "&7Dein Clan hat noch kein Home. Setze eins mit &f/clan sethome <Name>&7.");
         return;
      }
      this.send(sender, this.msg("messages.homes-header", "&aClan-Homes (%count%/%max%):", "count", Integer.toString(homes.size()), "max", Integer.toString(this.maxHomes())));
      for (HomeData home : homes) {
         this.send(sender, this.msg("messages.homes-line", "&8- &e%home% &7(%server%)", "home", home.name, "server", home.serverId.isEmpty() ? "lokal" : home.serverId));
      }
   }

   // ------------------------------------------------------------------ Admin

   private void handleAdmin(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         this.tell(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      final String usage = "&7Verwendung: &f/clanadmin <disband <Clan> | kick <Spieler> | settag <Clan> <Tag> | setcolor <Clan> <Farbe>>";
      if (args.length == 0) {
         this.tell(sender, "messages.usage-admin", usage);
         return;
      }

      final String sub = args[0].toLowerCase(Locale.ROOT);
      switch (sub) {
         case "disband":
         case "kick":
         case "settag":
         case "setcolor":
            break;
         default:
            this.tell(sender, "messages.usage-admin", usage);
            return;
      }

      final boolean needsTwo = sub.equals("settag") || sub.equals("setcolor");
      if (args.length != (needsTwo ? 3 : 2)) {
         this.tell(sender, "messages.usage-admin", usage);
         return;
      }

      final String target = args[1];
      final String value = needsTwo ? args[2] : "";
      final String tagValue = value.toUpperCase(Locale.ROOT);
      String colorValue = null;
      if (sub.equals("settag")) {
         int tagMin = this.config.getInt("tag.min", 2);
         int tagMax = this.config.getInt("tag.max", 5);
         if (!tagValue.matches("[A-Z0-9]{" + tagMin + "," + tagMax + "}")) {
            this.tell(sender, "messages.invalid-tag", "&cDer Tag darf nur Buchstaben und Zahlen enthalten (%min% bis %max% Zeichen).",
               "min", Integer.toString(tagMin), "max", Integer.toString(tagMax));
            return;
         }
      } else if (sub.equals("setcolor")) {
         colorValue = parseColor(value);
         if (colorValue == null) {
            this.tell(sender, "messages.invalid-color", "&cUnbekannte Farbe. Erlaubt sind Farbnamen wie pink, gold, aqua oder Hex wie #ff8800.");
            return;
         }
      }
      final String parsedColor = colorValue;

      this.async(sender, storage -> {
         Snapshot fresh = storage.loadSnapshot();
         if (sub.equals("kick")) {
            MemberData member = null;
            for (MemberData candidate : fresh.members.values()) {
               if (candidate.name.equalsIgnoreCase(target)) {
                  member = candidate;
               }
            }
            if (member == null) {
               return new Res("NO_MEMBER", target, "");
            }
            ClanData memberClan = fresh.clans.get(member.clanKey);
            boolean owner = ClanModel.OWNER.equals(member.roleKey);
            if (owner && fresh.membersOf(member.clanKey).size() > 1) {
               return new Res("IS_OWNER", member.name, "");
            }
            if (owner) {
               storage.disband(member.clanKey);
            } else {
               storage.removeMember(member.uuid);
            }
            this.publishSnapshot(storage.loadSnapshot());
            return new Res("OK", member.name, memberClan == null ? member.clanKey : memberClan.name);
         }

         ClanData clan = fresh.clans.get(target.toLowerCase(Locale.ROOT));
         if (clan == null) {
            return new Res("UNKNOWN_CLAN", target, "");
         }
         switch (sub) {
            case "disband":
               storage.disband(clan.key);
               break;
            case "settag":
               if (!storage.setTag(clan.key, tagValue, tagValue.toLowerCase(Locale.ROOT))) {
                  return new Res("TAG_TAKEN", tagValue, "");
               }
               break;
            default:
               storage.setColor(clan.key, parsedColor);
               break;
         }
         this.publishSnapshot(storage.loadSnapshot());
         return new Res("OK", clan.name, sub.equals("settag") ? tagValue : value);
      }, result -> {
         switch (result.status) {
            case "NO_MEMBER":
               this.tell(sender, "messages.admin-no-member", "&cDer Spieler &e%player% &cist in keinem Clan.", "player", result.a);
               break;
            case "IS_OWNER":
               this.tell(sender, "messages.admin-is-owner", "&e%player% &cist Owner eines Clans mit weiteren Mitgliedern. Löse den Clan mit &f/clanadmin disband &cauf.", "player", result.a);
               break;
            case "TAG_TAKEN":
               this.tell(sender, "messages.tag-taken-free", "&cDen Tag &e%tag% &cgibt es bereits.", "tag", result.a);
               break;
            case "UNKNOWN_CLAN":
               this.tell(sender, "messages.unknown-clan", "&cDen Clan &e%clan% &cgibt es nicht.", "clan", result.a);
               break;
            default: {
               String fallback = sub.equals("disband") ? "&aDer Clan &e%clan% &awurde aufgelöst."
                  : sub.equals("kick") ? "&e%player% &awurde aus dem Clan &e%value% &aentfernt."
                  : sub.equals("settag") ? "&aDer Tag von &e%clan% &awurde geändert: &f%value%" : "&aDie Tag-Farbe von &e%clan% &awurde geändert.";
               this.tell(sender, "messages.admin-" + sub, fallback, "clan", result.a, "player", result.a, "value", result.b);
               break;
            }
         }
      });
   }

   String colorize(String text) {
      return color(text);
   }

   boolean hasInvite(UUID id) {
      Invite invite = this.invites.get(id);
      return invite != null && invite.expiresAt >= System.currentTimeMillis();
   }

   org.bukkit.configuration.file.YamlConfiguration config() {
      return this.config;
   }

   // ------------------------------------------------------------------ Eingaben im Chat (für das Menü)

   void startPrompt(Player player, String type, String context, String path, String fallback) {
      if (!this.chatListenerActive) {
         this.tell(player, "messages.prompt-unavailable", "&cDie Chat-Eingabe ist nicht verfügbar. Nutze den passenden Befehl, siehe &f/clan help&c.");
         return;
      }
      this.prompts.put(player.getUniqueId(), new Prompt(type, context));
      player.closeInventory();
      this.tell(player, path, fallback);
      this.tell(player, "messages.prompt-cancel", "&7Zum Abbrechen schreibe &fabbrechen&7.");
   }

   Prompt takePrompt(UUID id) {
      Prompt prompt = this.prompts.remove(id);
      return prompt == null || System.currentTimeMillis() - prompt.createdAt > 120000L ? null : prompt;
   }

   boolean hasPrompt(UUID id) {
      return this.chatListenerActive && this.prompts.containsKey(id);
   }

   void handlePrompt(Player player, String input, Prompt prompt) {
      final String text = input.trim();
      if (text.equalsIgnoreCase("abbrechen") || text.equalsIgnoreCase("cancel")) {
         this.tell(player, "messages.prompt-cancelled", "&7Abgebrochen.");
         return;
      }
      final ClanMenus ui = this.menus;
      switch (prompt.type) {
         case "rename":
            this.renameClan(player, text, () -> this.reopen(ui, player, "main"));
            break;
         case "tag":
            this.changeTag(player, text, () -> this.reopen(ui, player, "main"));
            break;
         case "role-create": {
            String[] parts = text.split("\\s+");
            if (parts.length != 2 || !parts[1].matches("-?[0-9]{1,6}")) {
               this.tell(player, "messages.prompt-role-format", "&cFormat: &fName Gewicht &c(zum Beispiel &fsupporter 50&c). Starte im Menü neu.");
            } else {
               this.roleCreate(player, parts[0], Integer.parseInt(parts[1]), () -> this.reopen(ui, player, "roles"));
            }
            break;
         }
         case "home-set":
            this.setHome(player, text, () -> this.reopen(ui, player, "homes"));
            break;
         case "hex": {
            String parsed = parseColor(text);
            if (parsed == null) {
               this.tell(player, "messages.invalid-color", "&cUnbekannte Farbe. Erlaubt sind Farbnamen wie pink, gold, aqua oder Hex wie #ff8800.");
            } else {
               this.buyColor(player, parsed, () -> this.reopen(ui, player, "colors"));
            }
            break;
         }
         default:
            break;
      }
   }

   void reopen(ClanMenus ui, Player player, String menu) {
      if (ui == null) {
         return;
      }
      switch (menu) {
         case "roles":
            ui.openRoles(player);
            break;
         case "homes":
            ui.openHomes(player);
            break;
         case "colors":
            ui.openColors(player);
            break;
         case "members":
            ui.openMembers(player);
            break;
         case "settings":
            ui.openSettings(player);
            break;
         default:
            ui.openMain(player);
            break;
      }
   }

   // ------------------------------------------------------------------ Tab-Completion

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      String name = command.getName().toLowerCase(Locale.ROOT);
      Snapshot current = this.snapshot;
      List<String> options = new ArrayList<>();
      String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);

      if (name.equals("clanadmin")) {
         if (!this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
            return List.of();
         }
         if (args.length == 1) {
            options.addAll(Arrays.asList("disband", "kick", "settag", "setcolor"));
         } else if (args.length == 2 && args[0].equalsIgnoreCase("kick")) {
            for (MemberData member : current.members.values()) {
               options.add(member.name);
            }
         } else if (args.length == 2) {
            for (ClanData clan : current.clans.values()) {
               options.add(clan.name);
            }
         } else if (args.length == 3 && args[0].equalsIgnoreCase("setcolor")) {
            options.addAll(NAMED_COLORS.keySet());
         }
      } else if (name.equals("clan") && sender instanceof Player player) {
         MemberData me = current.members.get(player.getUniqueId());
         if (args.length == 1) {
            options.addAll(Arrays.asList("menu", "create", "invite", "accept", "deny", "join", "leave", "delete", "info", "list", "top", "members", "chat", "kick", "setrole", "roles",
               "role", "color", "rename", "settag", "settings", "home", "sethome", "delhome", "homes", "transfer", "help"));
         } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
               case "invite":
                  for (Player online : Bukkit.getOnlinePlayers()) {
                     if (!current.members.containsKey(online.getUniqueId())) {
                        options.add(online.getName());
                     }
                  }
                  break;
               case "kick":
               case "setrole":
               case "transfer":
                  if (me != null) {
                     for (MemberData member : current.membersOf(me.clanKey)) {
                        if (!member.uuid.equals(player.getUniqueId())) {
                           options.add(member.name);
                        }
                     }
                  }
                  break;
               case "info":
               case "join":
                  for (ClanData clan : current.clans.values()) {
                     options.add(clan.name);
                  }
                  break;
               case "color":
                  options.addAll(NAMED_COLORS.keySet());
                  break;
               case "role":
                  options.addAll(Arrays.asList("create", "delete", "weight", "perm", "info"));
                  break;
               case "settings":
                  options.add("join");
                  break;
               case "delete":
                  if (me != null && ClanModel.OWNER.equals(me.roleKey)) {
                     options.add("confirm");
                  }
                  break;
               case "home":
               case "delhome":
                  if (me != null) {
                     for (HomeData home : current.homesOf(me.clanKey)) {
                        options.add(home.name);
                     }
                  }
                  break;
               default:
                  break;
            }
         } else if (args.length == 3) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("setrole") && me != null) {
               for (RoleData role : current.rolesOf(me.clanKey)) {
                  if (!ClanModel.OWNER.equals(role.key)) {
                     options.add(role.name);
                  }
               }
            } else if (first.equals("role") && me != null && !args[1].equalsIgnoreCase("create")) {
               for (RoleData role : current.rolesOf(me.clanKey)) {
                  options.add(role.name);
               }
            } else if (first.equals("settings") && (args[1].equalsIgnoreCase("join") || args[1].equalsIgnoreCase("joinmode"))) {
               options.addAll(Arrays.asList("invite", "close", "open"));
            }
         } else if (args.length == 4 && args[0].equalsIgnoreCase("role") && args[1].equalsIgnoreCase("perm")) {
            options.addAll(ClanModel.PERMISSIONS);
         } else if (args.length == 5 && args[0].equalsIgnoreCase("role") && args[1].equalsIgnoreCase("perm")) {
            options.addAll(Arrays.asList("on", "off"));
         }
      }

      List<String> result = new ArrayList<>();
      for (String option : options) {
         if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            result.add(option);
         }
      }
      return result;
   }

   // ------------------------------------------------------------------ Hilfsmethoden

   /** Liefert die gespeicherte Form (&d oder #rrggbb) oder null, wenn die Farbe unbekannt ist. */
   static String parseColor(String input) {
      if (input == null) {
         return null;
      }
      String value = input.trim().toLowerCase(Locale.ROOT);
      if (value.matches("#[0-9a-f]{6}") || value.matches("&[0-9a-f]")) {
         return value;
      }
      String code = NAMED_COLORS.get(value);
      return code == null ? null : "&" + code;
   }

   static String legacyColor(String stored) {
      if (stored != null && stored.matches("#[0-9a-fA-F]{6}")) {
         StringBuilder builder = new StringBuilder("§x");
         for (char digit : stored.substring(1).toCharArray()) {
            builder.append('§').append(Character.toLowerCase(digit));
         }
         return builder.toString();
      }
      if (stored != null && stored.matches("&[0-9a-fA-F]")) {
         return "§" + Character.toLowerCase(stored.charAt(1));
      }
      return "§d";
   }

   private <T> void async(CommandSender sender, StorageTask<T> task, Consumer<T> done) {
      this.async(sender, task, done, null);
   }

   private <T> void async(CommandSender sender, StorageTask<T> task, Consumer<T> done, Runnable onError) {
      final ClanStorage current = this.currentStorage();
      this.worker.execute(() -> {
         final T result;
         try {
            result = task.run(current);
         } catch (Exception exception) {
            this.plugin.getLogger().log(Level.WARNING, "Clan-System: Datenbankzugriff fehlgeschlagen", exception);
            this.sync(() -> {
               if (onError != null) {
                  onError.run();
               }
               this.tell(sender, "messages.db-error", "&cDie Clan-Datenbank ist gerade nicht erreichbar. Es wurde nichts abgebucht. Bitte melde das einem Admin.");
            });
            return;
         }
         this.sync(() -> done.accept(result));
      });
   }

   private void sync(Runnable runnable) {
      try {
         Bukkit.getScheduler().runTask(this.plugin, runnable);
      } catch (RuntimeException exception) {
         // Plugin wird gerade beendet
      }
   }

   // ------------------------------------------------------------------ Chat-Listener

   public static final class ChatListener implements Listener {
      private final ClanFeature feature;

      ChatListener(ClanFeature feature) {
         this.feature = feature;
      }

      @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
      public void onChat(AsyncPlayerChatEvent event) {
         final Player player = event.getPlayer();
         final Prompt prompt = this.feature.hasPrompt(player.getUniqueId()) ? this.feature.takePrompt(player.getUniqueId()) : null;
         if (prompt != null) {
            event.setCancelled(true);
            final String text = event.getMessage();
            this.feature.runSync(() -> this.feature.handlePrompt(player, text, prompt));
            return;
         }
         if (!this.feature.isChatToggled(player.getUniqueId())) {
            return;
         }
         event.setCancelled(true);
         final String message = event.getMessage();
         this.feature.runSync(() -> this.feature.deliverClanChat(player, message));
      }
   }
}

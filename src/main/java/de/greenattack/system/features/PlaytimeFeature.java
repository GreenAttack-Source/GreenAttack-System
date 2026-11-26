package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.scheduler.BukkitTask;

public final class PlaytimeFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String DEFAULT_HEADER = "&2&l-------- &e&lPlaytime/Spielzeit &2&l---------";
   private static final DateTimeFormatter JOINED_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy 'um' HH:mm");
   private final Object stateLock = new Object();
   private final Map<UUID, PlaytimeFeature.Session> activeSessions = new HashMap<>();
   private final Map<UUID, PlaytimeFeature.PendingTime> pendingTimes = new HashMap<>();
   private String tableName;
   private String dailyTableName;
   private String jdbcUrl;
   private String databaseUser;
   private String databasePassword;
   private ZoneId timeZone = ZoneId.of("Europe/Berlin");
   private long afkTimeoutMillis = 300000L;
   private boolean storageReady;
   private BukkitTask collectTask;
   private BukkitTask flushTask;

   public PlaytimeFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "playtime");
      this.ensureDefaults();
      this.loadTimeSettings();
      this.initializeStorage();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      if (this.storageReady) {
         long now = System.currentTimeMillis();

         for (Player player : Bukkit.getOnlinePlayers()) {
            this.startSession(player, now);
         }

         this.scheduleTasks();
         Bukkit.getScheduler().runTaskAsynchronously(plugin, this::flushPending);
      }
   }

   public void reloadFeature() {
      super.reload();
      this.ensureDefaults();
      this.loadTimeSettings();
      this.initializeStorage();
      this.scheduleTasks();
   }

   public void shutdownFeature() {
      if (this.collectTask != null) {
         this.collectTask.cancel();
      }

      if (this.flushTask != null) {
         this.flushTask.cancel();
      }

      this.collectTask = null;
      this.flushTask = null;
      if (this.storageReady) {
         this.collectActiveTime(System.currentTimeMillis());
      }

      this.flushPending();
   }

   private void ensureDefaults() {
      boolean changed = false;
      Map<String, Object> defaults = Map.ofEntries(
         Map.entry("enabled", true),
         Map.entry("save-interval-seconds", 60),
         Map.entry("afk-timeout-seconds", 300),
         Map.entry("timezone", "Europe/Berlin"),
         Map.entry("database.enabled", false),
         Map.entry("database.jdbc-url", ""),
         Map.entry("database.host", ""),
         Map.entry("database.port", 3306),
         Map.entry("database.name", ""),
         Map.entry("database.user", ""),
         Map.entry("database.password", ""),
         Map.entry("database.table-name", "greenattack_playtime"),
         Map.entry("database.daily-table-name", "greenattack_playtime_daily"),
         Map.entry("database.connection-timeout-ms", 3000),
         Map.entry("permissions.use", "system.playtime.use"),
         Map.entry("permissions.admin", "system.playtime.admin"),
         Map.entry("messages.header", "&2&l-------- &e&lPlaytime/Spielzeit &2&l---------"),
         Map.entry("messages.disabled", "&cDas Spielzeit-System ist deaktiviert."),
         Map.entry("messages.unavailable", "&cDie Spielzeit ist gerade nicht verfügbar. Bitte versuche es später erneut."),
         Map.entry("messages.player-only", "&cDiesen Befehl können nur Spieler verwenden."),
         Map.entry("messages.no-permission", "&cDafür hast du keine Berechtigung."),
         Map.entry("messages.usage", "&7Verwendung: &f/playtime &7oder &f/playtime <Spieler>"),
         Map.entry("messages.not-found", "&cZu &e%player% &cgibt es noch keine Spielzeitdaten."),
         Map.entry("messages.self", this.defaultStatsLines()),
         Map.entry("messages.other", this.defaultOtherStatsLines())
      );

      for (Entry<String, Object> entry : defaults.entrySet()) {
         if (!this.config.contains(entry.getKey())) {
            this.config.set(entry.getKey(), entry.getValue());
            changed = true;
         }
      }

      if (this.isLegacyDefaultLine("messages.self", "&aDeine Spielzeit: &e%playtime%")) {
         this.config.set("messages.self", this.defaultStatsLines());
         changed = true;
      }

      if (this.isLegacyDefaultLine("messages.other", "&aSpielzeit von &e%player%&a: &e%playtime%")) {
         this.config.set("messages.other", this.defaultOtherStatsLines());
         changed = true;
      }

      changed |= this.normalizeStatsLines("messages.self");
      changed |= this.normalizeStatsLines("messages.other");
      if (this.config.contains("messages.database-error")) {
         this.config.set("messages.database-error", null);
         changed = true;
      }

      if (changed) {
         this.save();
      }
   }

   private boolean isLegacyDefaultLine(String path, String oldDefault) {
      return this.config.isString(path) && this.config.getString(path, "").equals(oldDefault);
   }

   private boolean normalizeStatsLines(String path) {
      if (!this.config.isList(path)) {
         return false;
      }

      List<String> lines = this.config.getStringList(path);
      List<String> normalized = new ArrayList<>();
      boolean changed = false;

      for (String line : lines) {
         if (line != null && !line.isBlank()) {
            String value = line.replaceFirst("^\\s*&8\\s*»\\s*", "").replaceFirst("^\\s*»\\s*", "");
            if ("&7Spielzeit:".equals(value)) {
               value = "&7Kategorie:";
            }

            if (!value.equals(line)) {
               changed = true;
            }

            normalized.add(value);
         } else {
            changed = true;
         }
      }

      if (changed) {
         this.config.set(path, normalized);
      }

      return changed;
   }

   private List<String> defaultStatsLines() {
      return List.of(
         "&7Mitglied seit: &b%member-since%",
         "&7Onlinezeit: &b%online-time%",
         "&7Spielzeit: &b%playtime%",
         "&7Kategorie:",
         "&7Heute: &b%today%",
         "&7Letzte 7 Tage: &b%last-7-days%",
         "&7Letzte 30 Tage: &b%last-30-days%"
      );
   }

   private List<String> defaultOtherStatsLines() {
      List<String> lines = new ArrayList<>();
      lines.add("&7Spielzeit von &e%player%&7:");
      lines.addAll(this.defaultStatsLines());
      return lines;
   }

   private void loadTimeSettings() {
      try {
         this.timeZone = ZoneId.of(this.config.getString("timezone", "Europe/Berlin"));
      } catch (Exception ignored) {
         this.timeZone = ZoneId.of("Europe/Berlin");
         this.plugin.getLogger().warning("Ungültige Playtime-Zeitzone; verwende Europe/Berlin.");
      }

      long timeoutSeconds = Math.max(60L, Math.min(86400L, this.config.getLong("afk-timeout-seconds", 300L)));
      this.afkTimeoutMillis = timeoutSeconds * 1000L;
   }

   private void initializeStorage() {
      this.storageReady = false;
      if (!this.isEnabled()) {
         this.plugin.getLogger().info("Das Playtime-Feature ist deaktiviert.");
      } else if (!this.config.getBoolean("database.enabled", false)) {
         this.plugin.getLogger().warning("Playtime-Speicherung ist deaktiviert. Aktiviere database.enabled in features/playtime/config.yml.");
      } else {
         this.tableName = this.config.getString("database.table-name", "greenattack_playtime").trim();
         this.dailyTableName = this.config.getString("database.daily-table-name", "greenattack_playtime_daily").trim();
         if (this.tableName.matches("[A-Za-z0-9_]{1,48}")
            && this.dailyTableName.matches("[A-Za-z0-9_]{1,48}")
            && !this.tableName.equalsIgnoreCase(this.dailyTableName)) {
            this.databaseUser = this.config.getString("database.user", "").trim();
            this.databasePassword = this.config.getString("database.password", "");
            if (this.databaseUser.isBlank()) {
               this.plugin.getLogger().severe("database.user fehlt in features/playtime/config.yml; Spielzeit bleibt aus.");
            } else {
               this.jdbcUrl = this.config.getString("database.jdbc-url", "").trim();
               if (this.jdbcUrl.isEmpty()) {
                  String host = this.config.getString("database.host", "").trim();
                  String database = this.config.getString("database.name", "").trim();
                  if (host.isEmpty() || database.isEmpty()) {
                     this.plugin.getLogger().severe("database.host und database.name fehlen in features/playtime/config.yml; Spielzeit bleibt aus.");
                     return;
                  }

                  int port = Math.max(1, Math.min(65535, this.config.getInt("database.port", 3306)));
                  int timeout = Math.max(1000, this.config.getInt("database.connection-timeout-ms", 3000));
                  this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?connectTimeout=" + timeout + "&socketTimeout=5000&autoReconnect=true";
               }

               try {
                  Class.forName(this.jdbcUrl.startsWith("jdbc:mariadb:") ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver");
               } catch (ClassNotFoundException exception) {
                  this.plugin.getLogger().severe("Der konfigurierte MySQL/MariaDB-Treiber wurde nicht geladen: " + exception.getMessage());
                  return;
               }

               try (Connection connection = this.openDatabaseConnection()) {
                  boolean oldSchema = this.tableExists(connection, this.tableName);

                  try (Statement statement = connection.createStatement()) {
                     statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS `"
                           + this.tableName
                           + "` (`player_uuid` CHAR(36) NOT NULL, `player_name` VARCHAR(32) NOT NULL, `first_joined_at_ms` BIGINT NOT NULL DEFAULT 0, `online_seconds` BIGINT NOT NULL DEFAULT 0, `playtime_seconds` BIGINT NOT NULL DEFAULT 0, `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (`player_uuid`), INDEX `idx_greenattack_playtime_name` (`player_name`)) ENGINE=InnoDB"
                     );
                  }

                  this.migrateOldSchema(connection, oldSchema);

                  try (Statement statement = connection.createStatement()) {
                     statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS `"
                           + this.dailyTableName
                           + "` (`player_uuid` CHAR(36) NOT NULL, `play_date` DATE NOT NULL, `online_seconds` BIGINT NOT NULL DEFAULT 0, `playtime_seconds` BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (`player_uuid`,`play_date`), INDEX `idx_greenattack_playtime_date` (`play_date`)) ENGINE=InnoDB"
                     );
                  }

                  this.storageReady = true;
                  this.plugin.getLogger().info("Netzwerkweite Spielzeit ist bereit (Tabellen '" + this.tableName + "' und '" + this.dailyTableName + "').");
               } catch (SQLException exception) {
                  this.plugin
                     .getLogger()
                     .warning("Playtime-Speicherung konnte nicht gestartet werden. Prüfe features/playtime/config.yml: " + exception.getMessage());
               }
            }
         } else {
            this.plugin.getLogger().severe("Ungültige Tabellenangaben in features/playtime/config.yml; Spielzeit bleibt aus.");
         }
      }
   }

   private boolean tableExists(Connection connection, String name) throws SQLException {
      try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, name, new String[]{"TABLE"})) {
         return tables.next();
      }
   }

   private void migrateOldSchema(Connection connection, boolean oldSchema) throws SQLException {
      Set<String> columns = new HashSet<>();

      try (ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, this.tableName, null)) {
         while (result.next()) {
            columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
         }
      }

      try (Statement statement = connection.createStatement()) {
         if (!columns.contains("first_joined_at_ms")) {
            statement.executeUpdate("ALTER TABLE `" + this.tableName + "` ADD COLUMN `first_joined_at_ms` BIGINT NOT NULL DEFAULT 0");
         }

         if (!columns.contains("online_seconds")) {
            statement.executeUpdate("ALTER TABLE `" + this.tableName + "` ADD COLUMN `online_seconds` BIGINT NOT NULL DEFAULT 0");
            if (oldSchema && columns.contains("playtime_seconds")) {
               statement.executeUpdate("UPDATE `" + this.tableName + "` SET `online_seconds`=`playtime_seconds`, `playtime_seconds`=0");
            }
         }

         if (!columns.contains("playtime_seconds")) {
            statement.executeUpdate("ALTER TABLE `" + this.tableName + "` ADD COLUMN `playtime_seconds` BIGINT NOT NULL DEFAULT 0");
         }
      }
   }

   private Connection openDatabaseConnection() throws SQLException {
      return DriverManager.getConnection(this.jdbcUrl, this.databaseUser, this.databasePassword);
   }

   private void scheduleTasks() {
      if (this.collectTask != null) {
         this.collectTask.cancel();
      }

      if (this.flushTask != null) {
         this.flushTask.cancel();
      }

      this.collectTask = null;
      this.flushTask = null;
      if (this.storageReady && this.isEnabled()) {
         this.collectTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> this.collectActiveTime(System.currentTimeMillis()), 20L, 20L);
         long seconds = Math.max(5L, Math.min(3600L, this.config.getLong("save-interval-seconds", 60L)));
         this.flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, this::flushPending, seconds * 20L, seconds * 20L);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent event) {
      if (this.storageReady && this.isEnabled()) {
         this.startSession(event.getPlayer(), System.currentTimeMillis());
         Bukkit.getScheduler().runTaskAsynchronously(this.plugin, this::flushPending);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onQuit(PlayerQuitEvent event) {
      if (this.storageReady) {
         Player player = event.getPlayer();
         synchronized (this.stateLock) {
            PlaytimeFeature.Session session = this.activeSessions.remove(player.getUniqueId());
            if (session != null) {
               this.addPending(
                  player.getUniqueId(),
                  player.getName(),
                  session.joinedAtMillis,
                  session.consume(System.currentTimeMillis(), this.afkTimeoutMillis, this.timeZone)
               );
            }
         }

         Bukkit.getScheduler().runTaskAsynchronously(this.plugin, this::flushPending);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onMove(PlayerMoveEvent event) {
      if (event.getTo() != null) {
         Location from = event.getFrom();
         Location to = event.getTo();
         if (from.getX() != to.getX()
            || from.getY() != to.getY()
            || from.getZ() != to.getZ()
            || from.getYaw() != to.getYaw()
            || from.getPitch() != to.getPitch()) {
            this.markActive(event.getPlayer());
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onInteract(PlayerInteractEvent event) {
      this.markActive(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onChat(AsyncChatEvent event) {
      this.markActive(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onCommandInput(PlayerCommandPreprocessEvent event) {
      this.markActive(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onAnimation(PlayerAnimationEvent event) {
      this.markActive(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onItemHeld(PlayerItemHeldEvent event) {
      this.markActive(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onDrop(PlayerDropItemEvent event) {
      this.markActive(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onSwap(PlayerSwapHandItemsEvent event) {
      this.markActive(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         this.markActive(player);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onCombat(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player player) {
         this.markActive(player);
      }

      if (event.getEntity() instanceof Player player) {
         this.markActive(player);
      }
   }

   private void startSession(Player player, long now) {
      synchronized (this.stateLock) {
         UUID uuid = player.getUniqueId();
         long firstJoinedAt = player.getFirstPlayed() > 0L ? player.getFirstPlayed() : now;
         PlaytimeFeature.Session session = new PlaytimeFeature.Session(player.getName(), now, firstJoinedAt);
         this.activeSessions.putIfAbsent(uuid, session);
         this.addPending(uuid, player.getName(), session.joinedAtMillis, PlaytimeFeature.TimeDelta.empty());
      }
   }

   private void markActive(Player player) {
      synchronized (this.stateLock) {
         PlaytimeFeature.Session session = this.activeSessions.get(player.getUniqueId());
         if (session != null) {
            session.markActivity(System.currentTimeMillis());
         }
      }
   }

   private void collectActiveTime(long now) {
      if (this.storageReady) {
         synchronized (this.stateLock) {
            for (Entry<UUID, PlaytimeFeature.Session> entry : this.activeSessions.entrySet()) {
               PlaytimeFeature.Session session = entry.getValue();
               PlaytimeFeature.TimeDelta delta = session.consume(now, this.afkTimeoutMillis, this.timeZone);
               this.addPending(entry.getKey(), session.playerName, session.joinedAtMillis, delta);
            }
         }
      }
   }

   private void addPending(UUID uuid, String name, long joinedAtMillis, PlaytimeFeature.TimeDelta delta) {
      PlaytimeFeature.PendingTime pending = this.pendingTimes.computeIfAbsent(uuid, ignored -> new PlaytimeFeature.PendingTime(name, joinedAtMillis));
      pending.playerName = name;
      if (pending.firstJoinedAtMillis == 0L || joinedAtMillis < pending.firstJoinedAtMillis) {
         pending.firstJoinedAtMillis = joinedAtMillis;
      }

      pending.onlineSeconds = pending.onlineSeconds + delta.onlineSeconds;
      pending.playtimeSeconds = pending.playtimeSeconds + delta.playtimeSeconds;

      for (Entry<LocalDate, PlaytimeFeature.DailyTime> entry : delta.daily.entrySet()) {
         pending.daily.computeIfAbsent(entry.getKey(), ignored -> new PlaytimeFeature.DailyTime()).add(entry.getValue());
      }
   }

   private synchronized void flushPending() {
      if (this.storageReady) {
         List<PlaytimeFeature.PendingSnapshot> snapshots = new ArrayList<>();
         synchronized (this.stateLock) {
            for (Entry<UUID, PlaytimeFeature.PendingTime> entry : this.pendingTimes.entrySet()) {
               PlaytimeFeature.PendingTime pending = entry.getValue();
               snapshots.add(pending.snapshot(entry.getKey()));
            }
         }

         if (!snapshots.isEmpty()) {
            String mainSql = "INSERT INTO `"
               + this.tableName
               + "` (`player_uuid`,`player_name`,`first_joined_at_ms`,`online_seconds`,`playtime_seconds`) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE `player_name`=VALUES(`player_name`), `first_joined_at_ms`=IF(`first_joined_at_ms`=0,VALUES(`first_joined_at_ms`),`first_joined_at_ms`), `online_seconds`=`online_seconds`+VALUES(`online_seconds`), `playtime_seconds`=`playtime_seconds`+VALUES(`playtime_seconds`), `updated_at`=CURRENT_TIMESTAMP";
            String dailySql = "INSERT INTO `"
               + this.dailyTableName
               + "` (`player_uuid`,`play_date`,`online_seconds`,`playtime_seconds`) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE `online_seconds`=`online_seconds`+VALUES(`online_seconds`), `playtime_seconds`=`playtime_seconds`+VALUES(`playtime_seconds`)";

            try (Connection connection = this.openDatabaseConnection()) {
               boolean autoCommit = connection.getAutoCommit();
               connection.setAutoCommit(false);

               try (
                  PreparedStatement main = connection.prepareStatement(mainSql);
                  PreparedStatement daily = connection.prepareStatement(dailySql);
               ) {
                  for (PlaytimeFeature.PendingSnapshot snapshot : snapshots) {
                     main.setString(1, snapshot.uuid.toString());
                     main.setString(2, snapshot.playerName);
                     main.setLong(3, snapshot.firstJoinedAtMillis);
                     main.setLong(4, snapshot.onlineSeconds);
                     main.setLong(5, snapshot.playtimeSeconds);
                     main.addBatch();

                     for (Entry<LocalDate, PlaytimeFeature.DailyTime> day : snapshot.daily.entrySet()) {
                        daily.setString(1, snapshot.uuid.toString());
                        daily.setDate(2, Date.valueOf(day.getKey()));
                        daily.setLong(3, day.getValue().onlineSeconds);
                        daily.setLong(4, day.getValue().playtimeSeconds);
                        daily.addBatch();
                     }
                  }

                  main.executeBatch();
                  daily.executeBatch();
                  connection.commit();
               } catch (SQLException exception) {
                  try {
                     connection.rollback();
                  } catch (SQLException var31) {
                  }

                  throw exception;
               } finally {
                  try {
                     connection.setAutoCommit(autoCommit);
                  } catch (SQLException var30) {
                  }
               }

               synchronized (this.stateLock) {
                  for (PlaytimeFeature.PendingSnapshot snapshot : snapshots) {
                     PlaytimeFeature.PendingTime current = this.pendingTimes.get(snapshot.uuid);
                     if (current != null) {
                        current.onlineSeconds = Math.max(0L, current.onlineSeconds - snapshot.onlineSeconds);
                        current.playtimeSeconds = Math.max(0L, current.playtimeSeconds - snapshot.playtimeSeconds);

                        for (Entry<LocalDate, PlaytimeFeature.DailyTime> savedDay : snapshot.daily.entrySet()) {
                           PlaytimeFeature.DailyTime currentDay = current.daily.get(savedDay.getKey());
                           if (currentDay != null) {
                              currentDay.subtract(savedDay.getValue());
                              if (currentDay.isZero()) {
                                 current.daily.remove(savedDay.getKey());
                              }
                           }
                        }

                        if (current.onlineSeconds == 0L
                           && current.playtimeSeconds == 0L
                           && current.daily.isEmpty()
                           && current.playerName.equals(snapshot.playerName)) {
                           this.pendingTimes.remove(snapshot.uuid);
                        }
                     }
                  }
               }
            } catch (SQLException exception) {
               this.plugin
                  .getLogger()
                  .warning("Spielzeit konnte nicht gespeichert werden; der nächste Speicherversuch folgt automatisch: " + exception.getMessage());
            }
         }
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Spielzeit-System ist deaktiviert.");
         return true;
      }

      if (args.length > 1) {
         this.sendPath(sender, "messages.usage", "&7Verwendung: &f/playtime &7oder &f/playtime <Spieler>");
         return true;
      }

      if (args.length == 0 && !this.hasFeaturePermission(sender, this.config.getString("permissions.use", "system.playtime.use"))) {
         this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return true;
      }

      if (args.length == 1 && !this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.playtime.admin"))) {
         this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return true;
      }

      if (!this.storageReady) {
         this.sendPath(sender, "messages.unavailable", "&cDie Spielzeit ist gerade nicht verfügbar. Bitte versuche es später erneut.");
         return true;
      }

      if (args.length == 0) {
         if (sender instanceof Player player) {
            this.lookup(player.getUniqueId(), player.getName(), sender, true);
            return true;
         } else {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         }
      } else {
         this.lookup(null, args[0], sender, false);
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1 && this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.playtime.admin"))) {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         return Bukkit.getOnlinePlayers()
            .stream()
            .<String>map(Player::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .sorted()
            .toList();
      } else {
         return List.of();
      }
   }

   private void lookup(UUID uuid, String playerName, CommandSender sender, boolean self) {
      Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> this.readPlaytime(uuid, playerName, sender, self));
   }

   private synchronized void readPlaytime(UUID uuid, String playerName, CommandSender sender, boolean self) {
      if (!this.storageReady) {
         this.deliver(sender, "messages.unavailable", "&cDie Spielzeit ist gerade nicht verfügbar.", Map.of());
      } else {
         try (Connection connection = this.openDatabaseConnection()) {
            UUID resolvedUuid = uuid;
            String resolvedName = playerName;
            PlaytimeFeature.PlayerTotals totals = null;
            if (uuid != null) {
               totals = this.readTotalsByUuid(connection, uuid);
            } else {
               try (PreparedStatement statement = connection.prepareStatement(
                     "SELECT `player_uuid`,`player_name`,`first_joined_at_ms`,`online_seconds`,`playtime_seconds` FROM `"
                        + this.tableName
                        + "` WHERE LOWER(`player_name`)=LOWER(?) ORDER BY `updated_at` DESC LIMIT 1"
                  )) {
                  statement.setString(1, playerName);

                  try (ResultSet result = statement.executeQuery()) {
                     if (result.next()) {
                        resolvedUuid = UUID.fromString(result.getString("player_uuid"));
                        resolvedName = result.getString("player_name");
                        totals = this.totalsFrom(result);
                     }
                  }
               }

               if (resolvedUuid == null) {
                  synchronized (this.stateLock) {
                     for (Entry<UUID, PlaytimeFeature.PendingTime> entry : this.pendingTimes.entrySet()) {
                        if (entry.getValue().playerName.equalsIgnoreCase(playerName)) {
                           resolvedUuid = entry.getKey();
                           resolvedName = entry.getValue().playerName;
                           break;
                        }
                     }
                  }

                  if (resolvedUuid != null) {
                     totals = this.readTotalsByUuid(connection, resolvedUuid);
                  }
               }
            }

            if (resolvedUuid != null && totals == null) {
               synchronized (this.stateLock) {
                  PlaytimeFeature.PendingTime pending = this.pendingTimes.get(resolvedUuid);
                  if (pending != null) {
                     resolvedName = pending.playerName;
                     totals = new PlaytimeFeature.PlayerTotals(pending.playerName, pending.firstJoinedAtMillis, 0L, 0L);
                  }
               }
            }

            if (resolvedUuid != null && totals != null) {
               LocalDate today = LocalDate.now(this.timeZone);
               LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
               LocalDate weekStart = currentWeekStart.minusWeeks(1L);
               LocalDate weekEnd = currentWeekStart.minusDays(1L);
               LocalDate currentMonthStart = today.withDayOfMonth(1);
               LocalDate monthStart = currentMonthStart.minusMonths(1L);
               LocalDate monthEnd = currentMonthStart.minusDays(1L);
               long todaySeconds = this.readDailyPlaytime(connection, resolvedUuid, today, today);
               long weekSeconds = this.readDailyPlaytime(connection, resolvedUuid, weekStart, weekEnd);
               long monthSeconds = this.readDailyPlaytime(connection, resolvedUuid, monthStart, monthEnd);
               long onlineSeconds = totals.onlineSeconds;
               long playtimeSeconds = totals.playtimeSeconds;
               Map<String, String> values = new HashMap<>();
               synchronized (this.stateLock) {
                  this.collectActiveTime(System.currentTimeMillis());
                  PlaytimeFeature.PendingTime pending = this.pendingTimes.get(resolvedUuid);
                  if (pending != null) {
                     resolvedName = pending.playerName;
                     onlineSeconds += pending.onlineSeconds;
                     playtimeSeconds += pending.playtimeSeconds;

                     for (Entry<LocalDate, PlaytimeFeature.DailyTime> entry : pending.daily.entrySet()) {
                        LocalDate date = entry.getKey();
                        if (date.equals(today)) {
                           todaySeconds += entry.getValue().playtimeSeconds;
                        }

                        if (!date.isBefore(weekStart) && !date.isAfter(weekEnd)) {
                           weekSeconds += entry.getValue().playtimeSeconds;
                        }

                        if (!date.isBefore(monthStart) && !date.isAfter(monthEnd)) {
                           monthSeconds += entry.getValue().playtimeSeconds;
                        }
                     }
                  }
               }

               values.put("%player%", resolvedName);
               values.put("%member-since%", this.formatJoined(totals.firstJoinedAtMillis));
               values.put("%online-time%", this.formatDuration(onlineSeconds));
               values.put("%playtime%", this.formatDuration(playtimeSeconds));
               values.put("%today%", this.formatDuration(todaySeconds));
               values.put("%last-7-days%", this.formatDuration(weekSeconds));
               values.put("%last-30-days%", this.formatDuration(monthSeconds));
               this.deliver(sender, self ? "messages.self" : "messages.other", "", values);
            } else {
               this.deliver(sender, "messages.not-found", "&cZu &e%player% &cgibt es noch keine Spielzeitdaten.", Map.of("%player%", playerName));
            }
         } catch (Exception exception) {
            this.plugin.getLogger().warning("Spielzeit konnte nicht gelesen werden: " + exception.getMessage());
            this.deliver(sender, "messages.unavailable", "&cDie Spielzeit ist gerade nicht verfügbar. Bitte versuche es später erneut.", Map.of());
         }
      }
   }

   private PlaytimeFeature.PlayerTotals readTotalsByUuid(Connection connection, UUID uuid) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(
            "SELECT `player_name`,`first_joined_at_ms`,`online_seconds`,`playtime_seconds` FROM `" + this.tableName + "` WHERE `player_uuid`=?"
         )) {
         statement.setString(1, uuid.toString());

         try (ResultSet result = statement.executeQuery()) {
            return result.next() ? this.totalsFrom(result) : null;
         }
      }
   }

   private PlaytimeFeature.PlayerTotals totalsFrom(ResultSet result) throws SQLException {
      return new PlaytimeFeature.PlayerTotals(
         result.getString("player_name"), result.getLong("first_joined_at_ms"), result.getLong("online_seconds"), result.getLong("playtime_seconds")
      );
   }

   private long readDailyPlaytime(Connection connection, UUID uuid, LocalDate from, LocalDate to) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COALESCE(SUM(`playtime_seconds`),0) FROM `" + this.dailyTableName + "` WHERE `player_uuid`=? AND `play_date` BETWEEN ? AND ?"
         )) {
         statement.setString(1, uuid.toString());
         statement.setDate(2, Date.valueOf(from));
         statement.setDate(3, Date.valueOf(to));

         try (ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
         }
      }
   }

   private void deliver(CommandSender sender, String path, String fallback, Map<String, String> values) {
      Runnable sendLines = () -> {
         List<String> templates;
         if (this.config.isList(path)) {
            templates = this.config.getStringList(path);
         } else if (this.config.isString(path)) {
            templates = List.of(this.config.getString(path, fallback));
         } else {
            templates = fallback.isEmpty() ? List.of() : List.of(fallback);
         }

         if (path.equals("messages.self") || path.equals("messages.other")) {
            this.sendUnprefixed(sender, this.config.getString("messages.header", "&2&l-------- &e&lPlaytime/Spielzeit &2&l---------"));
         }

         for (String template : templates) {
            String text = template;

            for (Entry<String, String> entry : values.entrySet()) {
               text = text.replace(entry.getKey(), entry.getValue());
            }

            this.sendUnprefixed(sender, text);
         }
      };
      if (Bukkit.isPrimaryThread()) {
         sendLines.run();
      } else {
         Bukkit.getScheduler().runTask(this.plugin, sendLines);
      }
   }

   private void sendUnprefixed(CommandSender sender, String text) {
      if (sender instanceof Player player) {
         text = this.plugin.applyPlaceholders(player, text);
      }

      sender.sendMessage(color(text));
   }

   private String formatJoined(long joinedAtMillis) {
      return joinedAtMillis <= 0L ? "Unbekannt" : JOINED_FORMAT.format(Instant.ofEpochMilli(joinedAtMillis).atZone(this.timeZone));
   }

   private String formatDuration(long totalSeconds) {
      long safeSeconds = Math.max(0L, totalSeconds);
      long hours = safeSeconds / 3600L;
      long minutes = safeSeconds % 3600L / 60L;
      long seconds = safeSeconds % 60L;
      return hours
         + (hours == 1L ? " Stunde " : " Stunden ")
         + minutes
         + (minutes == 1L ? " Minute " : " Minuten ")
         + seconds
         + (seconds == 1L ? " Sekunde" : " Sekunden");
   }

   private static final class DailyTime {
      private long onlineSeconds;
      private long playtimeSeconds;

      private DailyTime() {
      }

      private DailyTime(long onlineSeconds, long playtimeSeconds) {
         this.onlineSeconds = onlineSeconds;
         this.playtimeSeconds = playtimeSeconds;
      }

      private void add(long online, long playtime) {
         this.onlineSeconds += online;
         this.playtimeSeconds += playtime;
      }

      private void add(PlaytimeFeature.DailyTime other) {
         this.add(other.onlineSeconds, other.playtimeSeconds);
      }

      private void subtract(PlaytimeFeature.DailyTime other) {
         this.onlineSeconds = Math.max(0L, this.onlineSeconds - other.onlineSeconds);
         this.playtimeSeconds = Math.max(0L, this.playtimeSeconds - other.playtimeSeconds);
      }

      private boolean isZero() {
         return this.onlineSeconds == 0L && this.playtimeSeconds == 0L;
      }
   }

   private record PendingSnapshot(
      UUID uuid, String playerName, long firstJoinedAtMillis, long onlineSeconds, long playtimeSeconds, Map<LocalDate, PlaytimeFeature.DailyTime> daily
   ) {
   }

   private static final class PendingTime {
      private String playerName;
      private long firstJoinedAtMillis;
      private long onlineSeconds;
      private long playtimeSeconds;
      private final Map<LocalDate, PlaytimeFeature.DailyTime> daily = new HashMap<>();

      private PendingTime(String playerName, long firstJoinedAtMillis) {
         this.playerName = playerName;
         this.firstJoinedAtMillis = firstJoinedAtMillis;
      }

      private PlaytimeFeature.PendingSnapshot snapshot(UUID uuid) {
         Map<LocalDate, PlaytimeFeature.DailyTime> copy = new HashMap<>();

         for (Entry<LocalDate, PlaytimeFeature.DailyTime> entry : this.daily.entrySet()) {
            copy.put(entry.getKey(), new PlaytimeFeature.DailyTime(entry.getValue().onlineSeconds, entry.getValue().playtimeSeconds));
         }

         return new PlaytimeFeature.PendingSnapshot(uuid, this.playerName, this.firstJoinedAtMillis, this.onlineSeconds, this.playtimeSeconds, copy);
      }
   }

   private record PlayerTotals(String playerName, long firstJoinedAtMillis, long onlineSeconds, long playtimeSeconds) {
   }

   private static final class Session {
      private final String playerName;
      private final long joinedAtMillis;
      private long lastAccountedAt;
      private long lastActivityAt;
      private final TreeSet<Long> activityEvents = new TreeSet<>();

      private Session(String playerName, long startedAt, long firstJoinedAt) {
         this.playerName = playerName;
         this.joinedAtMillis = firstJoinedAt;
         this.lastAccountedAt = startedAt;
         this.lastActivityAt = startedAt;
      }

      private void markActivity(long now) {
         this.activityEvents.add(now);
      }

      private PlaytimeFeature.TimeDelta consume(long now, long afkTimeout, ZoneId zone) {
         long seconds = Math.max(0L, now - this.lastAccountedAt) / 1000L;
         if (seconds == 0L) {
            return PlaytimeFeature.TimeDelta.empty();
         }

         Map<LocalDate, PlaytimeFeature.DailyTime> daily = new HashMap<>();
         long start = this.lastAccountedAt;

         for (long index = 0L; index < seconds; index++) {
            long secondEnd = start + (index + 1L) * 1000L;

            while (!this.activityEvents.isEmpty() && this.activityEvents.first() <= secondEnd) {
               this.lastActivityAt = this.activityEvents.pollFirst();
            }

            boolean active = secondEnd - this.lastActivityAt <= afkTimeout;
            LocalDate date = Instant.ofEpochMilli(start + index * 1000L).atZone(zone).toLocalDate();
            daily.computeIfAbsent(date, ignored -> new PlaytimeFeature.DailyTime()).add(1L, active ? 1L : 0L);
         }

         this.lastAccountedAt += seconds * 1000L;
         this.activityEvents.headSet(this.lastAccountedAt, true).clear();
         long activeSeconds = daily.values().stream().mapToLong(value -> value.playtimeSeconds).sum();
         return new PlaytimeFeature.TimeDelta(seconds, activeSeconds, daily);
      }
   }

   private record TimeDelta(long onlineSeconds, long playtimeSeconds, Map<LocalDate, PlaytimeFeature.DailyTime> daily) {
      private static PlaytimeFeature.TimeDelta empty() {
         return new PlaytimeFeature.TimeDelta(0L, 0L, Map.of());
      }
   }
}

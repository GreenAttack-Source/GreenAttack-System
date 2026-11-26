package de.greenattack.system.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.greenattack.system.GreenAttackSystemPlugin;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

public final class MySqlPlayerBridgeFeature extends FeatureModule implements Listener {
   private static final String PLAYER_INDEX_TABLE = "mpb_player_index";
   private static final String LOCK_PREFIX = "gcplayer_";
   private static final long JOIN_LOCK_SECONDS = 5L;
   private final Gson gson = new Gson();
   private final Map<UUID, Connection> pendingJoinLocks = new ConcurrentHashMap<>();
   private volatile ExecutorService databaseExecutor;
   private volatile boolean databaseReady;
   private volatile boolean initializing;
   private volatile boolean stopping;
   private BukkitTask periodicTask;
   private CommandMap commandMap;
   private Command adminCommand;
   private MySqlPlayerBridgeFeature.ItemArrayCodec itemCodec;
   private String tableBase;

   public MySqlPlayerBridgeFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "mysqlplayerbridge");
      this.migrateRequestedNetworkSync();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      this.startFeature();
   }

   public void reloadFeature() {
      this.shutdownRuntime(true);
      this.reload();
      this.migrateRequestedNetworkSync();
      this.startFeature();
   }

   private void migrateRequestedNetworkSync() {
      boolean changed = false;
      if (!this.config.getBoolean("migrations.network-state-v1-applied", false)) {
         this.config.set("sync.inventory", true);
         this.config.set("sync.selected_hotbar_slot", true);
         this.config.set("sync.amorSlots", true);
         this.config.set("sync.offhand", true);
         this.config.set("sync.vaultEconomy", true);
         this.config.set("migrations.network-state-v1-applied", true);
         changed = true;
      }
      if (!this.config.getBoolean("migrations.crystals-v1-applied", false)) {
         if (!this.config.contains("sync.kristall")) this.config.set("sync.kristall", true);
         this.config.set("migrations.crystals-v1-applied", true);
         changed = true;
      }
      if (changed && this.save()) {
         this.plugin.getLogger().info("MySqlPlayerBridge: Netzwerk-Sync für Inventar, Rüstung, Nebenhand, Geld und Kristalle ist konfiguriert.");
      }
   }

   public void shutdownFeature() {
      this.shutdownRuntime(true);
   }

   private void startFeature() {
      this.stopping = false;
      this.registerAdminCommand();
      boolean syncModulesEnabled = this.hasEnabledModule();
      boolean createMissingPlayerData = this.config.getBoolean("settings.create-missing-player-data", true);
      if (this.isEnabled() && (syncModulesEnabled || createMissingPlayerData)) {
         this.tableBase = this.config.getString("database.main-table-name", "player_data").trim();
         if (!this.tableBase.matches("[A-Za-z0-9_]{1,42}")) {
            this.tableBase = "player_data";
            this.warn("Ungültiger MySqlPlayerBridge-Tabellenname; verwende player_data.");
         }

         if (this.usesItems()) {
            try {
               this.itemCodec = new MySqlPlayerBridgeFeature.ItemArrayCodec();
            } catch (Exception exception) {
               this.plugin.getLogger().severe("NBTAPI fehlt oder ist inkompatibel; Inventar-, Rüstungs- und Endertruhen-Sync kann nicht starten.");
               return;
            }
         }

         this.databaseExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "GreenAttack-MySqlPlayerBridge");
            thread.setDaemon(true);
            return thread;
         });
         this.initializeDatabase();
         if (syncModulesEnabled) {
            long delaySeconds = Math.max(1L, this.config.getLong("syncTask.delay", 90L));
            this.periodicTask = Bukkit.getScheduler().runTaskTimer(this.plugin, this::saveOnlinePlayers, delaySeconds * 20L, delaySeconds * 20L);
         }
      }
   }

   private void initializeDatabase() {
      ExecutorService executor = this.databaseExecutor;
      if (executor != null && !this.initializing && !this.databaseReady && !this.stopping) {
         String url = this.jdbcUrl();
         String user = this.config.getString("database.user", "").trim();
         String password = this.config.getString("database.password", "");
         if (url != null && !user.isEmpty()) {
            this.initializing = true;
            executor.execute(() -> {
               try {
                  Class.forName(url.startsWith("jdbc:mariadb:") ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver");

                  try (Connection connection = DriverManager.getConnection(url, user, password)) {
                     this.createTables(connection);
                  }

                  this.databaseReady = true;
                  this.plugin.getLogger().info("MySqlPlayerBridge-Datenbank ist bereit; aktive Module: " + String.join(", ", this.enabledModuleNames()));
               } catch (Exception exception) {
                  this.logBridgeError("Datenbankverbindung oder Tabellenerstellung fehlgeschlagen.", exception);
               } finally {
                  this.initializing = false;
               }
            });
         } else {
            this.plugin.getLogger().warning("MySqlPlayerBridge ist aktiv, aber database.host/name/user sind noch nicht gesetzt.");
         }
      }
   }

   private String jdbcUrl() {
      String custom = this.config.getString("database.jdbc-url", "").trim();
      if (!custom.isEmpty()) {
         return custom;
      } else {
         String host = this.config.getString("database.host", "").trim();
         String database = this.config.getString("database.name", "").trim();
         if (!host.isEmpty() && !database.isEmpty()) {
            int port = Math.max(1, Math.min(65535, this.config.getInt("database.port", 3306)));
            return "jdbc:mysql://" + host + ":" + port + "/" + database + "?autoReconnect=true";
         } else {
            return null;
         }
      }
   }

   private Connection openConnection() throws SQLException {
      String url = this.jdbcUrl();
      if (url == null) {
         throw new SQLException("Datenbankverbindung ist unvollständig konfiguriert.");
      } else {
         return DriverManager.getConnection(url, this.config.getString("database.user", ""), this.config.getString("database.password", ""));
      }
   }

   private void createTables(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement()) {
         statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS `mpb_player_index` (`uuid` VARCHAR(36) NOT NULL PRIMARY KEY, `player_name` VARCHAR(32), `timestamp` TEXT, `online` BOOLEAN, `server_id` TEXT) ENGINE=InnoDB"
         );

         for (MySqlPlayerBridgeFeature.Module module : MySqlPlayerBridgeFeature.Module.values()) {
            if (this.isModuleEnabled(module)) {
               statement.executeUpdate(
                  "CREATE TABLE IF NOT EXISTS `" + this.tableName(module) + "` (`uuid` VARCHAR(36) NOT NULL PRIMARY KEY, " + module.columns + ") ENGINE=InnoDB"
               );
            }
         }
      }
   }

   private String tableName(MySqlPlayerBridgeFeature.Module module) {
      return this.tableBase + module.suffix;
   }

   private String lockName(UUID uuid) {
      return "gcplayer_" + uuid.toString().replace("-", "");
   }

   private Connection acquirePlayerLock(UUID uuid) throws SQLException {
      Connection connection = this.openConnection();

      try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
         statement.setString(1, this.lockName(uuid));
         statement.setLong(2, 5L);

         try (ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1) {
               throw new SQLException("Spielerdaten-Sperre konnte nicht rechtzeitig erworben werden.");
            }
         }

         return connection;
      } catch (SQLException exception) {
         this.closeQuietly(connection);
         throw exception;
      }
   }

   private void releasePlayerLock(Connection connection, UUID uuid) {
      if (connection != null) {
         try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, this.lockName(uuid));
            statement.executeQuery().close();
         } catch (SQLException exception) {
            this.logBridgeError("Spielerdaten-Sperre konnte nicht freigegeben werden.", exception);
         } finally {
            this.closeQuietly(connection);
         }
      }
   }

   private void closeQuietly(Connection connection) {
      try {
         connection.close();
      } catch (SQLException var3) {
      }
   }

   private boolean playerIndexExists(Connection connection, UUID uuid) throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM `mpb_player_index` WHERE `uuid`=? LIMIT 1")) {
         statement.setString(1, uuid.toString());

         try (ResultSet result = statement.executeQuery()) {
            return result.next();
         }
      }
   }

   private void updatePlayerIndex(Connection connection, UUID uuid, String playerName, boolean online) throws SQLException {
      String sql = "INSERT INTO `mpb_player_index` (`uuid`,`player_name`,`timestamp`,`online`,`server_id`) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE `player_name`=VALUES(`player_name`), `timestamp`=VALUES(`timestamp`), `online`=VALUES(`online`), `server_id`=VALUES(`server_id`)";

      try (PreparedStatement statement = connection.prepareStatement(sql)) {
         statement.setString(1, uuid.toString());
         statement.setString(2, playerName);
         statement.setString(3, Long.toString(System.currentTimeMillis()));
         statement.setBoolean(4, online);
         statement.setString(5, online ? this.config.getString("settings.server-id", "") : "");
         statement.executeUpdate();
      }
   }

   private void upsert(Connection connection, MySqlPlayerBridgeFeature.Module module, UUID uuid, Map<String, Object> values) throws SQLException {
      if (!values.isEmpty()) {
         List<String> columns = new ArrayList<>(values.keySet());
         StringBuilder sql = new StringBuilder("INSERT INTO `").append(this.tableName(module)).append("` (`uuid`");

         for (String column : columns) {
            sql.append(",`").append(column).append('`');
         }

         sql.append(") VALUES (");

         for (int i = 0; i <= columns.size(); i++) {
            if (i > 0) {
               sql.append(',');
            }

            sql.append('?');
         }

         sql.append(')');
         sql.append(" ON DUPLICATE KEY UPDATE ");

         for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
               sql.append(',');
            }

            sql.append('`').append(columns.get(i)).append("`=VALUES(`").append(columns.get(i)).append("`)");
         }

         try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, uuid.toString());

            for (int i = 0; i < columns.size(); i++) {
               statement.setObject(i + 2, values.get(columns.get(i)));
            }

            statement.executeUpdate();
         }
      }
   }

   private Map<MySqlPlayerBridgeFeature.Module, Map<String, Object>> loadPlayer(Connection connection, UUID uuid) throws SQLException {
      Map<MySqlPlayerBridgeFeature.Module, Map<String, Object>> snapshot = new EnumMap<>(MySqlPlayerBridgeFeature.Module.class);

      for (MySqlPlayerBridgeFeature.Module module : MySqlPlayerBridgeFeature.Module.values()) {
         if (this.isModuleEnabled(module)) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM `" + this.tableName(module) + "` WHERE `uuid`=? LIMIT 1")) {
               statement.setString(1, uuid.toString());

               try (ResultSet result = statement.executeQuery()) {
                  if (result.next()) {
                     Map<String, Object> row = new HashMap<>();

                     for (String column : module.columnNames) {
                        row.put(column, result.getObject(column));
                     }

                     snapshot.put(module, row);
                  }
               }
            }
         }
      }

      return snapshot;
   }

   private void saveSnapshot(Connection connection, MySqlPlayerBridgeFeature.PlayerSnapshot snapshot, boolean online) throws SQLException {
      this.updatePlayerIndex(connection, snapshot.uuid, snapshot.name, online);

      for (MySqlPlayerBridgeFeature.Module module : MySqlPlayerBridgeFeature.Module.values()) {
         Map<String, Object> values = snapshot.data.get(module);
         if (this.isModuleEnabled(module) && values != null) {
            this.upsert(connection, module, snapshot.uuid, values);
         }
      }
   }

   private void onPlayerJoin(Player player) {
      if (this.isEnabled()) {
         if (this.hasEnabledModule() || this.config.getBoolean("settings.create-missing-player-data", true)) {
            MySqlPlayerBridgeFeature.PlayerSnapshot joinSnapshot;
            try {
               joinSnapshot = this.captureSnapshot(player);
            } catch (Exception exception) {
               this.handleSyncFailure(player, exception);
               return;
            }

            if (!this.databaseReady) {
               this.initializeDatabase();
               ExecutorService pendingExecutor = this.databaseExecutor;
               if (pendingExecutor != null && !pendingExecutor.isShutdown()) {
                  pendingExecutor.execute(
                     () -> {
                        if (player.isOnline() && this.databaseReady) {
                           this.syncOnJoin(player, joinSnapshot);
                        } else if (player.isOnline() && this.config.getBoolean("settings.kickPlayerOnSyncFail", true)) {
                           this.queueKick(
                              player, "messages.database-not-ready", "&cDeine Daten können gerade nicht geladen werden. Bitte versuche es später erneut."
                           );
                        }
                     }
                  );
               }
            } else {
               ExecutorService executor = this.databaseExecutor;
               if (executor != null && !executor.isShutdown()) {
                  executor.execute(() -> this.syncOnJoin(player, joinSnapshot));
               }
            }
         }
      }
   }

   private void syncOnJoin(Player player, MySqlPlayerBridgeFeature.PlayerSnapshot joinSnapshot) {
      UUID uuid = player.getUniqueId();
      Connection connection = null;
      boolean retained = false;

      try {
         connection = this.acquirePlayerLock(uuid);
         if (this.playerIndexExists(connection, uuid)) {
            Map<MySqlPlayerBridgeFeature.Module, Map<String, Object>> stored = this.loadPlayer(connection, uuid);
            for (MySqlPlayerBridgeFeature.Module module : MySqlPlayerBridgeFeature.Module.values()) {
               Map<String, Object> values = joinSnapshot.data.get(module);
               if (this.isModuleEnabled(module) && !stored.containsKey(module) && values != null) {
                  this.upsert(connection, module, uuid, values);
                  stored.put(module, values);
               }
            }
            this.updatePlayerIndex(connection, uuid, player.getName(), true);
            this.pendingJoinLocks.put(uuid, connection);
            Connection heldConnection = connection;
            retained = true;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
               if (this.pendingJoinLocks.get(uuid) == heldConnection) {
                  try {
                     if (player.isOnline()) {
                        this.applySnapshot(player, stored);
                     }
                  } catch (Exception exception) {
                     this.handleSyncFailure(player, exception);
                  } finally {
                     this.releasePendingLock(uuid, heldConnection);
                  }
               }
            });
            return;
         }

         if (!this.config.getBoolean("settings.no-entry-protection", false) || this.config.getBoolean("settings.create-missing-player-data", true)) {
            this.saveSnapshot(connection, joinSnapshot, true);
            Bukkit.getScheduler().runTask(this.plugin, () -> {
               if (player.isOnline()) {
                  this.sendBridgeMessage(player, "messages.first-join", "&7Deine Spielerdaten wurden &aerstellt&7.");
               }
            });
            return;
         }

         this.queueKick(player, "messages.no-entry", "&cFür dich sind keine synchronisierten Spielerdaten vorhanden.");
      } catch (Exception exception) {
         this.handleSyncFailure(player, exception);
         return;
      } finally {
         if (!retained) {
            this.releasePlayerLock(connection, uuid);
         }
      }
   }

   private void releasePendingLock(UUID uuid, Connection connection) {
      if (this.pendingJoinLocks.remove(uuid, connection)) {
         ExecutorService executor = this.databaseExecutor;
         if (executor != null && !executor.isShutdown()) {
            executor.execute(() -> this.releasePlayerLock(connection, uuid));
         } else {
            this.releasePlayerLock(connection, uuid);
         }
      }
   }

   private void onPlayerQuit(Player player) {
      UUID uuid = player.getUniqueId();
      Connection pending = this.pendingJoinLocks.remove(uuid);
      ExecutorService executor = this.databaseExecutor;
      if (executor != null && !executor.isShutdown()) {
         Map<MySqlPlayerBridgeFeature.Module, Map<String, Object>> snapshot;
         try {
            snapshot = this.captureSnapshot(player).data;
         } catch (Exception exception) {
            this.logBridgeError("Spielerdaten konnten beim Verlassen nicht erfasst werden: " + player.getName(), exception);
            if (pending != null) {
               executor.execute(() -> this.releasePlayerLock(pending, uuid));
            }

            return;
         }

         executor.execute(() -> {
            Connection connection = pending;
            try {
               if (connection == null) {
                  connection = this.acquirePlayerLock(uuid);
               }
               this.saveSnapshot(connection, new MySqlPlayerBridgeFeature.PlayerSnapshot(uuid, player.getName(), snapshot), false);
            } catch (Exception exception) {
               this.logBridgeError("Spielerdaten konnten beim Verlassen nicht gespeichert werden: " + player.getName(), exception);
            } finally {
               this.releasePlayerLock(connection, uuid);
            }
         });
      } else {
         if (pending != null) {
            this.releasePlayerLock(pending, uuid);
         }
      }
   }

   private void saveOnlinePlayers() {
      if (this.isEnabled() && this.hasEnabledModule()) {
         if (!this.databaseReady) {
            this.initializeDatabase();
         } else {
            ExecutorService executor = this.databaseExecutor;
            if (executor != null && !executor.isShutdown()) {
               for (Player player : Bukkit.getOnlinePlayers()) {
                  try {
                     MySqlPlayerBridgeFeature.PlayerSnapshot snapshot = this.captureSnapshot(player);
                     executor.execute(() -> {
                        try (Connection connection = this.acquirePlayerLock(snapshot.uuid)) {
                           this.saveSnapshot(connection, snapshot, true);
                        } catch (Exception exception) {
                           this.handleSyncFailure(player, exception);
                        }
                     });
                  } catch (Exception exception) {
                     this.handleSyncFailure(player, exception);
                  }
               }
            }
         }
      }
   }

   private MySqlPlayerBridgeFeature.PlayerSnapshot captureSnapshot(Player player) throws Exception {
      Map<MySqlPlayerBridgeFeature.Module, Map<String, Object>> data = new EnumMap<>(MySqlPlayerBridgeFeature.Module.class);

      for (MySqlPlayerBridgeFeature.Module module : MySqlPlayerBridgeFeature.Module.values()) {
         if (this.isModuleEnabled(module)) {
            Map<String, Object> row = switch (module) {
               case INVENTORY -> Map.of("inventory", this.itemCodec.serialize(player.getInventory().getStorageContents()));
               case SELECTED_HOTBAR_SLOT -> Map.of("slot", player.getInventory().getHeldItemSlot());
               case ENDERCHEST -> Map.of("enderchest", this.itemCodec.serialize(player.getEnderChest().getContents()));
               case ARMOR -> Map.of("armor", this.itemCodec.serialize(player.getInventory().getArmorContents()));
               case OFFHAND -> Map.of("offhand", this.itemCodec.serialize(new ItemStack[]{player.getInventory().getItemInOffHand()}));
               case LOCATION -> this.captureLocation(player);
               case GAMEMODE -> Map.of("gamemode", player.getGameMode().name());
               case EXP -> Map.of("exp_level", player.getLevel(), "exp", player.getExp());
               case HEALTH -> this.captureHealth(player);
               case SATURATION -> Map.of("saturation", player.getSaturation(), "food_level", player.getFoodLevel());
               case EFFECTS -> Map.of("effects", this.serializeEffects(player));
               case ADVANCEMENTS -> Map.of("advancements", this.serializeAdvancements(player));
               case STATISTICS -> Map.of("stats", this.serializeStatistics(player));
               case MONEY -> this.captureMoney(player);
               case KRISTALL -> Map.of("crystals", this.plugin.getKristallFeature().getBalance(player.getUniqueId()));
            };
            data.put(module, row);
         }
      }

      return new MySqlPlayerBridgeFeature.PlayerSnapshot(player.getUniqueId(), player.getName(), data);
   }

   private Map<String, Object> captureLocation(Player player) {
      Location location = player.getLocation();
      World world = location.getWorld();
      if (world == null) {
         return Map.of();
      }

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("world", world.getName());
      row.put("x", location.getX());
      row.put("y", location.getY());
      row.put("z", location.getZ());
      row.put("yaw", location.getYaw());
      row.put("pitch", location.getPitch());
      return row;
   }

   private Map<String, Object> captureHealth(Player player) {
      AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
      double maxHealth = attribute == null ? 20.0 : attribute.getBaseValue();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("health", player.getHealth());
      row.put("max_health", maxHealth);
      row.put("health_scaled", player.isHealthScaled());
      row.put("health_scale", player.getHealthScale());
      return row;
   }

   private Map<String, Object> captureMoney(Player player) {
      Economy economy = this.getEconomy();
      if (economy == null) {
         throw new IllegalStateException("Vault-Economy-Modul ist aktiviert, aber kein Vault-Anbieter verfügbar ist.");
      } else {
         return Map.of("money", economy.getBalance(player));
      }
   }

   private void applySnapshot(Player player, Map<MySqlPlayerBridgeFeature.Module, Map<String, Object>> snapshot) throws Exception {
      for (MySqlPlayerBridgeFeature.Module module : MySqlPlayerBridgeFeature.Module.values()) {
         Map<String, Object> row = snapshot.get(module);
         if (row != null) {
            switch (module) {
               case INVENTORY:
                  player.getInventory().setStorageContents(this.itemCodec.deserialize(this.string(row, "inventory")));
                  break;
               case SELECTED_HOTBAR_SLOT:
                  int slot = this.number(row.get("slot")).intValue();
                  if (slot >= 0 && slot < 9) {
                     player.getInventory().setHeldItemSlot(slot);
                  }
                  break;
               case ENDERCHEST:
                  player.getEnderChest().setContents(this.itemCodec.deserialize(this.string(row, "enderchest")));
                  break;
               case ARMOR:
                  player.getInventory().setArmorContents(this.itemCodec.deserialize(this.string(row, "armor")));
                  break;
               case OFFHAND:
                  ItemStack[] offhand = this.itemCodec.deserialize(this.string(row, "offhand"));
                  player.getInventory().setItemInOffHand(offhand.length == 0 || offhand[0] == null ? new ItemStack(Material.AIR) : offhand[0]);
                  break;
               case LOCATION:
                  this.applyLocation(player, row);
                  break;
               case GAMEMODE:
                  this.applyGamemode(player, row);
                  break;
               case EXP:
                  player.setExp(this.number(row.get("exp")).floatValue());
                  player.setLevel(this.number(row.get("exp_level")).intValue());
                  break;
               case HEALTH:
                  this.applyHealth(player, row);
                  break;
               case SATURATION:
                  player.setFoodLevel(this.number(row.get("food_level")).intValue());
                  player.setSaturation(this.number(row.get("saturation")).floatValue());
                  break;
               case EFFECTS:
                  this.applyEffects(player, this.string(row, "effects"));
                  break;
               case ADVANCEMENTS:
                  this.applyAdvancements(player, this.string(row, "advancements"));
                  break;
               case STATISTICS:
                  this.applyStatistics(player, this.string(row, "stats"));
                  break;
               case MONEY:
                  this.applyMoney(player, this.number(row.get("money")).doubleValue());
                  break;
               case KRISTALL:
                  this.plugin.getKristallFeature().applySyncedBalance(
                     player.getUniqueId(), Math.max(0L, this.number(row.get("crystals")).longValue())
                  );
                  break;
            }
         }
      }
   }

   private void applyLocation(Player player, Map<String, Object> row) {
      World world = Bukkit.getWorld(this.string(row, "world"));
      if (world == null) {
         if (!this.config.getBoolean("settings.suppress-warnings", false)) {
            this.plugin.getLogger().warning("Player-Bridge: Welt '" + row.get("world") + "' ist auf diesem Server nicht geladen.");
         }
      } else {
         player.teleport(
            new Location(
               world,
               this.number(row.get("x")).doubleValue(),
               this.number(row.get("y")).doubleValue(),
               this.number(row.get("z")).doubleValue(),
               this.number(row.get("yaw")).floatValue(),
               this.number(row.get("pitch")).floatValue()
            )
         );
      }
   }

   private void applyGamemode(Player player, Map<String, Object> row) {
      try {
         player.setGameMode(GameMode.valueOf(this.string(row, "gamemode").toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
         if (!this.config.getBoolean("settings.suppress-warnings", false)) {
            this.plugin.getLogger().warning("Player-Bridge: unbekannter Spielmodus in der Datenbank.");
         }
      }
   }

   private void applyHealth(Player player, Map<String, Object> row) {
      AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
      double maxHealth = Math.max(1.0, this.number(row.get("max_health")).doubleValue());
      if (attribute != null) {
         attribute.setBaseValue(maxHealth);
      }

      boolean scaled = this.bool(row.get("health_scaled"));
      player.setHealthScaled(scaled);
      if (scaled) {
         player.setHealthScale(Math.max(1.0, this.number(row.get("health_scale")).doubleValue()));
      }

      player.setHealth(Math.min(maxHealth, Math.max(0.0, this.number(row.get("health")).doubleValue())));
   }

   private void applyMoney(Player player, double amount) {
      Economy economy = this.getEconomy();
      if (economy == null) {
         throw new IllegalStateException("Vault-Economy-Modul ist aktiviert, aber kein Vault-Anbieter verfügbar ist.");
      }

      double current = economy.getBalance(player);
      if (amount > current) {
         economy.depositPlayer(player, amount - current);
      } else if (amount < current) {
         economy.withdrawPlayer(player, current - amount);
      }
   }

   private String serializeEffects(Player player) {
      JsonArray effects = new JsonArray();

      for (PotionEffect effect : player.getActivePotionEffects()) {
         JsonObject value = new JsonObject();
         value.addProperty("type", effect.getType().getName());
         value.addProperty("duration", effect.getDuration());
         value.addProperty("amplifier", effect.getAmplifier());
         value.addProperty("ambient", effect.isAmbient());
         value.addProperty("particles", effect.hasParticles());
         value.addProperty("icon", effect.hasIcon());
         effects.add(value);
      }

      return this.encodeJson(effects);
   }

   private void applyEffects(Player player, String encoded) {
      for (JsonElement element : this.decodeJson(encoded)) {
         JsonObject value = element.getAsJsonObject();
         PotionEffectType type = PotionEffectType.getByName(value.get("type").getAsString());
         if (type != null) {
            player.addPotionEffect(
               new PotionEffect(
                  type,
                  value.get("duration").getAsInt(),
                  value.get("amplifier").getAsInt(),
                  value.get("ambient").getAsBoolean(),
                  value.get("particles").getAsBoolean(),
                  value.get("icon").getAsBoolean()
               )
            );
         }
      }
   }

   private String serializeAdvancements(Player player) {
      JsonArray values = new JsonArray();
      Iterator<Advancement> advancements = Bukkit.getServer().advancementIterator();

      while (advancements.hasNext()) {
         Advancement advancement = advancements.next();
         AdvancementProgress progress = player.getAdvancementProgress(advancement);
         if (progress.isDone() || !progress.getAwardedCriteria().isEmpty()) {
            JsonObject value = new JsonObject();
            value.addProperty("type", advancement.getKey().toString());
            value.addProperty("done", progress.isDone());
            value.addProperty("awardedCriteria", String.join(",", progress.getAwardedCriteria()));
            values.add(value);
         }
      }

      return this.encodeJson(values);
   }

   private void applyAdvancements(Player player, String encoded) {
      for (JsonElement element : this.decodeJson(encoded)) {
         JsonObject value = element.getAsJsonObject();
         String keyText = value.get("type").getAsString();
         NamespacedKey key = NamespacedKey.fromString(keyText);
         if (key != null) {
            Advancement advancement = Bukkit.getAdvancement(key);
            if (advancement != null) {
               AdvancementProgress progress = player.getAdvancementProgress(advancement);
               String criteria = value.has("awardedCriteria") ? value.get("awardedCriteria").getAsString() : "";
               if (!criteria.isBlank()) {
                  for (String criterion : criteria.split(",")) {
                     if ((criterion.isBlank() || !progress.getAwardedCriteria().contains(criterion)) && !criterion.isBlank()) {
                        progress.awardCriteria(criterion);
                     }
                  }
               }
            }
         }
      }
   }

   private String serializeStatistics(Player player) {
      JsonArray values = new JsonArray();

      for (Statistic statistic : Statistic.values()) {
         try {
            switch (statistic.getType()) {
               case UNTYPED:
                  this.addStatistic(values, statistic, null, player.getStatistic(statistic));
                  break;
               case BLOCK:
                  for (Material material : Material.values()) {
                     if (material.isBlock()) {
                        this.addStatistic(values, statistic, material.name(), player.getStatistic(statistic, material));
                     }
                  }
                  break;
               case ITEM:
                  for (Material material : Material.values()) {
                     if (material.isItem()) {
                        this.addStatistic(values, statistic, material.name(), player.getStatistic(statistic, material));
                     }
                  }
                  break;
               case ENTITY:
                  for (EntityType entity : EntityType.values()) {
                     try {
                        this.addStatistic(values, statistic, entity.name(), player.getStatistic(statistic, entity));
                     } catch (IllegalArgumentException var12) {
                     }
                  }
            }
         } catch (Exception var13) {
         }
      }

      return this.encodeJson(values);
   }

   private void addStatistic(JsonArray values, Statistic statistic, String subType, int amount) {
      if (amount != 0) {
         JsonObject value = new JsonObject();
         value.addProperty("type", statistic.name());
         if (subType != null) {
            value.addProperty("subType", subType);
         }

         value.addProperty("value", amount);
         values.add(value);
      }
   }

   private void applyStatistics(Player player, String encoded) {
      for (JsonElement element : this.decodeJson(encoded)) {
         JsonObject value = element.getAsJsonObject();

         Statistic statistic;
         try {
            statistic = Statistic.valueOf(value.get("type").getAsString());
         } catch (IllegalArgumentException exception) {
            continue;
         }

         int amount = value.get("value").getAsInt();

         try {
            switch (statistic.getType()) {
               case UNTYPED:
                  player.setStatistic(statistic, amount);
                  break;
               case BLOCK:
                  Material materialx = Material.matchMaterial(value.get("subType").getAsString());
                  if (materialx != null && materialx.isBlock()) {
                     player.setStatistic(statistic, materialx, amount);
                  }
                  break;
               case ITEM:
                  Material material = Material.matchMaterial(value.get("subType").getAsString());
                  if (material != null && material.isItem()) {
                     player.setStatistic(statistic, material, amount);
                  }
                  break;
               case ENTITY:
                  player.setStatistic(statistic, EntityType.valueOf(value.get("subType").getAsString()), amount);
            }
         } catch (Exception var9) {
         }
      }
   }

   private String encodeJson(JsonElement value) {
      return Base64.getEncoder().encodeToString(this.gson.toJson(value).getBytes(StandardCharsets.UTF_8));
   }

   private JsonArray decodeJson(String value) {
      JsonArray result = (JsonArray)this.gson.fromJson(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8), JsonArray.class);
      return result == null ? new JsonArray() : result;
   }

   private Economy getEconomy() {
      RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
      return registration == null ? null : (Economy)registration.getProvider();
   }

   private void handleSyncFailure(Player player, Exception exception) {
      this.logBridgeError("Synchronisierung fehlgeschlagen für " + player.getName(), exception);
      if (this.config.getBoolean("settings.kickPlayerOnSyncFail", true)) {
         this.queueKick(player, "messages.sync-failed", "&cDie Synchronisierung ist fehlgeschlagen.");
      }
   }

   private void queueKick(Player player, String messagePath, String fallback) {
      Bukkit.getScheduler().runTask(this.plugin, () -> this.kick(player, messagePath, fallback));
   }

   private void kick(Player player, String messagePath, String fallback) {
      if (player.isOnline()) {
         player.kickPlayer(this.message(messagePath, fallback));
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
      if (this.isEnabled()
         && this.hasEnabledModule()
         && this.config.getBoolean("settings.no-entry-protection", false)
         && !this.config.getBoolean("settings.create-missing-player-data", true)) {
         if (this.databaseReady && this.databaseExecutor != null) {
            try (Connection connection = this.openConnection()) {
               if (!this.playerIndexExists(connection, event.getUniqueId())) {
                  event.disallow(Result.KICK_OTHER, this.message("messages.no-entry", "&cFür dich sind keine synchronisierten Daten vorhanden."));
               }
            } catch (Exception exception) {
               this.logBridgeError("No-Entry-Prüfung fehlgeschlagen.", exception);
               if (this.config.getBoolean("settings.kickPlayerOnSyncFail", true)) {
                  event.disallow(
                     Result.KICK_OTHER,
                     this.message("messages.database-not-ready", "&cDeine Daten können gerade nicht geladen werden. Bitte versuche es später erneut.")
                  );
               }
            }
         } else {
            if (this.config.getBoolean("settings.kickPlayerOnSyncFail", true)) {
               event.disallow(
                  Result.KICK_OTHER,
                  this.message("messages.database-not-ready", "&cDeine Daten können gerade nicht geladen werden. Bitte versuche es später erneut.")
               );
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onJoin(PlayerJoinEvent event) {
      this.onPlayerJoin(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onQuit(PlayerQuitEvent event) {
      this.onPlayerQuit(event.getPlayer());
   }

   private void registerAdminCommand() {
      String name = this.config.getString("settings.command-prefix", "mpb").toLowerCase(Locale.ROOT).trim();
      if (!name.matches("[a-z0-9_-]+")) {
         name = "mpb";
      }

      try {
         if (!(this.plugin.getServer().getClass().getMethod("getCommandMap").invoke(this.plugin.getServer()) instanceof CommandMap map)) {
            throw new IllegalStateException("CommandMap nicht verfügbar.");
         }

         if (this.adminCommand != null) {
            this.adminCommand.unregister(this.commandMap);
         }

         this.commandMap = map;
         this.adminCommand = new Command(name, "MySqlPlayerBridge-Verwaltung", "/" + name + " <sync|status> [Spieler|*]", List.of()) {
            public boolean execute(CommandSender sender, String label, String[] args) {
               return MySqlPlayerBridgeFeature.this.executeAdminCommand(sender, args, label);
            }

            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
               if (args.length == 1) {
                  return List.of("sync", "status").stream().filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
               } else {
                  return args.length == 2 && args[0].equalsIgnoreCase("sync")
                     ? MySqlPlayerBridgeFeature.this.plugin
                        .getServer()
                        .getOnlinePlayers()
                        .stream()
                        .<String>map(Player::getName)
                        .filter(player -> player.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList()
                     : List.of();
               }
            }
         };
         this.commandMap.register(this.plugin.getName().toLowerCase(Locale.ROOT), this.adminCommand);
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.warn("MySqlPlayerBridge-Verwaltungsbefehl konnte nicht registriert werden: " + exception.getMessage());
      }
   }

   private boolean executeAdminCommand(CommandSender sender, String[] args, String label) {
      if (!this.hasFeaturePermission(sender, this.config.getString("settings.admin-permission", "system.mysqlplayerbridge.admin"))) {
         sender.sendMessage(this.message("messages.no-permission", "&cDazu hast du keine Rechte."));
         return true;
      }

      if (args.length == 0) {
         sender.sendMessage(this.message("messages.command-usage", "&7Verwendung: &f/%command% <sync|status> [Spieler|*]").replace("%command%", label));
         return true;
      }

      if (args[0].equalsIgnoreCase("status")) {
         if (!this.isEnabled() || !this.hasEnabledModule()) {
            sender.sendMessage(this.message("messages.status-off", "&eKeine Module aktiviert."));
         } else if (this.databaseReady) {
            sender.sendMessage(
               this.message("messages.status-ready", "&aBridge bereit: %modules%").replace("%modules%", String.join(", ", this.enabledModuleNames()))
            );
         } else {
            sender.sendMessage(
               this.message("messages.database-not-ready", "&cDeine Daten können gerade nicht geladen werden. Bitte versuche es später erneut.")
            );
         }

         return true;
      } else {
         if (!args[0].equalsIgnoreCase("sync") || args.length > 2) {
            sender.sendMessage(this.message("messages.command-usage", "&7Verwendung: &f/%command% <sync|status> [Spieler|*]").replace("%command%", label));
            return true;
         }

         if (!this.isEnabled() || !this.hasEnabledModule()) {
            sender.sendMessage(this.message("messages.status-off", "&eKeine Module aktiviert."));
            return true;
         }

         if (!this.databaseReady) {
            sender.sendMessage(
               this.message("messages.database-not-ready", "&cDeine Daten können gerade nicht geladen werden. Bitte versuche es später erneut.")
            );
            this.initializeDatabase();
            return true;
         }

         List<Player> targets;
         if (args.length != 1 && !args[1].equals("*")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
               sender.sendMessage(color("&cDer Spieler muss online sein."));
               return true;
            }

            targets = List.of(target);
         } else {
            targets = new ArrayList<>(Bukkit.getOnlinePlayers());
         }

         for (Player target : targets) {
            MySqlPlayerBridgeFeature.PlayerSnapshot snapshot;
            try {
               snapshot = this.captureSnapshot(target);
            } catch (Exception exception) {
               this.handleSyncFailure(target, exception);
               continue;
            }

            ExecutorService executor = this.databaseExecutor;
            if (executor != null && !executor.isShutdown()) {
               executor.execute(() -> {
                  try (Connection connection = this.acquirePlayerLock(snapshot.uuid)) {
                     this.saveSnapshot(connection, snapshot, true);
                  } catch (Exception exception) {
                     this.handleSyncFailure(target, exception);
                  }
               });
            }
         }

         sender.sendMessage(this.message("messages.sync-started", "&aSynchronisierung gestartet."));
         return true;
      }
   }

   private boolean isModuleEnabled(MySqlPlayerBridgeFeature.Module module) {
      return this.config.getBoolean(module.configPath, false);
   }

   private boolean hasEnabledModule() {
      for (MySqlPlayerBridgeFeature.Module module : MySqlPlayerBridgeFeature.Module.values()) {
         if (this.isModuleEnabled(module)) {
            return true;
         }
      }

      return false;
   }

   private boolean usesItems() {
      return this.isModuleEnabled(MySqlPlayerBridgeFeature.Module.INVENTORY)
         || this.isModuleEnabled(MySqlPlayerBridgeFeature.Module.ARMOR)
         || this.isModuleEnabled(MySqlPlayerBridgeFeature.Module.OFFHAND)
         || this.isModuleEnabled(MySqlPlayerBridgeFeature.Module.ENDERCHEST);
   }

   private List<String> enabledModuleNames() {
      List<String> names = new ArrayList<>();

      for (MySqlPlayerBridgeFeature.Module module : MySqlPlayerBridgeFeature.Module.values()) {
         if (this.isModuleEnabled(module)) {
            names.add(module.displayName);
         }
      }

      return names;
   }

   private String message(String path, String fallback) {
      String configured = this.config.getString(path);
      return color(this.prefix() + (configured == null ? this.localizedFallback(path, fallback) : configured));
   }

   private void sendBridgeMessage(Player player, String path, String fallback) {
      if (player.isOnline()) {
         String configured = this.config.getString(path);
         String text = configured == null ? this.localizedFallback(path, fallback) : configured;
         player.sendMessage(color(this.prefix() + text));
      }
   }

   private String localizedFallback(String path, String german) {
      String language = this.config.getString("settings.language", "de-de").toLowerCase(Locale.ROOT);
      boolean english = language.startsWith("en");
      boolean chinese = language.startsWith("zh");
      if (!english && !chinese) {
         return german;
      }

      return switch (path) {
         case "messages.no-permission" -> english ? "&cYou do not have permission." : "&c你没有权限。";
         case "messages.database-not-ready" -> english ? "&cYour data is temporarily unavailable. Please try again later." : "&c你的数据暂时无法读取，请稍后重试。";
         case "messages.sync-started" -> english ? "&aSynchronization started." : "&a同步已开始。";
         case "messages.sync-failed" -> english ? "&cPlayer data could not be synchronized. Please contact an administrator." : "&c玩家数据同步失败，请联系管理员。";
         case "messages.no-entry" -> english ? "&cNo synchronized data exists for you yet." : "&c尚无你的同步数据。";
         case "messages.command-usage" -> english ? "&7Usage: &f/%command% <sync|status> [player|*]" : "&7用法：&f/%command% <sync|status> [玩家|*]";
         case "messages.status-ready" -> english ? "&aPlayer bridge ready. Enabled modules: &e%modules%" : "&a玩家数据桥已就绪。已启用模块：&e%modules%";
         case "messages.status-off" -> english ? "&eNo data modules are enabled." : "&e没有启用数据模块。";
         case "messages.sync-success" -> english ? "&aYour player data has been synchronized." : "&a你的玩家数据已同步。";
         case "messages.first-join" -> english ? "&7Your player data has been &acreated&7." : "&7已为你&a创建&7玩家数据。";
         default -> german;
      };
   }

   private String string(Map<String, Object> values, String key) {
      Object value = values.get(key);
      if (value == null) {
         throw new IllegalArgumentException("Fehlendes Datenbankfeld: " + key);
      } else {
         return String.valueOf(value);
      }
   }

   private Number number(Object value) {
      if (value instanceof Number number) {
         return number;
      } else if (value == null) {
         throw new IllegalArgumentException("Leeres numerisches Datenbankfeld.");
      } else {
         return Double.parseDouble(String.valueOf(value));
      }
   }

   private boolean bool(Object value) {
      return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
   }

   private void logBridgeError(String message, Throwable exception) {
      if (!this.config.getBoolean("settings.suppress-warnings", false)) {
         this.plugin
            .getLogger()
            .log(Level.WARNING, "[MySqlPlayerBridge] " + message + " " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
      }
   }

   private void warn(String message) {
      if (!this.config.getBoolean("settings.suppress-warnings", false)) {
         this.plugin.getLogger().warning("[MySqlPlayerBridge] " + message);
      }
   }

   private void shutdownRuntime(boolean saveOnline) {
      this.stopping = true;
      this.databaseReady = false;
      if (this.periodicTask != null) {
         this.periodicTask.cancel();
         this.periodicTask = null;
      }

      if (this.adminCommand != null && this.commandMap != null) {
         this.adminCommand.unregister(this.commandMap);
         this.adminCommand = null;
      }

      ExecutorService executor = this.databaseExecutor;
      if (executor != null) {
         if (saveOnline && this.hasEnabledModule()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
               try {
                  MySqlPlayerBridgeFeature.PlayerSnapshot snapshot = this.captureSnapshot(player);
                  executor.execute(() -> {
                     try (Connection connectionx = this.acquirePlayerLock(snapshot.uuid)) {
                        this.saveSnapshot(connectionx, snapshot, true);
                     } catch (Exception exception) {
                        this.logBridgeError("Abschlussspeicherung fehlgeschlagen für " + snapshot.name, exception);
                     }
                  });
               } catch (Exception exception) {
                  this.logBridgeError("Abschlussspeicherung konnte nicht vorbereitet werden.", exception);
               }
            }
         }

         for (Entry<UUID, Connection> entry : this.pendingJoinLocks.entrySet()) {
            if (this.pendingJoinLocks.remove(entry.getKey(), entry.getValue())) {
               Connection connection = entry.getValue();
               UUID uuid = entry.getKey();
               executor.execute(() -> this.releasePlayerLock(connection, uuid));
            }
         }

         executor.shutdown();

         try {
            if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
               executor.shutdownNow();
            }
         } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
         }

         this.databaseExecutor = null;
         this.initializing = false;
      }
   }

   private static final class ItemArrayCodec {
      private final Method toNbt;
      private final Method fromNbt;
      private final Constructor<?> containerConstructor;
      private final Method toString;

      private ItemArrayCodec() throws Exception {
         Plugin plugin = Bukkit.getPluginManager().getPlugin("NBTAPI");
         if (plugin != null && plugin.isEnabled()) {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> nbtItem = Class.forName("de.tr7zw.nbtapi.NBTItem", true, loader);
            Class<?> nbtCompound = Class.forName("de.tr7zw.nbtapi.NBTCompound", true, loader);
            Class<?> nbtContainer = Class.forName("de.tr7zw.nbtapi.NBTContainer", true, loader);
            this.toNbt = nbtItem.getDeclaredMethod("convertItemArraytoNBT", ItemStack[].class);
            this.fromNbt = nbtItem.getDeclaredMethod("convertNBTtoItemArray", nbtCompound);
            this.containerConstructor = nbtContainer.getConstructor(String.class);
            this.toString = nbtContainer.getMethod("toString");
         } else {
            throw new IllegalStateException("NBTAPI ist nicht aktiviert.");
         }
      }

      private String serialize(ItemStack[] items) throws Exception {
         // Method.invoke is varargs: passing ItemStack[] directly expands it into
         // one reflection argument per slot. Keep the array as the single API argument.
         Object container = this.toNbt.invoke(null, (Object)items);
         String value = (String)this.toString.invoke(container);
         return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
      }

      private ItemStack[] deserialize(String value) throws Exception {
         String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
         Object container = this.containerConstructor.newInstance(decoded);
         return (ItemStack[])this.fromNbt.invoke(null, container);
      }
   }

   private enum Module {
      INVENTORY("inventory", "_inventory", "inventory LONGTEXT", new String[]{"inventory"}, "Inventar"),
      SELECTED_HOTBAR_SLOT("selected_hotbar_slot", "_selected_hotbar_slot", "slot INT", new String[]{"slot"}, "Hotbar-Slot"),
      ENDERCHEST("enderChest", "_enderchest", "enderchest LONGTEXT", new String[]{"enderchest"}, "Endertruhe"),
      ARMOR("amorSlots", "_armor", "armor LONGTEXT", new String[]{"armor"}, "Rüstung"),
      OFFHAND("offhand", "_offhand", "offhand LONGTEXT", new String[]{"offhand"}, "Nebenhand"),
      LOCATION(
         "location",
         "_location",
         "world TEXT, x DOUBLE, y DOUBLE, z DOUBLE, yaw FLOAT, pitch FLOAT",
         new String[]{"world", "x", "y", "z", "yaw", "pitch"},
         "Standort"
      ),
      GAMEMODE("gamemode", "_gamemode", "gamemode TEXT", new String[]{"gamemode"}, "Spielmodus"),
      EXP("exp", "_exp", "exp_level INT, exp FLOAT", new String[]{"exp_level", "exp"}, "Erfahrung"),
      HEALTH(
         "health",
         "_health",
         "health DOUBLE, max_health DOUBLE, health_scaled BOOLEAN, health_scale DOUBLE",
         new String[]{"health", "max_health", "health_scaled", "health_scale"},
         "Leben"
      ),
      SATURATION("saturation", "_saturation", "saturation FLOAT, food_level INT", new String[]{"saturation", "food_level"}, "Hunger"),
      EFFECTS("effects", "_potion_effects", "effects LONGTEXT", new String[]{"effects"}, "Effekte"),
      ADVANCEMENTS("advancements", "_advancements", "advancements LONGTEXT", new String[]{"advancements"}, "Erfolge"),
      STATISTICS("statistics", "_stats", "stats LONGTEXT", new String[]{"stats"}, "Statistiken"),
      MONEY("vaultEconomy", "_money", "money DOUBLE", new String[]{"money"}, "Vault-Geld"),
      KRISTALL("kristall", "_kristall", "crystals BIGINT", new String[]{"crystals"}, "Kristalle");

      private final String configPath;
      private final String suffix;
      private final String columns;
      private final String[] columnNames;
      private final String displayName;

      Module(String configPath, String suffix, String columns, String[] columnNames, String displayName) {
         this.configPath = "sync." + configPath;
         this.suffix = suffix;
         this.columns = columns;
         this.columnNames = columnNames;
         this.displayName = displayName;
      }
   }

   private record PlayerSnapshot(UUID uuid, String name, Map<MySqlPlayerBridgeFeature.Module, Map<String, Object>> data) {
   }
}

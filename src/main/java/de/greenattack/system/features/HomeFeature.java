package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class HomeFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
   private static final String VELOCITY_HOME_CHANNEL = "greenattack:home_connect";
   private File homesFile = this.featureFile("homes.yml");
   private FileConfiguration homes;
   private boolean databaseRequested;
   private boolean mysqlEnabled;
   private String jdbcUrl;
   private String databaseUser;
   private String databasePassword;
   private String databaseServerId;
   private String homesTable;

   public HomeFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "home");
      this.migrateLegacyHomes();
      this.homes = YamlConfiguration.loadConfiguration(this.homesFile);
      this.initializeDatabase();
   }

   public void reloadFeature() {
      super.reload();
      this.homes = YamlConfiguration.loadConfiguration(this.homesFile);
      this.initializeDatabase();
   }

   public void shutdownFeature() {
      if (this.mysqlEnabled) {
         this.plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(this.plugin, "greenattack:home_connect");
         this.mysqlEnabled = false;
      }
   }

   private void migrateLegacyHomes() {
      if (!this.homesFile.isFile()) {
         File pluginsFolder = this.plugin.getDataFolder().getParentFile();
         File legacyHomes = new File(new File(pluginsFolder, "Home"), "homes.yml");
         if (legacyHomes.isFile()) {
            try {
               Files.copy(legacyHomes.toPath(), this.homesFile.toPath());
               this.plugin.getLogger().info("Bestehende Home-Daten wurden nach features/home/homes.yml kopiert.");
            } catch (IOException exception) {
               this.plugin.getLogger().severe("Bestehende Home-Daten konnten nicht übernommen werden: " + exception.getMessage());
            }
         }
      }
   }

   private void initializeDatabase() {
      if (this.mysqlEnabled) {
         this.plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(this.plugin, "greenattack:home_connect");
         this.mysqlEnabled = false;
      }

      this.ensureDatabaseDefaults();
      this.databaseRequested = this.config.getBoolean("database.enabled", false);
      if (!this.databaseRequested) {
         this.plugin.getLogger().info("Home-Speicher läuft lokal. Für Homes zwischen Backends muss database.enabled auf allen Backends aktiviert sein.");
      } else {
         this.databaseServerId = this.config.getString("database.server-id", "").trim();
         this.databaseUser = this.config.getString("database.user", "").trim();
         this.databasePassword = this.config.getString("database.password", "");
         this.homesTable = this.config.getString("database.table-name", "greenattack_homes").trim();
         if (!this.homesTable.matches("[A-Za-z0-9_]{1,48}")) {
            this.plugin.getLogger().severe("Ungültiger Tabellenname in features/home/config.yml; MySQL-Home bleibt aus.");
         } else if (this.databaseServerId.isBlank()) {
            this.plugin.getLogger().severe("database.server-id fehlt in features/home/config.yml; trage dort exakt den Velocity-Servernamen ein.");
         } else if (this.databaseUser.isBlank()) {
            this.plugin.getLogger().severe("database.user fehlt in features/home/config.yml; MySQL-Home bleibt aus.");
         } else {
            this.jdbcUrl = this.config.getString("database.jdbc-url", "").trim();
            if (this.jdbcUrl.isEmpty()) {
               String host = this.config.getString("database.host", "").trim();
               String database = this.config.getString("database.name", "").trim();
               if (host.isEmpty() || database.isEmpty()) {
                  this.plugin.getLogger().severe("database.host und database.name fehlen in features/home/config.yml; MySQL-Home bleibt aus.");
                  return;
               }

               int port = Math.max(1, Math.min(65535, this.config.getInt("database.port", 3306)));
               int timeout = Math.max(1000, this.config.getInt("database.connection-timeout-ms", 3000));
               this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?connectTimeout=" + timeout + "&socketTimeout=5000&autoReconnect=true";
            }

            try {
               Class.forName(this.jdbcUrl.startsWith("jdbc:mariadb:") ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver");

               try (Connection connection = this.openDatabaseConnection()) {
                  this.createHomesTable(connection);
                  this.migrateLocalHomes(connection);
               }

               this.mysqlEnabled = true;
               this.plugin.getServer().getMessenger().registerOutgoingPluginChannel(this.plugin, "greenattack:home_connect");
               this.plugin.getLogger().info("MySQL-Home ist bereit für Backend '" + this.databaseServerId + "' mit Tabelle '" + this.homesTable + "'.");
            } catch (Exception exception) {
               this.plugin.getLogger().severe("MySQL-Home konnte nicht gestartet werden: " + exception.getMessage());
            }
         }
      }
   }

   private void ensureDatabaseDefaults() {
      boolean changed = false;
      Map<String, Object> defaults = Map.ofEntries(
         Map.entry("database.enabled", false),
         Map.entry("database.jdbc-url", ""),
         Map.entry("database.host", ""),
         Map.entry("database.port", 3306),
         Map.entry("database.name", ""),
         Map.entry("database.user", ""),
         Map.entry("database.password", ""),
         Map.entry("database.server-id", ""),
         Map.entry("database.table-name", "greenattack_homes"),
         Map.entry("database.connection-timeout-ms", 3000),
         Map.entry("messages.database-error", "&cDeine Homes sind gerade nicht verfügbar. Bitte versuche es später erneut."),
         Map.entry("messages.home-not-found", "&cHome &e%home% &cvon &e%player% &cwurde nicht gefunden."),
         Map.entry("messages.home-not-found-local", "&cHome &e%home% &cvon &e%player% &cwurde nicht gefunden."),
         Map.entry("messages.remote-home", "&eDieses Home liegt auf &6%server%&e. Verbinde dich dorthin über Velocity.")
      );

      for (Entry<String, Object> entry : defaults.entrySet()) {
         if (!this.config.contains(entry.getKey())) {
            this.config.set(entry.getKey(), entry.getValue());
            changed = true;
         }
      }

      changed |= this.replaceLegacyMessage(
         "messages.database-error",
         "&cDie Home-Datenbank ist gerade nicht erreichbar. Bitte Admin informieren.",
         "&cDeine Homes sind gerade nicht verfügbar. Bitte versuche es später erneut."
      );
      changed |= this.replaceLegacyMessage(
         "messages.home-not-found",
         "&cHome &e%home% &cvon &e%player% &cwurde in der gemeinsamen Home-Datenbank nicht gefunden.",
         "&cHome &e%home% &cvon &e%player% &cwurde nicht gefunden."
      );
      changed |= this.replaceLegacyMessage(
         "messages.home-not-found-local",
         "&cHome &e%home% &cvon &e%player% &cwurde auf diesem Backend nicht gefunden. &7Für serverübergreifende Homes aktiviere database.enabled und nutze auf allen Backends dieselbe Datenbank und Tabelle.",
         "&cHome &e%home% &cvon &e%player% &cwurde nicht gefunden."
      );
      if (changed) {
         this.save();
      }
   }

   private boolean replaceLegacyMessage(String path, String previous, String replacement) {
      if (!this.config.getString(path, "").equals(previous)) {
         return false;
      }

      this.config.set(path, replacement);
      return true;
   }

   private Connection openDatabaseConnection() throws SQLException {
      return DriverManager.getConnection(this.jdbcUrl, this.databaseUser, this.databasePassword);
   }

   public Connection openSharedDatabaseConnection() throws SQLException {
      if (!this.mysqlEnabled) {
         throw new SQLException("Die gemeinsame Home-Datenbank ist nicht aktiviert oder nicht erreichbar.");
      } else {
         return this.openDatabaseConnection();
      }
   }

   private void ensureDatabaseAvailable() {
      if (this.databaseRequested && !this.mysqlEnabled) {
         throw new HomeFeature.HomeStorageException(new SQLException("Die konfigurierte Datenbankverbindung ist nicht verfügbar."));
      }
   }

   private void createHomesTable(Connection connection) throws SQLException {
      String sql = "CREATE TABLE IF NOT EXISTS `"
         + this.homesTable
         + "` (`owner_uuid` CHAR(36) NOT NULL, `owner_name` VARCHAR(32) NOT NULL, `home_name` VARCHAR(32) NOT NULL, `server_id` VARCHAR(64) NOT NULL, `world_name` VARCHAR(128) NOT NULL, `x` DOUBLE NOT NULL, `y` DOUBLE NOT NULL, `z` DOUBLE NOT NULL, `yaw` FLOAT NOT NULL, `pitch` FLOAT NOT NULL, `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, PRIMARY KEY (`owner_uuid`, `home_name`), INDEX `idx_greenattack_home_owner_name` (`owner_name`)) ENGINE=InnoDB";

      try (Statement statement = connection.createStatement()) {
         statement.executeUpdate(sql);
      }
   }

   private void migrateLocalHomes(Connection connection) throws SQLException, IOException {
      ConfigurationSection players = this.homes.getConfigurationSection("players");
      if (players != null && this.homesFile.isFile()) {
         String sql = "INSERT IGNORE INTO `"
            + this.homesTable
            + "` (`owner_uuid`,`owner_name`,`home_name`,`server_id`,`world_name`,`x`,`y`,`z`,`yaw`,`pitch`) VALUES (?,?,?,?,?,?,?,?,?,?)";
         int migrated = 0;

         try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Iterator backup = players.getKeys(false).iterator();

            label75:
            while (true) {
               String uuidText;
               UUID uuid;
               while (true) {
                  if (!backup.hasNext()) {
                     statement.executeBatch();
                     break label75;
                  }

                  uuidText = (String)backup.next();

                  try {
                     uuid = UUID.fromString(uuidText);
                     break;
                  } catch (IllegalArgumentException ignored) {
                  }
               }

               OfflinePlayer owner = Bukkit.getOfflinePlayer(uuid);
               String ownerName = owner.getName() == null ? uuidText : owner.getName();
               ConfigurationSection locations = this.homes.getConfigurationSection("players." + uuidText + ".locations");
               if (locations != null) {
                  for (String homeName : locations.getKeys(false)) {
                     Location location = this.homes.getLocation("players." + uuidText + ".locations." + homeName);
                     if (location != null && location.getWorld() != null) {
                        bindHome(statement, uuid, ownerName, homeName, this.databaseServerId, location);
                        statement.addBatch();
                        migrated++;
                     }
                  }
               }
            }
         }

         if (migrated != 0) {
            String backupName = "homes.local-backup.yml";
            File backup = this.featureFile(backupName);
            if (backup.exists()) {
               backup = this.featureFile("homes.local-backup-" + System.currentTimeMillis() + ".yml");
            }

            Files.move(this.homesFile.toPath(), backup.toPath());
            this.plugin
               .getLogger()
               .info(migrated + " lokale Homes wurden in MySQL übernommen; die alte Datei liegt als " + backup.getName() + " im Home-Feature-Ordner.");
         }
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Home-Feature ist deaktiviert.");
         return true;
      }

      if (sender instanceof Player player) {
         String permissionKey = switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "home" -> "home";
            case "sethome", "delhome", "renamehome" -> "set";
            case "homeadmin" -> "admin";
            default -> "home";
         };

         String fallbackPermission = switch (permissionKey) {
            case "set" -> "system.home.set";
            case "admin" -> "system.home.admin";
            default -> "system.home";
         };
         if (!this.checkPermission(player, permissionKey, fallbackPermission)) {
            return true;
         }

         try {
            return switch (command.getName().toLowerCase(Locale.ROOT)) {
               case "home" -> this.commandHome(player, args);
               case "sethome" -> this.setHome(player, args);
               case "delhome" -> this.deleteHome(player, args);
               case "renamehome" -> this.renameHome(player, args);
               case "homeadmin" -> this.commandAdmin(player, args);
               default -> true;
            };
         } catch (HomeFeature.HomeStorageException exception) {
            this.plugin.getLogger().warning("Home-Datenbankabfrage fehlgeschlagen: " + exception.getCause().getMessage());
            this.sendPath(player, "messages.database-error", "&cDeine Homes sind gerade nicht verfügbar. Bitte versuche es später erneut.");
            return true;
         }
      } else {
         this.sendHomeText(sender, "player-only", "&cDieser Befehl kann nur von Spielern verwendet werden.");
         return true;
      }
   }

   private boolean commandHome(Player player, String[] args) {
      if (args.length == 0) {
         List<String> names = this.getNames(player.getUniqueId());
         if (names.isEmpty()) {
            this.sendHomeText(player, "list-empty", "&eDu hast noch keine Homes. Setze eines mit /sethome.");
         } else {
            this.sendHomeText(player, "list", "&aDeine Homes: &e" + String.join(", ", names));
            this.sendHomeText(player, "list-usage", "&7Teleportieren: /home <name>");
         }

         return true;
      } else if (args.length >= 2) {
         return !this.checkPermission(player, "admin", "system.home.admin") ? true : this.adminTeleport(player, args);
      } else if (args.length != 1) {
         this.sendHomeText(player, "usage-home", "&eVerwendung: /home [name]");
         return true;
      } else {
         return this.teleportHome(player, args[0]);
      }
   }

   private boolean commandAdmin(Player player, String[] args) {
      if (!this.checkPermission(player, "admin", "system.home.admin")) {
         return true;
      } else if (args.length == 0) {
         this.sendHomeText(player, "usage-homeadmin", "&eBenutzung: /homeadmin <spieler> [home] | /homeadmin set|remove <spieler> <home>");
         return true;
      } else if (args.length > 3) {
         this.sendHomeText(player, "usage-homeadmin-short", "&eBenutzung: /homeadmin [set|remove] <spieler> [home]");
         return true;
      } else {
         return this.adminTeleport(player, args);
      }
   }

   private boolean setHome(Player player, String[] args) {
      if (args.length > 1) {
         this.sendHomeText(player, "usage-sethome", "&eVerwendung: /sethome [name]");
         return true;
      }

      String name = this.normalize(args.length == 0 ? "home" : args[0]);
      if (name == null) {
         return this.invalidName(player);
      }

      this.ensureDatabaseAvailable();
      List<String> names = this.getNames(player.getUniqueId());
      int maximum = this.getMaxHomes(player);
      if (!names.contains(name) && names.size() >= maximum) {
         this.sendHomeText(player, "home-limit", "&cDu hast das Limit von &e%limit% &cHomes erreicht!", Map.of("%limit%", Integer.toString(maximum)));
         return true;
      }

      if (this.mysqlEnabled) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO "
                  + this.homesTable
                  + " (owner_uuid,owner_name,home_name,server_id,world_name,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE owner_name=VALUES(owner_name),server_id=VALUES(server_id),world_name=VALUES(world_name),x=VALUES(x),y=VALUES(y),z=VALUES(z),yaw=VALUES(yaw),pitch=VALUES(pitch)"
            );
         ) {
            bindHome(statement, player.getUniqueId(), player.getName(), name, this.databaseServerId, player.getLocation());
            statement.executeUpdate();
         } catch (SQLException exception) {
            throw new HomeFeature.HomeStorageException(exception);
         }
      } else {
         this.homes.set(this.path(player, "locations." + name), player.getLocation());
         if (!names.contains(name)) {
            names.add(name);
         }

         this.homes.set(this.path(player, "list"), names);
         this.saveHomes();
      }

      this.sendHomeText(player, "home-set", "&aHome &e%home% &awurde gesetzt.", Map.of("%home%", name));
      return true;
   }

   private boolean teleportHome(Player player, String inputName) {
      String name = this.normalize(inputName);
      return name == null ? this.invalidName(player) : this.teleportHome(player, player.getUniqueId(), player.getName(), name, false);
   }

   private boolean teleportHome(Player player, UUID ownerId, String ownerName, String name, boolean admin) {
      HomeFeature.HomeEntry entry = this.findHome(ownerId, name);
      if (entry == null) {
         String path = this.mysqlEnabled ? "messages.home-not-found" : "messages.home-not-found-local";
         String fallback = "&cHome &e%home% &cvon &e%player% &cwurde nicht gefunden.";
         this.sendHomeText(
            player, path.substring("messages.".length()), fallback, Map.of("%home%", name, "%player%", ownerName == null ? ownerId.toString() : ownerName)
         );
         return true;
      }

      if (this.mysqlEnabled && !entry.serverId().equalsIgnoreCase(this.databaseServerId)) {
         String backendCommand = admin ? "/homeadmin " + this.safeCommandName(ownerName) + " " + name : "/home " + name;
         if (!this.requestVelocityConnect(player, entry.serverId(), backendCommand)) {
            this.sendHomeText(player, "velocity-failed", "&cDer Serverwechsel konnte nicht gestartet werden.");
            return true;
         } else {
            this.send(
               player, this.config.getString("messages.remote-home", "&eVerbinde dich mit &6%server% &efür dein Home.").replace("%server%", entry.serverId())
            );
            return true;
         }
      } else {
         Location location = this.toLocation(entry);
         if (location == null) {
            this.sendHomeText(player, "world-unavailable", "&cDie Welt dieses Homes ist gerade nicht verfügbar.");
            return true;
         }

         player.teleport(location);
         if (admin) {
            this.sendHomeText(
               player, "teleport-admin-success", "&aDu wurdest zu &e%player%s &aHome &e%home% &ateleportiert.", Map.of("%player%", ownerName, "%home%", name)
            );
         } else {
            this.sendHomeText(player, "teleport-success", "&aDu wurdest zu deinem Home &e%home% &ateleportiert.", Map.of("%home%", name));
         }

         return true;
      }
   }

   private boolean deleteHome(Player player, String[] args) {
      if (args.length > 1) {
         this.sendHomeText(player, "usage-delhome", "&eVerwendung: /delhome [name]");
         return true;
      }

      String name = this.normalize(args.length == 0 ? "home" : args[0]);
      if (name == null) {
         return this.invalidName(player);
      }

      this.ensureDatabaseAvailable();
      List<String> names = this.getNames(player.getUniqueId());
      boolean removed;
      if (this.mysqlEnabled) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement("DELETE FROM " + this.homesTable + " WHERE owner_uuid=? AND home_name=?");
         ) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, name);
            removed = statement.executeUpdate() > 0;
         } catch (SQLException exception) {
            throw new HomeFeature.HomeStorageException(exception);
         }
      } else {
         removed = names.remove(name);
         if (removed) {
            this.homes.set(this.path(player, "locations." + name), null);
            this.homes.set(this.path(player, "list"), names);
            this.saveHomes();
         }
      }

      if (!removed) {
         this.sendHomeText(player, "home-delete-not-found", "&cHome &e%home% &cexistiert nicht.", Map.of("%home%", name));
         return true;
      } else {
         this.sendHomeText(player, "home-deleted", "&aHome &e%home% &awurde gelöscht.", Map.of("%home%", name));
         return true;
      }
   }

   private boolean renameHome(Player player, String[] args) {
      if (args.length != 2) {
         this.sendHomeText(player, "usage-renamehome", "&eVerwendung: /renamehome <alt> <neu>");
         return true;
      }

      String oldName = this.normalize(args[0]);
      String newName = this.normalize(args[1]);
      if (oldName != null && newName != null) {
         this.ensureDatabaseAvailable();
         List<String> names = this.getNames(player.getUniqueId());
         if (!names.contains(oldName)) {
            this.sendHomeText(player, "home-rename-not-found", "&cHome &e%home% &cexistiert nicht.", Map.of("%home%", oldName));
            return true;
         }

         if (!oldName.equals(newName) && names.contains(newName)) {
            this.sendHomeText(player, "home-name-used", "&cEin Home mit dem Namen &e%home% &cexistiert bereits.", Map.of("%home%", newName));
            return true;
         }

         if (this.mysqlEnabled) {
            try (Connection connection = this.openDatabaseConnection()) {
               connection.setAutoCommit(false);
               HomeFeature.HomeEntry old = this.findHome(player.getUniqueId(), oldName);
               if (old == null) {
                  connection.rollback();
                  this.sendHomeText(player, "home-rename-not-found", "&cDieses Home wurde nicht gefunden.");
                  return true;
               }

               try (
                  PreparedStatement delete = connection.prepareStatement("DELETE FROM " + this.homesTable + " WHERE owner_uuid=? AND home_name=?");
                  PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + this.homesTable + " (owner_uuid,owner_name,home_name,server_id,world_name,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?,?,?)"
                  );
               ) {
                  delete.setString(1, player.getUniqueId().toString());
                  delete.setString(2, oldName);
                  delete.executeUpdate();
                  insert.setString(1, player.getUniqueId().toString());
                  insert.setString(2, player.getName());
                  insert.setString(3, newName);
                  insert.setString(4, old.serverId());
                  insert.setString(5, old.worldName());
                  insert.setDouble(6, old.x());
                  insert.setDouble(7, old.y());
                  insert.setDouble(8, old.z());
                  insert.setFloat(9, old.yaw());
                  insert.setFloat(10, old.pitch());
                  insert.executeUpdate();
                  connection.commit();
               } catch (SQLException exception) {
                  connection.rollback();
                  throw exception;
               }
            } catch (SQLException exception) {
               throw new HomeFeature.HomeStorageException(exception);
            }
         } else {
            Location location = this.homes.getLocation(this.path(player, "locations." + oldName));
            this.homes.set(this.path(player, "locations." + oldName), null);
            this.homes.set(this.path(player, "locations." + newName), location);
            names.remove(oldName);
            if (!names.contains(newName)) {
               names.add(newName);
            }

            this.homes.set(this.path(player, "list"), names);
            this.saveHomes();
         }

         this.sendHomeText(player, "home-renamed", "&aHome &e%old% &awurde zu &e%new% &aumbenannt.", Map.of("%old%", oldName, "%new%", newName));
         return true;
      } else {
         return this.invalidName(player);
      }
   }

   private boolean adminTeleport(Player player, String[] args) {
      if (!this.checkPermission(player, "admin", "system.home.admin")) {
         return true;
      }

      if (args.length <= 0 || !args[0].equalsIgnoreCase("set") && !args[0].equalsIgnoreCase("remove")) {
         if (args.length >= 1 && args.length <= 2) {
            UUID ownerId = this.resolveOwnerId(args[0]);
            if (ownerId == null) {
               this.sendHomeText(player, "player-not-found", "&cDieser Spieler wurde nicht gefunden.");
               return true;
            } else {
               String name = this.normalize(args.length == 1 ? "home" : args[1]);
               return name == null ? this.invalidName(player) : this.teleportHome(player, ownerId, args[0], name, true);
            }
         } else {
            this.sendHomeText(player, "usage-homeadmin", "&eBenutzung: /homeadmin <spieler> [home] | /homeadmin set|remove <spieler> <home>");
            return true;
         }
      } else if (args.length != 3) {
         this.sendHomeText(
            player, "usage-homeadmin-action", "&eBenutzung: /homeadmin %action% <spieler> <home>", Map.of("%action%", args[0].toLowerCase(Locale.ROOT))
         );
         return true;
      } else {
         return args[0].equalsIgnoreCase("set") ? this.adminSetHome(player, args[1], args[2]) : this.adminRemoveHome(player, args[1], args[2]);
      }
   }

   private boolean adminSetHome(Player admin, String targetName, String inputHomeName) {
      String homeName = this.normalize(inputHomeName);
      if (homeName == null) {
         return this.invalidName(admin);
      }

      this.ensureDatabaseAvailable();
      UUID ownerId = this.resolveAdminOwnerId(targetName);
      if (ownerId == null) {
         this.sendHomeText(admin, "admin-player-not-found", "&cDer Spieler &e%player% &cwurde nicht gefunden.", Map.of("%player%", targetName));
         return true;
      }

      if (this.mysqlEnabled) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO "
                  + this.homesTable
                  + " (owner_uuid,owner_name,home_name,server_id,world_name,x,y,z,yaw,pitch) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE owner_name=VALUES(owner_name),server_id=VALUES(server_id),world_name=VALUES(world_name),x=VALUES(x),y=VALUES(y),z=VALUES(z),yaw=VALUES(yaw),pitch=VALUES(pitch)"
            );
         ) {
            bindHome(statement, ownerId, targetName, homeName, this.databaseServerId, admin.getLocation());
            statement.executeUpdate();
         } catch (SQLException exception) {
            throw new HomeFeature.HomeStorageException(exception);
         }
      } else {
         List<String> names = this.getNames(ownerId);
         if (!names.contains(homeName)) {
            names.add(homeName);
         }

         this.homes.set("players." + ownerId + ".locations." + homeName, admin.getLocation());
         this.homes.set("players." + ownerId + ".list", names);
         this.saveHomes();
      }

      this.sendHomeText(
         admin, "admin-home-set", "&aHome &e%home% &afür &e%player% &awurde an deiner Position gesetzt.", Map.of("%home%", homeName, "%player%", targetName)
      );
      return true;
   }

   private boolean adminRemoveHome(Player admin, String targetName, String inputHomeName) {
      String homeName = this.normalize(inputHomeName);
      if (homeName == null) {
         return this.invalidName(admin);
      }

      this.ensureDatabaseAvailable();
      UUID ownerId = this.resolveAdminOwnerId(targetName);
      if (ownerId == null) {
         this.sendHomeText(admin, "admin-player-not-found", "&cDer Spieler &e%player% &cwurde nicht gefunden.", Map.of("%player%", targetName));
         return true;
      }

      boolean removed;
      if (this.mysqlEnabled) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement("DELETE FROM " + this.homesTable + " WHERE owner_uuid=? AND home_name=?");
         ) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, homeName);
            removed = statement.executeUpdate() > 0;
         } catch (SQLException exception) {
            throw new HomeFeature.HomeStorageException(exception);
         }
      } else {
         List<String> names = this.getNames(ownerId);
         removed = names.remove(homeName);
         if (removed) {
            this.homes.set("players." + ownerId + ".locations." + homeName, null);
            this.homes.set("players." + ownerId + ".list", names);
            this.saveHomes();
         }
      }

      if (!removed) {
         this.sendHomeText(
            admin, "admin-home-not-found", "&cHome &e%home% &cvon &e%player% &cwurde nicht gefunden.", Map.of("%home%", homeName, "%player%", targetName)
         );
         return true;
      } else {
         this.sendHomeText(
            admin, "admin-home-removed", "&aHome &e%home% &avon &e%player% &awurde entfernt.", Map.of("%home%", homeName, "%player%", targetName)
         );
         return true;
      }
   }

   private UUID resolveAdminOwnerId(String value) {
      UUID ownerId = this.resolveOwnerId(value);
      if (ownerId != null) {
         return ownerId;
      }

      Player online = Bukkit.getPlayerExact(value);
      if (online != null) {
         return online.getUniqueId();
      }

      OfflinePlayer offline = Bukkit.getOfflinePlayer(value);
      return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (sender instanceof Player player) {
         String name = command.getName().toLowerCase(Locale.ROOT);

         try {
            if ((name.equals("home") || name.equals("delhome") || name.equals("renamehome")) && args.length == 1) {
               return this.prefix(this.getNames(player.getUniqueId()), args[0]);
            }

            if (name.equals("sethome") && args.length == 1) {
               return this.prefix(List.of("home"), args[0]);
            }

            if (name.equals("homeadmin")) {
               if (args.length == 1) {
                  List<String> choices = new ArrayList<>(List.of("set", "remove"));
                  choices.addAll(this.adminPlayerNames(args[0]));
                  return this.prefix(choices, args[0]);
               }

               if (args.length == 2) {
                  if (!args[0].equalsIgnoreCase("set") && !args[0].equalsIgnoreCase("remove")) {
                     UUID ownerId = this.resolveOwnerId(args[0]);
                     return ownerId == null ? List.of() : this.prefix(this.getNames(ownerId), args[1]);
                  }

                  return this.adminPlayerNames(args[1]);
               }

               if (args.length == 3 && args[0].equalsIgnoreCase("remove")) {
                  UUID ownerId = this.resolveAdminOwnerId(args[1]);
                  return ownerId == null ? List.of() : this.prefix(this.getNames(ownerId), args[2]);
               }
            }
         } catch (HomeFeature.HomeStorageException exception) {
            this.plugin.getLogger().fine("Home-Tabvervollständigung nicht verfügbar: " + exception.getMessage());
         }

         return List.of();
      } else {
         return List.of();
      }
   }

   private List<String> adminPlayerNames(String partial) {
      TreeSet<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      if (this.mysqlEnabled) {
         candidates.addAll(this.lookupOwnerNames(partial));
      }

      for (Player online : Bukkit.getOnlinePlayers()) {
         candidates.add(online.getName());
      }

      return this.prefix(new ArrayList<>(candidates), partial);
   }

   private List<String> getNames(UUID ownerId) {
      this.ensureDatabaseAvailable();
      if (!this.mysqlEnabled) {
         return new ArrayList<>(this.homes.getStringList("players." + ownerId + ".list"));
      }

      List<String> names = new ArrayList<>();

      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement("SELECT home_name FROM " + this.homesTable + " WHERE owner_uuid=? ORDER BY home_name");
      ) {
         statement.setString(1, ownerId.toString());

         try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
               names.add(result.getString(1));
            }
         }

         return names;
      } catch (SQLException exception) {
         throw new HomeFeature.HomeStorageException(exception);
      }
   }

   private HomeFeature.HomeEntry findHome(UUID ownerId, String homeName) {
      this.ensureDatabaseAvailable();
      if (!this.mysqlEnabled) {
         Location location = this.homes.getLocation("players." + ownerId + ".locations." + homeName);
         if (location != null && location.getWorld() != null) {
            String ownerName = Bukkit.getOfflinePlayer(ownerId).getName();
            return fromLocation(
               ownerId, ownerName == null ? ownerId.toString() : ownerName, homeName, this.databaseServerId == null ? "" : this.databaseServerId, location
            );
         } else {
            return null;
         }
      } else {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "SELECT owner_name,server_id,world_name,x,y,z,yaw,pitch FROM " + this.homesTable + " WHERE owner_uuid=? AND home_name=?"
            );
         ) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, homeName);

            try (ResultSet result = statement.executeQuery()) {
               return !result.next()
                  ? null
                  : new HomeFeature.HomeEntry(
                     ownerId,
                     result.getString("owner_name"),
                     homeName,
                     result.getString("server_id"),
                     result.getString("world_name"),
                     result.getDouble("x"),
                     result.getDouble("y"),
                     result.getDouble("z"),
                     result.getFloat("yaw"),
                     result.getFloat("pitch")
                  );
            }
         } catch (SQLException exception) {
            throw new HomeFeature.HomeStorageException(exception);
         }
      }
   }

   private UUID resolveOwnerId(String value) {
      try {
         return UUID.fromString(value);
      } catch (IllegalArgumentException ignored) {
         if (this.mysqlEnabled) {
            try (
               Connection connection = this.openDatabaseConnection();
               PreparedStatement statement = connection.prepareStatement(
                  "SELECT owner_uuid FROM " + this.homesTable + " WHERE LOWER(owner_name)=LOWER(?) ORDER BY updated_at DESC LIMIT 1"
               );
            ) {
               statement.setString(1, value);

               try (ResultSet result = statement.executeQuery()) {
                  return result.next() ? UUID.fromString(result.getString(1)) : null;
               }
            } catch (SQLException exception) {
               throw new HomeFeature.HomeStorageException(exception);
            }
         } else {
            Player online = Bukkit.getPlayerExact(value);
            OfflinePlayer offline = (OfflinePlayer)(online == null ? Bukkit.getOfflinePlayer(value) : online);
            return online == null && !offline.hasPlayedBefore() ? null : offline.getUniqueId();
         }
      }
   }

   private List<String> lookupOwnerNames(String partial) {
      List<String> names = new ArrayList<>();

      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement(
            "SELECT DISTINCT owner_name FROM " + this.homesTable + " WHERE owner_name LIKE ? ORDER BY owner_name LIMIT 50"
         );
      ) {
         statement.setString(1, partial + "%");

         try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
               names.add(result.getString(1));
            }
         }

         return names;
      } catch (SQLException exception) {
         throw new HomeFeature.HomeStorageException(exception);
      }
   }

   private static HomeFeature.HomeEntry fromLocation(UUID ownerId, String ownerName, String homeName, String serverId, Location location) {
      return new HomeFeature.HomeEntry(
         ownerId,
         ownerName,
         homeName,
         serverId,
         location.getWorld().getName(),
         location.getX(),
         location.getY(),
         location.getZ(),
         location.getYaw(),
         location.getPitch()
      );
   }

   private Location toLocation(HomeFeature.HomeEntry entry) {
      World world = Bukkit.getWorld(entry.worldName());
      if (world == null) {
         world = Bukkit.createWorld(new WorldCreator(entry.worldName()));
      }

      return world == null ? null : new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), entry.pitch());
   }

   private static void bindHome(PreparedStatement statement, UUID ownerId, String ownerName, String homeName, String serverId, Location location) throws SQLException {
      statement.setString(1, ownerId.toString());
      statement.setString(2, ownerName);
      statement.setString(3, homeName);
      statement.setString(4, serverId);
      statement.setString(5, location.getWorld().getName());
      statement.setDouble(6, location.getX());
      statement.setDouble(7, location.getY());
      statement.setDouble(8, location.getZ());
      statement.setFloat(9, location.getYaw());
      statement.setFloat(10, location.getPitch());
   }

   private boolean requestVelocityConnect(Player player, String serverName, String command) {
      if (serverName != null && serverName.matches("[A-Za-z0-9_-]{1,64}") && command.length() <= 256 && command.indexOf(10) < 0 && command.indexOf(13) < 0) {
         try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            try (DataOutputStream output = new DataOutputStream(bytes)) {
               output.writeUTF("connect");
               output.writeUTF(player.getUniqueId().toString());
               output.writeUTF(player.getUniqueId().toString());
               output.writeUTF(serverName);
               output.writeUTF(command);
            }

            player.sendPluginMessage(this.plugin, "greenattack:home_connect", bytes.toByteArray());
            return true;
         } catch (IOException exception) {
            this.plugin.getLogger().warning("Velocity-Transfernachricht konnte nicht erstellt werden: " + exception.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   private int getMaxHomes(Player player) {
      int maximum = 1;
      String permissionPrefix = this.config.getString("permissions.max-homes-prefix", "system.home.");

      for (int i = 1; i <= 100; i++) {
         if (this.hasFeaturePermission(player, permissionPrefix + i)) {
            maximum = i;
         }
      }

      return maximum;
   }

   private boolean checkPermission(Player player, String path, String fallback) {
      String permission = this.config.getString("permissions." + path, fallback);
      if (permission == null || permission.isBlank()) {
         permission = fallback;
      }

      if (this.hasFeaturePermission(player, permission)) {
         return true;
      }

      this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
      return false;
   }

   private String path(OfflinePlayer player, String suffix) {
      return "players." + player.getUniqueId() + "." + suffix;
   }

   private void saveHomes() {
      try {
         this.homes.save(this.homesFile);
      } catch (IOException exception) {
         this.plugin.getLogger().severe("Die Home-Daten konnten nicht gespeichert werden: " + exception.getMessage());
      }
   }

   private OfflinePlayer resolveOwner(String value) {
      try {
         return Bukkit.getOfflinePlayer(UUID.fromString(value));
      } catch (IllegalArgumentException ignored) {
         Player online = Bukkit.getPlayerExact(value);
         return (OfflinePlayer)(online == null ? Bukkit.getOfflinePlayer(value) : online);
      }
   }

   private String safePlayerName(OfflinePlayer player) {
      String name = player.getName();
      return name == null ? player.getUniqueId().toString().substring(0, 8) : name;
   }

   private String normalize(String name) {
      return name != null && VALID_NAME.matcher(name).matches() ? name.toLowerCase(Locale.ROOT) : null;
   }

   private boolean invalidName(Player player) {
      this.sendHomeText(player, "invalid-name", "&cDer Home-Name darf nur Buchstaben, Zahlen, _ und - enthalten (1-32 Zeichen).");
      return true;
   }

   private void sendHomeText(CommandSender sender, String key, String fallback) {
      this.sendHomeText(sender, key, fallback, Map.of());
   }

   private void sendHomeText(CommandSender sender, String key, String fallback, Map<String, String> values) {
      String path = "messages." + key;
      if (!this.config.contains(path)) {
         this.config.set(path, fallback);
         this.save();
      }

      String output = this.config.getString(path, fallback);

      for (Entry<String, String> entry : values.entrySet()) {
         output = output.replace(entry.getKey(), entry.getValue());
      }

      this.send(sender, output);
   }

   private String safeCommandName(String value) {
      return value != null && value.matches("[A-Za-z0-9_-]{1,36}") ? value : "unknown";
   }

   private List<String> prefix(List<String> values, String query) {
      String lower = query.toLowerCase(Locale.ROOT);
      return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
   }

   private record HomeEntry(
      UUID ownerId, String ownerName, String homeName, String serverId, String worldName, double x, double y, double z, float yaw, float pitch
   ) {
   }

   private static final class HomeStorageException extends RuntimeException {
      private HomeStorageException(Throwable cause) {
         super(cause);
      }
   }
}

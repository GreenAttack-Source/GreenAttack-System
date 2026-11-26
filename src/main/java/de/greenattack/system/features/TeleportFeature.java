package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

public final class TeleportFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener, PluginMessageListener {
   private static final DateTimeFormatter LOOKUP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
   private final Map<UUID, TeleportFeature.TeleportRequest> requestsByReceiver = new HashMap<>();
   private final Set<UUID> autoAccept = new HashSet<>();
   private final Map<UUID, String> knownOnlineNames = new ConcurrentHashMap<>();
   private final Map<String, TeleportFeature.NetworkPlayer> proxyPlayersByName = new ConcurrentHashMap<>();
   private final Map<UUID, TeleportFeature.NetworkPlayer> proxyPlayersById = new ConcurrentHashMap<>();
   private final Set<UUID> proxyPendingReceivers = ConcurrentHashMap.newKeySet();
   private boolean databaseRequested;
   private boolean mysqlEnabled;
   private String jdbcUrl;
   private String databaseUser;
   private String databasePassword;
   private String serverId;
   private BukkitTask presenceTask;
   private BukkitTask proxyPlayerListTask;
   private volatile long proxySnapshotAt;
   private static final String VELOCITY_HOME_CHANNEL = "greenattack:home_connect";

   public TeleportFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "teleportsystem");
      this.normalizePermissionSettings();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "greenattack:home_connect");
      plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "greenattack:home_connect", this);
      this.proxyPlayerListTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> this.requestProxyPlayerList(), 20L, 100L);
      this.initializeDatabase();
   }

   public void reloadFeature() {
      this.reload();
      this.normalizePermissionSettings();
      this.initializeDatabase();
   }

   private void normalizePermissionSettings() {
      boolean changed = false;
      changed |= this.addDefault("permissions.request", "system.tpa");
      changed |= this.addDefault("permissions.request-here", "system.tpa.here");
      changed |= this.addDefault("permissions.auto", "system.tpa.auto");
      changed |= this.addDefault("permissions.admin", "system.tpa.admin");
      changed |= this.addDefault("permissions.teleport", "system.tp");
      changed |= this.addDefault("permissions.teleport-here", "system.tp.here");
      changed |= this.addDefault("lookup.history-size", 100);
      changed |= this.addDefault("lookup.display-entries", 10);
      changed |= this.addDefault("database.enabled", false);
      changed |= this.addDefault("database.jdbc-url", "");
      changed |= this.addDefault("database.host", "");
      changed |= this.addDefault("database.port", 3306);
      changed |= this.addDefault("database.name", "");
      changed |= this.addDefault("database.user", "");
      changed |= this.addDefault("database.password", "");
      changed |= this.addDefault("database.server-id", "");
      changed |= this.addDefault("database.connection-timeout-ms", 3000);
      changed |= this.replaceLegacyMessage("messages.usage-accept", "&7Verwendung: &f/tpaccept", "&7Verwendung: &f/tpaaccept");
      changed |= this.replaceLegacyMessage("messages.usage-accept", "&7Verwendung: &f/tpa accept", "&7Verwendung: &f/tpaaccept");
      changed |= this.replaceLegacyMessage("messages.usage-cancel", "&7Verwendung: &f/tpa cancel", "&7Verwendung: &f/tpacancel");
      changed |= this.replaceLegacyMessage("messages.usage-auto", "&7Verwendung: &f/tpauto", "&7Verwendung: &f/tpaauto");
      changed |= this.replaceLegacyMessage("messages.usage-auto", "&7Verwendung: &f/tpa auto", "&7Verwendung: &f/tpaauto");
      changed |= this.replaceLegacyMessage("messages.lookup-usage", "&7Verwendung: &f/tpa lookup", "&7Verwendung: &f/tpalookup");
      changed |= this.replaceLegacyMessage(
         "messages.database-error",
         "&cDie Teleport-Datenbank ist nicht erreichbar. Bitte Admin informieren.",
         "&cDie Teleport-Anfrage ist gerade nicht verfügbar. Bitte versuche es später erneut."
      );
      String oldHerePermission = this.config.getString("permissions.teleport-here", "");
      if (oldHerePermission.equalsIgnoreCase("system.tphere") || oldHerePermission.equalsIgnoreCase("system.tp")) {
         this.config.set("permissions.teleport-here", "system.tp.here");
         changed = true;
      }

      if (changed) {
         this.save();
      }
   }

   private void initializeDatabase() {
      if (this.presenceTask != null) {
         this.presenceTask.cancel();
         this.presenceTask = null;
      }

      this.mysqlEnabled = false;
      this.databaseRequested = this.config.getBoolean("database.enabled", false);
      if (this.databaseRequested) {
         this.serverId = this.config.getString("database.server-id", "").trim();
         this.databaseUser = this.config.getString("database.user", "").trim();
         this.databasePassword = this.config.getString("database.password", "");
         if (!this.serverId.isBlank() && this.serverId.matches("[A-Za-z0-9_-]{1,64}") && !this.databaseUser.isBlank()) {
            this.jdbcUrl = this.config.getString("database.jdbc-url", "").trim();
            if (this.jdbcUrl.isEmpty()) {
               String host = this.config.getString("database.host", "").trim();
               String database = this.config.getString("database.name", "").trim();
               if (host.isEmpty() || database.isEmpty()) {
                  this.plugin.getLogger().severe("TPA/TP-Datenbank benötigt database.host und database.name.");
                  return;
               }

               int port = Math.max(1, Math.min(65535, this.config.getInt("database.port", 3306)));
               int timeout = Math.max(1000, Math.min(30000, this.config.getInt("database.connection-timeout-ms", 3000)));
               this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database + "?connectTimeout=" + timeout + "&socketTimeout=5000&autoReconnect=true";
            }

            try {
               Class.forName(this.jdbcUrl.startsWith("jdbc:mariadb:") ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver");

               try (Connection connection = this.openDatabaseConnection()) {
                  this.createTeleportTables(connection);

                  try (PreparedStatement clear = connection.prepareStatement("UPDATE greenattack_network_players SET is_online=FALSE WHERE server_id=?")) {
                     clear.setString(1, this.serverId);
                     clear.executeUpdate();
                  }
               }

               this.mysqlEnabled = true;

               for (Player online : Bukkit.getOnlinePlayers()) {
                  this.knownOnlineNames.put(online.getUniqueId(), online.getName());
                  this.updatePresence(online.getUniqueId(), online.getName(), true);
               }

               this.presenceTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, this::refreshPresence, 600L, 600L);
               this.plugin.getLogger().info("Serverübergreifende TPA/TP-Datenbank ist bereit für Backend '" + this.serverId + "'.");
            } catch (Exception exception) {
               this.plugin.getLogger().severe("TPA/TP-Datenbank konnte nicht gestartet werden: " + exception.getMessage());
            }
         } else {
            this.plugin.getLogger().severe("TPA/TP-Datenbank benötigt database.server-id und database.user in features/teleportsystem/config.yml.");
         }
      }
   }

   private Connection openDatabaseConnection() throws SQLException {
      return DriverManager.getConnection(this.jdbcUrl, this.databaseUser, this.databasePassword);
   }

   private void createTeleportTables(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement()) {
         statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS greenattack_network_players (player_uuid CHAR(36) NOT NULL PRIMARY KEY, player_name VARCHAR(32) NOT NULL, server_id VARCHAR(64) NOT NULL, is_online BOOLEAN NOT NULL, last_seen BIGINT NOT NULL, INDEX idx_gc_network_name (player_name), INDEX idx_gc_network_online (is_online,last_seen)) ENGINE=InnoDB"
         );
         statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS greenattack_tpa_requests (receiver_uuid CHAR(36) NOT NULL PRIMARY KEY, requester_uuid CHAR(36) NOT NULL, requester_name VARCHAR(32) NOT NULL, receiver_name VARCHAR(32) NOT NULL, requester_server VARCHAR(64) NOT NULL, here BOOLEAN NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL) ENGINE=InnoDB"
         );
         statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS greenattack_tpa_auto (player_uuid CHAR(36) NOT NULL PRIMARY KEY, enabled BOOLEAN NOT NULL) ENGINE=InnoDB"
         );
         statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS greenattack_teleport_history (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, event_time BIGINT NOT NULL, player_name VARCHAR(32) NOT NULL, by_name VARCHAR(32) NOT NULL, target_name VARCHAR(32) NOT NULL, teleport_type VARCHAR(32) NOT NULL, INDEX idx_gc_tp_history_time (event_time)) ENGINE=InnoDB"
         );
         statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS greenattack_tp_transfers (token CHAR(36) NOT NULL PRIMARY KEY, mover_uuid CHAR(36) NOT NULL, destination_uuid CHAR(36) NOT NULL, actor_name VARCHAR(32) NOT NULL, teleport_type VARCHAR(32) NOT NULL, expires_at BIGINT NOT NULL) ENGINE=InnoDB"
         );
      }
   }

   private void ensureDatabaseAvailable() {
      if (this.databaseRequested && !this.mysqlEnabled) {
         throw new TeleportFeature.TeleportStorageException(new SQLException("Die konfigurierte Datenbank ist nicht verfügbar."));
      }
   }

   private void refreshPresence() {
      if (this.mysqlEnabled && !this.knownOnlineNames.isEmpty()) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "UPDATE greenattack_network_players SET last_seen=? WHERE player_uuid=? AND server_id=? AND is_online=TRUE"
            );
         ) {
            long now = System.currentTimeMillis();

            for (Entry<UUID, String> entry : this.knownOnlineNames.entrySet()) {
               statement.setLong(1, now);
               statement.setString(2, entry.getKey().toString());
               statement.setString(3, this.serverId);
               statement.addBatch();
            }

            statement.executeBatch();
         } catch (SQLException exception) {
            this.plugin.getLogger().warning("Netzwerk-Spielerstatus konnte nicht aktualisiert werden: " + exception.getMessage());
         }
      }
   }

   private void updatePresence(UUID playerId, String playerName, boolean online) {
      if (this.mysqlEnabled) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO greenattack_network_players (player_uuid,player_name,server_id,is_online,last_seen) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),server_id=VALUES(server_id),is_online=VALUES(is_online),last_seen=VALUES(last_seen)"
            );
         ) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerName);
            statement.setString(3, this.serverId);
            statement.setBoolean(4, online);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
         } catch (SQLException exception) {
            this.plugin.getLogger().warning("Netzwerk-Spielerstatus konnte nicht gespeichert werden: " + exception.getMessage());
         }
      }
   }

   private void markPresenceOffline(UUID playerId) {
      if (this.mysqlEnabled) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "UPDATE greenattack_network_players SET is_online=FALSE,last_seen=? WHERE player_uuid=? AND server_id=?"
            );
         ) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, playerId.toString());
            statement.setString(3, this.serverId);
            statement.executeUpdate();
         } catch (SQLException exception) {
            this.plugin.getLogger().warning("Spielerstatus konnte beim Verlassen nicht aktualisiert werden: " + exception.getMessage());
         }
      }
   }

   public void shutdownFeature() {
      if (this.presenceTask != null) {
         this.presenceTask.cancel();
      }

      if (this.proxyPlayerListTask != null) {
         this.proxyPlayerListTask.cancel();
      }

      this.plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(this.plugin, "greenattack:home_connect");
      this.plugin.getServer().getMessenger().unregisterIncomingPluginChannel(this.plugin, "greenattack:home_connect", this);
      this.mysqlEnabled = false;
   }

   private boolean addDefault(String path, Object value) {
      if (this.config.contains(path)) {
         return false;
      }

      this.config.set(path, value);
      return true;
   }

   private boolean replaceLegacyMessage(String path, String previous, String replacement) {
      if (!this.config.getString(path, "").equals(previous)) {
         return false;
      }

      this.config.set(path, replacement);
      return true;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
         return true;
      }

      try {
         String name = command.getName().toLowerCase(Locale.ROOT);

         return switch (name) {
            case "tpa" -> this.handleTpa(sender, args);
            case "tpaaccept" -> this.accept(sender, args);
            case "tpacancel" -> this.deny(sender, args);
            case "tpaauto" -> this.toggleAutoAccept(sender, args);
            case "tpalookup" -> this.lookup(sender, args);
            case "tpahere" -> this.requestTeleport(sender, args, true);
            case "tphere" -> this.teleportHere(sender, args);
            case "tp", "gctp" -> this.teleport(sender, args);
            default -> false;
         };
      } catch (TeleportFeature.TeleportStorageException exception) {
         Throwable cause = exception.getCause();
         this.plugin.getLogger().warning("TPA/TP-Datenbankfehler: " + (cause == null ? exception.getMessage() : cause.getMessage()));
         this.sendPath(sender, "messages.database-error", "&cDie Teleport-Anfrage ist gerade nicht verfügbar. Bitte versuche es später erneut.");
         return true;
      }
   }

   private boolean handleTpa(CommandSender sender, String[] args) {
      return args.length == 1 && args[0].equalsIgnoreCase("__notify") ? this.notifyPendingRequest(sender) : this.requestTeleport(sender, args, false);
   }

   private boolean requestTeleport(CommandSender sender, String[] args, boolean here) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return true;
      }

      String permissionPath = here ? "permissions.request-here" : "permissions.request";
      String permissionDefault = here ? "system.tpa.here" : "system.tpa";
      if (!this.hasPermission(sender, permissionPath, permissionDefault)) {
         return true;
      }

      if (args.length != 1) {
         this.sendPath(
            sender, here ? "messages.usage-tpahere" : "messages.usage-tpa", here ? "&7Verwendung: &f/tpahere <Spieler>" : "&7Verwendung: &f/tpa <Spieler>"
         );
         return true;
      }

      TeleportFeature.NetworkPlayer networkTarget = null;
      Player target = Bukkit.getPlayerExact(args[0]);
      if (target == null && this.hasFreshProxySnapshot()) {
         networkTarget = this.findNetworkPlayer(args[0]);
         if (networkTarget != null) {
            target = Bukkit.getPlayer(networkTarget.uuid());
         }
      }

      if (target == null) {
         String targetName = networkTarget == null ? args[0] : networkTarget.name();
         if (!this.sendProxyTpaRequest(player, targetName, here, false)) {
            this.sendPath(player, "messages.teleport-failed", "&cDie Anfrage konnte nicht an Velocity gesendet werden.");
         }

         return true;
      } else {
         if (target.equals(player)) {
            this.sendPath(sender, "messages.cannot-target-self", "&cDu kannst dich nicht selbst auswählen.");
            return true;
         }

         if (!this.mysqlEnabled) {
            if (!this.sendProxyTpaRequest(player, target.getName(), here, false)) {
               this.sendPath(player, "messages.teleport-failed", "&cDie Anfrage konnte nicht an Velocity gesendet werden.");
            }

            return true;
         } else {
            this.ensureDatabaseAvailable();
            return this.createNetworkRequest(player, target.getName(), here);
         }
      }
   }

   private boolean createNetworkRequest(Player requester, String targetInput, boolean here) {
      TeleportFeature.NetworkPlayer target = this.findNetworkPlayer(targetInput);
      if (target == null) {
         this.sendPath(requester, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
         return true;
      }

      if (target.uuid().equals(requester.getUniqueId())) {
         this.sendPath(requester, "messages.cannot-target-self", "&cDu kannst dich nicht selbst auswählen.");
         return true;
      }

      long now = System.currentTimeMillis();
      long timeout = Math.max(0L, this.config.getLong("request.expires-seconds", 0L)) * 1000L;
      boolean automaticallyAccepted = this.isAutoAccepted(target.uuid());
      if (automaticallyAccepted) {
         Player localTarget = Bukkit.getPlayer(target.uuid());
         if (localTarget != null) {
            Player teleported = here ? localTarget : requester;
            Player destination = here ? requester : localTarget;
            if (!this.performTeleport(teleported, destination, requester.getName(), here ? "TPA-HERE (AUTO)" : "TPA (AUTO)")) {
               this.sendPath(requester, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
            } else {
               this.sendPath(requester, "messages.teleported", "&aTeleportiert.");
               this.send(localTarget, "&e" + requester.getName() + " &ahat sich zu dir teleportiert.");
            }

            return true;
         } else {
            UUID mover = here ? target.uuid() : requester.getUniqueId();
            UUID destination = here ? requester.getUniqueId() : target.uuid();
            String type = here ? "TPA-HERE (AUTO)" : "TPA (AUTO)";
            String destinationServer = here ? this.serverId : target.serverId();
            if (!this.createNetworkTransfer(requester, mover, destination, requester.getName(), type, destinationServer)) {
               this.sendPath(requester, "messages.teleport-failed", "&cDer Serverwechsel für den Teleport ist fehlgeschlagen.");
            } else {
               this.sendPath(requester, "messages.teleported", "&aTeleport wird ausgeführt.");
            }

            return true;
         }
      } else {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO greenattack_tpa_requests (receiver_uuid,requester_uuid,requester_name,receiver_name,requester_server,here,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE requester_uuid=VALUES(requester_uuid),requester_name=VALUES(requester_name),receiver_name=VALUES(receiver_name),requester_server=VALUES(requester_server),here=VALUES(here),created_at=VALUES(created_at),expires_at=VALUES(expires_at)"
            );
         ) {
            statement.setString(1, target.uuid().toString());
            statement.setString(2, requester.getUniqueId().toString());
            statement.setString(3, requester.getName());
            statement.setString(4, target.name());
            statement.setString(5, this.serverId);
            statement.setBoolean(6, here);
            statement.setLong(7, now);
            statement.setLong(8, timeout == 0L ? 0L : now + timeout);
            statement.executeUpdate();
         } catch (SQLException exception) {
            throw new TeleportFeature.TeleportStorageException(exception);
         }

         this.send(requester, this.config.getString("messages.request-sent", "&aTeleport-Anfrage an &e%player% &agesendet.").replace("%player%", target.name()));
         Player localTarget = Bukkit.getPlayer(target.uuid());
         if (localTarget != null) {
            this.showRequestToTarget(localTarget, requester.getName(), here);
         } else if (!this.requestNetworkConnect(requester, target.uuid(), target.serverId(), "/tpa __notify")) {
            this.sendPath(requester, "messages.teleport-failed", "&cDie Anfrage wurde gespeichert, aber der Spieler konnte nicht benachrichtigt werden.");
         }

         return true;
      }
   }

   private void showRequestToTarget(Player receiver, String requesterName, boolean here) {
      String received = this.config
         .getString(
            here ? "messages.request-here-received" : "messages.request-received",
            here ? "&e%player% &amöchte dich zu sich teleportieren." : "&e%player% &amöchte sich zu dir teleportieren."
         );
      this.send(receiver, received.replace("%player%", requesterName));
      this.sendActions(receiver);
   }

   private boolean notifyPendingRequest(CommandSender sender) {
      if (sender instanceof Player receiver && this.mysqlEnabled) {
         TeleportFeature.NetworkTeleportRequest request = this.readNetworkRequest(receiver.getUniqueId());
         if (request == null) {
            return true;
         } else if (request.expired()) {
            this.deleteNetworkRequest(receiver.getUniqueId());
            this.sendPath(receiver, "messages.request-expired", "&cDiese Teleport-Anfrage ist abgelaufen.");
            return true;
         } else {
            this.showRequestToTarget(receiver, request.requesterName(), request.here());
            return true;
         }
      } else {
         return true;
      }
   }

   private boolean acceptNetworkRequest(Player receiver) {
      TeleportFeature.NetworkTeleportRequest request = this.readNetworkRequest(receiver.getUniqueId());
      if (request == null) {
         this.sendProxyTpaControl(receiver, "tpa-accept");
         return true;
      }

      if (request.expired()) {
         this.deleteNetworkRequest(receiver.getUniqueId());
         this.sendPath(receiver, "messages.request-expired", "&cDiese Teleport-Anfrage ist abgelaufen.");
         return true;
      }

      TeleportFeature.NetworkPlayer requester = this.findNetworkPlayer(request.requesterUuid().toString());
      if (requester == null) {
         this.deleteNetworkRequest(receiver.getUniqueId());
         this.sendPath(receiver, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
         return true;
      }

      Player localRequester = Bukkit.getPlayer(request.requesterUuid());
      if (localRequester == null) {
         UUID moverId = request.here() ? receiver.getUniqueId() : requester.uuid();
         UUID destinationId = request.here() ? requester.uuid() : receiver.getUniqueId();
         String destinationServer = request.here() ? requester.serverId() : this.serverId;
         String teleportType = request.here() ? "TPA-HERE" : "TPA";
         if (!this.createNetworkTransfer(receiver, moverId, destinationId, receiver.getName(), teleportType, destinationServer)) {
            this.sendPath(receiver, "messages.teleport-failed", "&cDer Serverwechsel für den Teleport ist fehlgeschlagen.");
            return true;
         } else {
            this.deleteNetworkRequest(receiver.getUniqueId());
            this.send(
               receiver,
               this.config.getString("messages.request-accepted", "&aTeleport-Anfrage von &e%player% &aangenommen.").replace("%player%", requester.name())
            );
            return true;
         }
      } else {
         this.deleteNetworkRequest(receiver.getUniqueId());
         Player teleported = request.here() ? receiver : localRequester;
         Player destination = request.here() ? localRequester : receiver;
         if (!this.performTeleport(teleported, destination, receiver.getName(), request.here() ? "TPA-HERE" : "TPA")) {
            this.sendPath(receiver, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
            return true;
         } else {
            this.send(
               receiver,
               this.config
                  .getString("messages.request-accepted", "&aTeleport-Anfrage von &e%player% &aangenommen.")
                  .replace("%player%", localRequester.getName())
            );
            this.send(
               localRequester,
               this.config
                  .getString("messages.request-accepted-by", "&e%player% &ahat deine Teleport-Anfrage angenommen.")
                  .replace("%player%", receiver.getName())
            );
            return true;
         }
      }
   }

   private boolean denyNetworkRequest(Player receiver) {
      TeleportFeature.NetworkTeleportRequest request = this.readNetworkRequest(receiver.getUniqueId());
      if (request == null) {
         this.sendProxyTpaControl(receiver, "tpa-deny");
         return true;
      } else {
         this.deleteNetworkRequest(receiver.getUniqueId());
         this.send(
            receiver,
            this.config.getString("messages.request-denied", "&cTeleport-Anfrage von &e%player% &cabgelehnt.").replace("%player%", request.requesterName())
         );
         return true;
      }
   }

   private TeleportFeature.NetworkPlayer findNetworkPlayer(String input) {
      UUID uuid = null;

      try {
         uuid = UUID.fromString(input);
      } catch (IllegalArgumentException var15) {
      }

      if (this.hasFreshProxySnapshot()) {
         return uuid == null ? this.proxyPlayersByName.get(input.toLowerCase(Locale.ROOT)) : this.proxyPlayersById.get(uuid);
      }

      Player localPlayer = uuid == null ? Bukkit.getPlayerExact(input) : Bukkit.getPlayer(uuid);
      if (localPlayer != null) {
         return new TeleportFeature.NetworkPlayer(localPlayer.getUniqueId(), localPlayer.getName(), this.serverId == null ? "" : this.serverId);
      }

      if (!this.mysqlEnabled) {
         return null;
      }

      long cutoff = System.currentTimeMillis() - 120000L;
      String sql = uuid == null
         ? "SELECT player_uuid,player_name,server_id FROM greenattack_network_players WHERE is_online=TRUE AND last_seen>=? AND LOWER(player_name)=LOWER(?) LIMIT 1"
         : "SELECT player_uuid,player_name,server_id FROM greenattack_network_players WHERE is_online=TRUE AND last_seen>=? AND player_uuid=? LIMIT 1";

      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement(sql);
      ) {
         statement.setLong(1, cutoff);
         statement.setString(2, uuid == null ? input : uuid.toString());

         try (ResultSet result = statement.executeQuery()) {
            return result.next() ? new TeleportFeature.NetworkPlayer(UUID.fromString(result.getString(1)), result.getString(2), result.getString(3)) : null;
         }
      } catch (SQLException exception) {
         throw new TeleportFeature.TeleportStorageException(exception);
      }
   }

   private TeleportFeature.NetworkTeleportRequest readNetworkRequest(UUID receiverId) {
      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement(
            "SELECT requester_uuid,requester_name,requester_server,here,created_at,expires_at FROM greenattack_tpa_requests WHERE receiver_uuid=?"
         );
      ) {
         statement.setString(1, receiverId.toString());

         try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
               return null;
            }

            long expiresAt = result.getLong("expires_at");
            return new TeleportFeature.NetworkTeleportRequest(
               UUID.fromString(result.getString("requester_uuid")),
               result.getString("requester_name"),
               result.getString("requester_server"),
               result.getBoolean("here"),
               result.getLong("created_at"),
               expiresAt
            );
         }
      } catch (SQLException exception) {
         throw new TeleportFeature.TeleportStorageException(exception);
      }
   }

   private void deleteNetworkRequest(UUID receiverId) {
      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement("DELETE FROM greenattack_tpa_requests WHERE receiver_uuid=?");
      ) {
         statement.setString(1, receiverId.toString());
         statement.executeUpdate();
      } catch (SQLException exception) {
         throw new TeleportFeature.TeleportStorageException(exception);
      }
   }

   private boolean isAutoAccepted(UUID playerId) {
      if (!this.mysqlEnabled) {
         return this.autoAccept.contains(playerId);
      }

      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement("SELECT enabled FROM greenattack_tpa_auto WHERE player_uuid=?");
      ) {
         statement.setString(1, playerId.toString());

         try (ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
         }
      } catch (SQLException exception) {
         throw new TeleportFeature.TeleportStorageException(exception);
      }
   }

   private void setAutoAccepted(UUID playerId, boolean enabled) {
      if (!this.mysqlEnabled) {
         if (enabled) {
            this.autoAccept.add(playerId);
         } else {
            this.autoAccept.remove(playerId);
         }
      } else {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO greenattack_tpa_auto (player_uuid,enabled) VALUES (?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)"
            );
         ) {
            statement.setString(1, playerId.toString());
            statement.setBoolean(2, enabled);
            statement.executeUpdate();
         } catch (SQLException exception) {
            throw new TeleportFeature.TeleportStorageException(exception);
         }
      }
   }

   private boolean createNetworkTransfer(Player actor, UUID moverId, UUID destinationId, String actorName, String type, String destinationServer) {
      UUID token = UUID.randomUUID();

      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO greenattack_tp_transfers (token,mover_uuid,destination_uuid,actor_name,teleport_type,expires_at) VALUES (?,?,?,?,?,?)"
         );
      ) {
         statement.setString(1, token.toString());
         statement.setString(2, moverId.toString());
         statement.setString(3, destinationId.toString());
         statement.setString(4, actorName);
         statement.setString(5, type);
         statement.setLong(6, System.currentTimeMillis() + 60000L);
         statement.executeUpdate();
      } catch (SQLException exception) {
         throw new TeleportFeature.TeleportStorageException(exception);
      }

      if (this.requestNetworkConnect(actor, moverId, destinationServer, "/tp __finish " + token)) {
         return true;
      }

      this.deleteNetworkTransfer(token);
      return false;
   }

   private boolean requestNetworkConnect(Player actor, UUID moverId, String targetServer, String command) {
      if (targetServer != null && targetServer.matches("[A-Za-z0-9_-]{1,64}")) {
         try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            try (DataOutputStream output = new DataOutputStream(bytes)) {
               output.writeUTF("connect");
               output.writeUTF(actor.getUniqueId().toString());
               output.writeUTF(moverId.toString());
               output.writeUTF(targetServer);
               output.writeUTF(command);
            }

            actor.sendPluginMessage(this.plugin, "greenattack:home_connect", bytes.toByteArray());
            return true;
         } catch (IOException exception) {
            this.plugin.getLogger().warning("Velocity-Transfernachricht konnte nicht erstellt werden: " + exception.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   private boolean finishNetworkTransfer(CommandSender sender, String tokenText) {
      if (sender instanceof Player mover) {
         UUID token;
         try {
            token = UUID.fromString(tokenText);
         } catch (IllegalArgumentException exception) {
            return true;
         }

         UUID destinationId;
         String actorName;
         String type;
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "SELECT destination_uuid,actor_name,teleport_type,expires_at FROM greenattack_tp_transfers WHERE token=? AND mover_uuid=?"
            );
         ) {
            statement.setString(1, token.toString());
            statement.setString(2, mover.getUniqueId().toString());

            try (ResultSet result = statement.executeQuery()) {
               if (!result.next()) {
                  return true;
               }

               if (result.getLong("expires_at") < System.currentTimeMillis()) {
                  this.deleteNetworkTransfer(token);
                  this.sendPath(mover, "messages.teleport-failed", "&cDer Teleport ist abgelaufen.");
                  return true;
               }

               destinationId = UUID.fromString(result.getString("destination_uuid"));
               actorName = result.getString("actor_name");
               type = result.getString("teleport_type");
            }
         } catch (SQLException exception) {
            throw new TeleportFeature.TeleportStorageException(exception);
         }

         Player destination = Bukkit.getPlayer(destinationId);
         if (destination == null) {
            this.deleteNetworkTransfer(token);
            this.sendPath(mover, "messages.player-not-found", "&cDer Zielspieler ist nicht mehr auf diesem Server.");
            return true;
         } else {
            this.deleteNetworkTransfer(token);
            if (!this.performTeleport(mover, destination, actorName, type)) {
               this.sendPath(mover, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
               return true;
            } else {
               this.sendPath(mover, "messages.teleported", "&aTeleportiert.");
               return true;
            }
         }
      } else {
         return true;
      }
   }

   private void deleteNetworkTransfer(UUID token) {
      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement("DELETE FROM greenattack_tp_transfers WHERE token=?");
      ) {
         statement.setString(1, token.toString());
         statement.executeUpdate();
      } catch (SQLException exception) {
         throw new TeleportFeature.TeleportStorageException(exception);
      }
   }

   private boolean networkTeleportHere(Player executor, String targetName) {
      TeleportFeature.NetworkPlayer target = this.findNetworkPlayer(targetName);
      if (target == null) {
         this.sendPath(executor, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
         return true;
      }

      Player localTarget = Bukkit.getPlayer(target.uuid());
      if (localTarget != null) {
         if (!this.performTeleport(localTarget, executor, executor.getName(), "TP-HERE")) {
            this.sendPath(executor, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
         } else {
            this.send(localTarget, this.config.getString("messages.teleported", "&aTeleportiert."));
            this.send(
               executor,
               this.config.getString("messages.teleport-here-success", "&e%player% &awurde zu dir teleportiert.").replace("%player%", localTarget.getName())
            );
         }

         return true;
      } else {
         if (!this.createNetworkTransfer(executor, target.uuid(), executor.getUniqueId(), executor.getName(), "TP-HERE", this.serverId)) {
            this.sendPath(executor, "messages.teleport-failed", "&cDer Serverwechsel für den Teleport ist fehlgeschlagen.");
         } else {
            this.send(
               executor, this.config.getString("messages.teleport-here-success", "&e%player% &awurde zu dir teleportiert.").replace("%player%", target.name())
            );
         }

         return true;
      }
   }

   private boolean networkTeleport(CommandSender sender, String[] args) {
      if (args.length == 1) {
         if (sender instanceof Player executor) {
            TeleportFeature.NetworkPlayer target = this.findNetworkPlayer(args[0]);
            if (target == null) {
               this.sendPath(sender, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
               return true;
            }

            Player localTarget = Bukkit.getPlayer(target.uuid());
            if (localTarget != null) {
               if (!this.performTeleport(executor, localTarget, executor.getName(), "TP")) {
                  this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
               } else {
                  this.sendPath(executor, "messages.teleported", "&aTeleportiert.");
               }

               return true;
            } else {
               if (!this.createNetworkTransfer(executor, executor.getUniqueId(), target.uuid(), executor.getName(), "TP", target.serverId())) {
                  this.sendPath(executor, "messages.teleport-failed", "&cDer Serverwechsel für den Teleport ist fehlgeschlagen.");
               }

               return true;
            }
         } else {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         }
      } else {
         TeleportFeature.NetworkPlayer first = this.findNetworkPlayer(args[0]);
         TeleportFeature.NetworkPlayer second = this.findNetworkPlayer(args[1]);
         if (first != null && second != null) {
            Player localFirst = Bukkit.getPlayer(first.uuid());
            Player localSecond = Bukkit.getPlayer(second.uuid());
            if (localFirst != null && localSecond != null) {
               if (!this.performTeleport(localFirst, localSecond, sender.getName(), "TP")) {
                  this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
               } else {
                  this.send(
                     sender,
                     this.config
                        .getString("messages.teleport-success", "&e%player% &awurde zu &e%target% &ateleportiert.")
                        .replace("%player%", localFirst.getName())
                        .replace("%target%", localSecond.getName())
                  );
                  this.send(localFirst, this.config.getString("messages.teleported", "&aTeleportiert."));
               }

               return true;
            } else if (sender instanceof Player actor) {
               String destinationServer = second.serverId();
               if (!this.createNetworkTransfer(actor, first.uuid(), second.uuid(), sender.getName(), "TP", destinationServer)) {
                  this.sendPath(sender, "messages.teleport-failed", "&cDer Serverwechsel für den Teleport ist fehlgeschlagen.");
               } else {
                  this.send(
                     sender,
                     this.config
                        .getString("messages.teleport-success", "&e%player% &awurde zu &e%target% &ateleportiert.")
                        .replace("%player%", first.name())
                        .replace("%target%", second.name())
                  );
               }

               return true;
            } else {
               this.sendPath(sender, "messages.player-only", "&cServerübergreifende Teleports müssen von einem Spieler ausgelöst werden.");
               return true;
            }
         } else {
            this.sendPath(sender, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
            return true;
         }
      }
   }

   private void sendActions(Player receiver) {
      String acceptLabel = color(this.config.getString("buttons.accept", "&a[Annehmen]"));
      String denyLabel = color(this.config.getString("buttons.deny", "&c[Ablehnen]"));
      Component prefixComponent = LegacyComponentSerializer.legacySection().deserialize(this.prefix());
      Component actionMessage = prefixComponent.append(Component.text(acceptLabel).clickEvent(ClickEvent.runCommand("/tpaaccept")))
         .append(Component.text(color(this.config.getString("button-separator", " &7| "))))
         .append(Component.text(denyLabel).clickEvent(ClickEvent.runCommand("/tpacancel")));
      receiver.sendMessage(actionMessage);
   }

   private boolean accept(CommandSender sender, String[] args) {
      Player receiver = this.requirePlayer(sender);
      if (receiver == null) {
         return true;
      } else if (!this.hasPermission(sender, "permissions.request", "system.tpa")) {
         return true;
      } else if (args.length != 0) {
         this.sendPath(sender, "messages.usage-accept", "&7Verwendung: &f/tpaaccept");
         return true;
      } else if (this.proxyPendingReceivers.remove(receiver.getUniqueId())) {
         this.sendProxyTpaControl(receiver, "tpa-accept");
         return true;
      } else if (this.databaseRequested && !this.mysqlEnabled) {
         this.sendProxyTpaControl(receiver, "tpa-accept");
         return true;
      } else {
         this.ensureDatabaseAvailable();
         if (this.mysqlEnabled) {
            return this.acceptNetworkRequest(receiver);
         } else {
            TeleportFeature.TeleportRequest request = this.requestsByReceiver.remove(receiver.getUniqueId());
            if (request == null) {
               this.sendProxyTpaControl(receiver, "tpa-accept");
               return true;
            } else if (request.isExpired()) {
               this.sendPath(sender, "messages.request-expired", "&cDiese Teleport-Anfrage ist abgelaufen.");
               return true;
            } else {
               Player requester = Bukkit.getPlayer(request.requester());
               if (requester == null) {
                  this.sendPath(sender, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
                  return true;
               } else {
                  Player teleported = request.here() ? receiver : requester;
                  Player destination = request.here() ? requester : receiver;
                  if (!this.performTeleport(teleported, destination, receiver.getName(), request.here() ? "TPA-HERE" : "TPA")) {
                     this.sendPath(receiver, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
                     return true;
                  } else {
                     this.send(
                        receiver,
                        this.config
                           .getString("messages.request-accepted", "&aTeleport-Anfrage von &e%player% &aangenommen.")
                           .replace("%player%", requester.getName())
                     );
                     this.send(
                        requester,
                        this.config
                           .getString("messages.request-accepted-by", "&e%player% &ahat deine Teleport-Anfrage angenommen.")
                           .replace("%player%", receiver.getName())
                     );
                     return true;
                  }
               }
            }
         }
      }
   }

   private boolean deny(CommandSender sender, String[] args) {
      Player receiver = this.requirePlayer(sender);
      if (receiver == null) {
         return true;
      }

      if (!this.hasPermission(sender, "permissions.request", "system.tpa")) {
         return true;
      }

      if (args.length != 0) {
         this.sendPath(sender, "messages.usage-cancel", "&7Verwendung: &f/tpacancel");
         return true;
      }

      if (this.proxyPendingReceivers.remove(receiver.getUniqueId())) {
         this.sendProxyTpaControl(receiver, "tpa-deny");
         return true;
      }

      if (this.databaseRequested && !this.mysqlEnabled) {
         this.sendProxyTpaControl(receiver, "tpa-deny");
         return true;
      }

      this.ensureDatabaseAvailable();
      if (this.mysqlEnabled) {
         return this.denyNetworkRequest(receiver);
      }

      TeleportFeature.TeleportRequest request = this.requestsByReceiver.remove(receiver.getUniqueId());
      if (request == null) {
         this.sendProxyTpaControl(receiver, "tpa-deny");
         return true;
      }

      Player requester = Bukkit.getPlayer(request.requester());
      this.send(
         receiver,
         this.config
            .getString("messages.request-denied", "&cTeleport-Anfrage von &e%player% &cabgelehnt.")
            .replace("%player%", requester == null ? "?" : requester.getName())
      );
      if (requester != null) {
         this.send(
            requester,
            this.config.getString("messages.request-denied-by", "&e%player% &chat deine Teleport-Anfrage abgelehnt.").replace("%player%", receiver.getName())
         );
      }

      return true;
   }

   private boolean toggleAutoAccept(CommandSender sender, String[] args) {
      Player player = this.requirePlayer(sender);
      if (player == null) {
         return true;
      }

      if (!this.hasPermission(sender, "permissions.auto", "system.tpa.auto")) {
         return true;
      }

      if (args.length != 0) {
         this.sendPath(sender, "messages.usage-auto", "&7Verwendung: &f/tpaauto");
         return true;
      }

      this.ensureDatabaseAvailable();
      if (this.mysqlEnabled) {
         boolean enabled = !this.isAutoAccepted(player.getUniqueId());
         this.setAutoAccepted(player.getUniqueId(), enabled);
         this.sendProxyTpaAuto(player, enabled);
         this.sendPath(
            sender,
            enabled ? "messages.auto-enabled" : "messages.auto-disabled",
            enabled ? "&aAutomatisches Annehmen von TPA-Anfragen ist aktiviert." : "&cAutomatisches Annehmen von TPA-Anfragen ist deaktiviert."
         );
         return true;
      }

      boolean enabled;
      if (this.autoAccept.remove(player.getUniqueId())) {
         enabled = false;
         this.sendPath(sender, "messages.auto-disabled", "&cAutomatisches Annehmen von TPA-Anfragen ist deaktiviert.");
      } else {
         this.autoAccept.add(player.getUniqueId());
         enabled = true;
         this.sendPath(sender, "messages.auto-enabled", "&aAutomatisches Annehmen von TPA-Anfragen ist aktiviert.");
      }

      this.sendProxyTpaAuto(player, enabled);
      return true;
   }

   private boolean lookup(CommandSender sender, String[] args) {
      if (!this.hasPermission(sender, "permissions.admin", "system.tpa.admin")) {
         return true;
      }

      if (args.length != 0) {
         this.sendPath(sender, "messages.lookup-usage", "&7Verwendung: &f/tpalookup");
         return true;
      }

      this.ensureDatabaseAvailable();
      if (this.mysqlEnabled) {
         return this.showNetworkLookup(sender);
      }

      List<Map<?, ?>> history = this.config.getMapList("storage.teleport-history");
      if (history.isEmpty()) {
         this.sendPath(sender, "messages.lookup-empty", "&7Es wurden noch keine Teleports gespeichert.");
         return true;
      }

      this.sendPath(sender, "messages.lookup-header", "&6Letzte Teleports:");
      int maxEntries = Math.max(1, this.config.getInt("lookup.display-entries", 10));
      int shown = 0;

      for (Map<?, ?> entry : history) {
         if (shown >= maxEntries) {
            break;
         }

         long time = readLong(entry.get("time"), 0L);
         String timeText = time > 0L ? LOOKUP_TIME_FORMAT.format(Instant.ofEpochMilli(time)) : "?";
         this.send(
            sender,
            replace(
               this.config.getString("messages.lookup-entry", "&7[%time%] &e%player% &7wurde von &e%by% &7zu &e%target% &7teleportiert &8(%type%)"),
               "%time%",
               timeText,
               "%player%",
               readString(entry.get("player")),
               "%by%",
               readString(entry.get("by")),
               "%target%",
               readString(entry.get("target")),
               "%type%",
               readString(entry.get("type"))
            )
         );
         shown++;
      }

      return true;
   }

   private boolean performTeleport(Player teleported, Player destination, String by, String type) {
      if (!teleported.teleport(destination)) {
         return false;
      }

      this.recordTeleport(teleported.getName(), by, destination.getName(), type);
      return true;
   }

   private void recordTeleport(String teleported, String by, String target, String type) {
      if (this.mysqlEnabled) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO greenattack_teleport_history (event_time,player_name,by_name,target_name,teleport_type) VALUES (?,?,?,?,?)"
            );
         ) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, teleported);
            statement.setString(3, by);
            statement.setString(4, target);
            statement.setString(5, type);
            statement.executeUpdate();
         } catch (SQLException exception) {
            throw new TeleportFeature.TeleportStorageException(exception);
         }
      } else {
         List<Map<?, ?>> previous = this.config.getMapList("storage.teleport-history");
         List<Map<String, Object>> history = new ArrayList<>();
         Map<String, Object> latest = new LinkedHashMap<>();
         latest.put("time", System.currentTimeMillis());
         latest.put("player", teleported);
         latest.put("by", by);
         latest.put("target", target);
         latest.put("type", type);
         history.add(latest);
         int maxHistory = Math.max(1, this.config.getInt("lookup.history-size", 100));

         for (Map<?, ?> entry : previous) {
            if (history.size() >= maxHistory) {
               break;
            }

            Map<String, Object> copy = new LinkedHashMap<>();

            for (Entry<?, ?> field : entry.entrySet()) {
               if (field.getKey() instanceof String key) {
                  copy.put(key, field.getValue());
               }
            }

            history.add(copy);
         }

         this.config.set("storage.teleport-history", history);
         this.save();
      }
   }

   private boolean showNetworkLookup(CommandSender sender) {
      int limit = Math.max(1, Math.min(100, this.config.getInt("lookup.display-entries", 10)));
      List<Map<String, Object>> entries = new ArrayList<>();

      try (
         Connection connection = this.openDatabaseConnection();
         PreparedStatement statement = connection.prepareStatement(
            "SELECT event_time,player_name,by_name,target_name,teleport_type FROM greenattack_teleport_history ORDER BY event_time DESC LIMIT ?"
         );
      ) {
         statement.setInt(1, limit);

         try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
               Map<String, Object> entry = new LinkedHashMap<>();
               entry.put("time", result.getLong("event_time"));
               entry.put("player", result.getString("player_name"));
               entry.put("by", result.getString("by_name"));
               entry.put("target", result.getString("target_name"));
               entry.put("type", result.getString("teleport_type"));
               entries.add(entry);
            }
         }
      } catch (SQLException exception) {
         throw new TeleportFeature.TeleportStorageException(exception);
      }

      if (entries.isEmpty()) {
         this.sendPath(sender, "messages.lookup-empty", "&7Es wurden noch keine Teleports gespeichert.");
         return true;
      }

      this.sendPath(sender, "messages.lookup-header", "&6Letzte Teleports:");

      for (Map<String, Object> entry : entries) {
         this.send(
            sender,
            replace(
               this.config.getString("messages.lookup-entry", "&7[%time%] &e%player% &7wurde von &e%by% &7zu &e%target% &7teleportiert &8(%type%)"),
               "%time%",
               LOOKUP_TIME_FORMAT.format(Instant.ofEpochMilli(readLong(entry.get("time"), 0L))),
               "%player%",
               readString(entry.get("player")),
               "%by%",
               readString(entry.get("by")),
               "%target%",
               readString(entry.get("target")),
               "%type%",
               readString(entry.get("type"))
            )
         );
      }

      return true;
   }

   private static long readLong(Object value, long fallback) {
      if (value instanceof Number number) {
         return number.longValue();
      } else if (value instanceof String text) {
         try {
            return Long.parseLong(text);
         } catch (NumberFormatException ignored) {
            return fallback;
         }
      } else {
         return fallback;
      }
   }

   private static String readString(Object value) {
      return value == null ? "?" : String.valueOf(value);
   }

   private static String replace(String text, String... replacements) {
      String result = text == null ? "" : text;

      for (int index = 0; index + 1 < replacements.length; index += 2) {
         result = result.replace(replacements[index], replacements[index + 1]);
      }

      return result;
   }

   private boolean teleportHere(CommandSender sender, String[] args) {
      if (!this.hasPermission(sender, "permissions.teleport-here", "system.tp.here")) {
         return true;
      }

      if (args.length != 1) {
         this.sendPath(sender, "messages.usage-tphere", "&7Verwendung: &f/tphere <Spieler>");
         return true;
      }

      this.ensureDatabaseAvailable();
      if (this.mysqlEnabled) {
         if (sender instanceof Player executor) {
            return this.networkTeleportHere(executor, args[0]);
         } else {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         }
      } else {
         Player target = Bukkit.getPlayerExact(args[0]);
         if (target == null) {
            TeleportFeature.NetworkPlayer proxyTarget = this.findNetworkPlayer(args[0]);
            if (proxyTarget != null && sender instanceof Player executor) {
               if (this.sendProxyTeleport(executor, proxyTarget.uuid(), executor.getUniqueId(), "TP-HERE")) {
                  this.send(
                     executor,
                     this.config.getString("messages.teleport-here-success", "&e%player% &awurde zu dir teleportiert.").replace("%player%", proxyTarget.name())
                  );
               } else {
                  this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
               }

               return true;
            } else {
               this.sendPath(sender, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
               return true;
            }
         } else if (sender instanceof Player executor) {
            if (!this.performTeleport(target, executor, executor.getName(), "TP-HERE")) {
               this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
               return true;
            } else {
               this.send(target, this.config.getString("messages.teleported", "&aTeleportiert."));
               this.send(
                  executor,
                  this.config.getString("messages.teleport-here-success", "&e%player% &awurde zu dir teleportiert.").replace("%player%", target.getName())
               );
               return true;
            }
         } else {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         }
      }
   }

   private boolean teleport(CommandSender sender, String[] args) {
      if (this.mysqlEnabled && args.length == 2 && args[0].equalsIgnoreCase("__finish")) {
         return this.finishNetworkTransfer(sender, args[1]);
      }

      if (!this.hasPermission(sender, "permissions.teleport", "system.tp")) {
         return true;
      }

      if (args.length >= 1 && args.length <= 2) {
         this.ensureDatabaseAvailable();
         if (this.mysqlEnabled) {
            return this.networkTeleport(sender, args);
         }

         Player first = Bukkit.getPlayerExact(args[0]);
         Player second = args.length == 2 ? Bukkit.getPlayerExact(args[1]) : null;
         if (args.length == 1) {
            if (first == null) {
               TeleportFeature.NetworkPlayer proxyTarget = this.findNetworkPlayer(args[0]);
               if (proxyTarget == null) {
                  this.sendPath(sender, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
                  return true;
               }

               if (sender instanceof Player executor) {
                  if (!this.sendProxyTeleport(executor, executor.getUniqueId(), proxyTarget.uuid(), "TP")) {
                     this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
                  }

                  return true;
               }

               this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
               return true;
            }

            if (!(sender instanceof Player executor)) {
               this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
               return true;
            }

            if (!this.performTeleport(executor, first, executor.getName(), "TP")) {
               this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
               return true;
            }

            this.send(executor, this.config.getString("messages.teleported", "&aTeleportiert."));
         } else {
            if (first == null || second == null) {
               TeleportFeature.NetworkPlayer proxyFirst = this.findNetworkPlayer(args[0]);
               TeleportFeature.NetworkPlayer proxySecond = this.findNetworkPlayer(args[1]);
               if (proxyFirst != null && proxySecond != null) {
                  if (sender instanceof Player executor) {
                     if (!this.sendProxyTeleport(executor, proxyFirst.uuid(), proxySecond.uuid(), "TP")) {
                        this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
                     }

                     return true;
                  }

                  this.sendPath(sender, "messages.player-only", "&cServerübergreifende Teleports müssen von einem Spieler ausgelöst werden.");
                  return true;
               }

               this.sendPath(sender, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
               return true;
            }

            if (!this.performTeleport(first, second, sender.getName(), "TP")) {
               this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
               return true;
            }

            this.send(
               sender,
               this.config
                  .getString("messages.teleport-success", "&e%player% &awurde zu &e%target% &ateleportiert.")
                  .replace("%player%", first.getName())
                  .replace("%target%", second.getName())
            );
            this.send(first, this.config.getString("messages.teleported", "&aTeleportiert."));
         }

         return true;
      } else {
         this.sendPath(sender, "messages.usage-tp", "&7Verwendung: &f/tp <Spieler> [Zielspieler]");
         return true;
      }
   }

   private boolean hasPermission(CommandSender sender, String configPath, String fallback) {
      String configuredPermission = this.config.getString(configPath, fallback);
      if (this.hasFeaturePermission(sender, configuredPermission)) {
         return true;
      }

      this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
      return false;
   }

   private Player requirePlayer(CommandSender sender) {
      if (sender instanceof Player player) {
         return player;
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return null;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.isEnabled()) {
         return List.of();
      }

      if (sender instanceof Player player && !this.hasFreshProxySnapshot()) {
         this.requestProxyPlayerList(player);
      }

      String commandName = command.getName().toLowerCase(Locale.ROOT);
      String partial = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
      if (commandName.equals("tpa")) {
         return args.length <= 1 && this.hasConfiguredPermission(sender, "permissions.request", "system.tpa")
            ? this.onlineNetworkNamesForCompletion(sender, partial, true)
            : List.of();
      }

      int playerArgument = switch (commandName) {
         case "tpahere" -> 1;
         case "tphere" -> 1;
         case "tp", "gctp" -> args.length <= 2 ? args.length : -1;
         default -> -1;
      };
      if (args.length == playerArgument && playerArgument >= 1) {
         String permission = switch (commandName) {
            case "tpahere" -> this.config.getString("permissions.request-here", "system.tpa.here");
            case "tphere" -> this.config.getString("permissions.teleport-here", "system.tp.here");
            default -> this.config.getString("permissions.teleport", "system.tp");
         };
         return this.hasFeaturePermission(sender, permission) ? this.onlineNetworkNamesForCompletion(sender, partial, false) : List.of();
      } else {
         return List.of();
      }
   }

   private boolean hasConfiguredPermission(CommandSender sender, String configPath, String fallback) {
      return this.hasFeaturePermission(sender, this.config.getString(configPath, fallback));
   }

   private List<String> onlineNames(CommandSender sender, boolean excludeSender) {
      Map<String, String> namesByLowercase = new LinkedHashMap<>();
      long snapshotAge = this.proxySnapshotAt == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - this.proxySnapshotAt;
      if (snapshotAge <= 60000L) {
         this.proxyPlayersByName.values().forEach(networkPlayer -> namesByLowercase.put(networkPlayer.name().toLowerCase(Locale.ROOT), networkPlayer.name()));
      } else if (this.mysqlEnabled) {
         try (
            Connection connection = this.openDatabaseConnection();
            PreparedStatement statement = connection.prepareStatement(
               "SELECT player_name FROM greenattack_network_players WHERE is_online=TRUE AND last_seen>=? ORDER BY player_name"
            );
         ) {
            statement.setLong(1, System.currentTimeMillis() - 120000L);

            try (ResultSet result = statement.executeQuery()) {
               while (result.next()) {
                  String name = result.getString(1);
                  namesByLowercase.put(name.toLowerCase(Locale.ROOT), name);
               }
            }
         } catch (SQLException exception) {
            this.plugin.getLogger().fine("Netzwerk-Spieler konnten für Tab-Vervollständigung nicht geladen werden: " + exception.getMessage());
         }
      }

      Bukkit.getOnlinePlayers().forEach(playerx -> namesByLowercase.put(playerx.getName().toLowerCase(Locale.ROOT), playerx.getName()));
      if (excludeSender && sender instanceof Player player) {
         namesByLowercase.remove(player.getName().toLowerCase(Locale.ROOT));
      }

      return namesByLowercase.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
   }

   public List<String> onlineNetworkNamesForCompletion(CommandSender sender, String partial) {
      return this.onlineNetworkNamesForCompletion(sender, partial, false);
   }

   public boolean sendProxyMessageRequest(Player sender, String targetName, String message, String sentLine,
                                          String receivedLine, String notFoundLine, String selfLine) {
      if (targetName == null || !targetName.matches("[A-Za-z0-9_]{1,16}") || message == null || message.length() > 256) {
         return false;
      }
      return this.sendProxyPacket(sender, output -> {
         output.writeUTF("msg-request");
         output.writeUTF(targetName);
         output.writeUTF(message);
         output.writeUTF(sentLine);
         output.writeUTF(receivedLine);
         output.writeUTF(notFoundLine);
         output.writeUTF(selfLine);
      });
   }

   public List<String> onlineNetworkNamesForCompletion(CommandSender sender, String partial, boolean excludeSender) {
      if (sender instanceof Player player && !this.hasFreshProxySnapshot()) {
         this.requestProxyPlayerList(player);
      }

      String prefix = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
      return this.matching(this.onlineNames(sender, excludeSender), prefix);
   }

   private List<String> matching(List<String> candidates, String partial) {
      return candidates.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.knownOnlineNames.put(player.getUniqueId(), player.getName());
      this.updatePresence(player.getUniqueId(), player.getName(), true);
      if (this.mysqlEnabled) {
         try {
            this.sendProxyTpaAuto(player, this.isAutoAccepted(player.getUniqueId()));
         } catch (TeleportFeature.TeleportStorageException exception) {
            this.plugin.getLogger().fine("Gespeicherter TPA-Auto-Status konnte nicht mit Velocity synchronisiert werden.");
         }
      }

      Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.requestProxyPlayerList(player), 1L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      UUID quitId = event.getPlayer().getUniqueId();
      this.knownOnlineNames.remove(quitId);
      this.markPresenceOffline(quitId);
      this.autoAccept.remove(quitId);
      if (!this.mysqlEnabled) {
         this.requestsByReceiver.remove(quitId);
         this.requestsByReceiver.entrySet().removeIf(entry -> entry.getValue().requester().equals(quitId));
      }
   }

   public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
      if ("greenattack:home_connect".equals(channel)) {
         try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            String action = input.readUTF();
            switch (action) {
               case "players":
                  int count = input.readInt();
                  if (count < 0 || count > 10000 || input.available() == 0 && count > 0) {
                     return;
                  } else {
                     Map<String, TeleportFeature.NetworkPlayer> names = new ConcurrentHashMap<>();
                     Map<UUID, TeleportFeature.NetworkPlayer> ids = new ConcurrentHashMap<>();

                     for (int i = 0; i < count; i++) {
                        UUID uuid = new UUID(input.readLong(), input.readLong());
                        String name = input.readUTF();
                        String onlineServer = input.readUTF();
                        if (!name.matches("[A-Za-z0-9_]{1,16}") || !onlineServer.matches("[A-Za-z0-9_.-]{1,64}")) {
                           return;
                        }

                        TeleportFeature.NetworkPlayer player = new TeleportFeature.NetworkPlayer(uuid, name, onlineServer);
                        names.put(name.toLowerCase(Locale.ROOT), player);
                        ids.put(uuid, player);
                     }

                     if (input.available() == 0) {
                        this.proxyPlayersByName.clear();
                        this.proxyPlayersByName.putAll(names);
                        this.proxyPlayersById.clear();
                        this.proxyPlayersById.putAll(ids);
                        this.proxySnapshotAt = System.currentTimeMillis();
                        return;
                     }

                     return;
                  }
               case "tpa-notify":
                  String requesterName = input.readUTF();
                  boolean here = input.readBoolean();
                  if (input.available() == 0 && requesterName.matches("[A-Za-z0-9_]{1,16}")) {
                     this.proxyPendingReceivers.add(carrier.getUniqueId());
                     this.showRequestToTarget(carrier, requesterName, here);
                     return;
                  }

                  return;
               case "tpa-status":
                  String status = input.readUTF();
                  String value = input.readUTF();
                  if (input.available() == 0) {
                     this.handleProxyTpaStatus(carrier, status, value);
                  }

                  return;
               case "msg-log":
                  UUID messageSenderId = UUID.fromString(input.readUTF());
                  String messageSenderName = input.readUTF();
                  UUID messageTargetId = UUID.fromString(input.readUTF());
                  String messageTargetName = input.readUTF();
                  String messageText = input.readUTF();
                  if (input.available() == 0
                     && carrier.getUniqueId().equals(messageSenderId)
                     && messageSenderName.matches("[A-Za-z0-9_]{1,16}")
                     && messageTargetName.matches("[A-Za-z0-9_]{1,16}")
                     && messageText.length() <= 256
                     && !messageSenderId.equals(messageTargetId)
                     && this.plugin.getMsgFeature() != null) {
                     this.plugin.getMsgFeature().handleProxyMessageLog(
                        carrier, messageSenderId, messageSenderName, messageTargetId, messageTargetName, messageText
                     );
                  }
                  return;
               case "teleport-status":
                  String teleportStatus = input.readUTF();
                  if (input.available() == 0 && teleportStatus.equals("teleport-started")) {
                     this.send(carrier, "&aDer Teleport wird ausgeführt.");
                  }

                  return;
               case "teleport-execute":
                  UUID moverId = UUID.fromString(input.readUTF());
                  UUID destinationId = UUID.fromString(input.readUTF());
                  String actorName = input.readUTF();
                  String type = input.readUTF();
                  if (input.available() == 0
                     && carrier.getUniqueId().equals(moverId)
                     && actorName.matches("[A-Za-z0-9_]{1,16}")
                     && List.of("TP", "TP-HERE", "TPA", "TPA-HERE").contains(type)) {
                     Bukkit.getScheduler().runTask(this.plugin, () -> {
                        Player destination = Bukkit.getPlayer(destinationId);
                        if (destination == null) {
                           this.sendPath(carrier, "messages.player-not-found", "&cDer Zielspieler ist nicht mehr auf diesem Server.");
                        } else if (!this.performTeleport(carrier, destination, actorName, type)) {
                           this.sendPath(carrier, "messages.teleport-failed", "&cDer Teleport ist fehlgeschlagen.");
                        } else {
                           this.sendPath(carrier, "messages.teleported", "&aTeleportiert.");
                        }
                     });
                     return;
                  }

                  return;
            }
         } catch (IOException | IllegalArgumentException exception) {
            this.plugin.getLogger().fine("Ungültige Proxy-Spielerliste wurde ignoriert: " + exception.getMessage());
         }
      }
   }

   private void handleProxyTpaStatus(Player player, String status, String value) {
      switch (status) {
         case "request-sent":
            this.send(player, this.config.getString("messages.request-sent", "&aTeleport-Anfrage an &e%player% &agesendet.").replace("%player%", value));
            break;
         case "request-accepted":
            this.send(player, this.config.getString("messages.request-accepted", "&aTeleport-Anfrage von &e%player% &aangenommen.").replace("%player%", value));
            break;
         case "request-accepted-by":
            this.send(
               player, this.config.getString("messages.request-accepted-by", "&e%player% &ahat deine Teleport-Anfrage angenommen.").replace("%player%", value)
            );
            break;
         case "request-denied":
            this.proxyPendingReceivers.remove(player.getUniqueId());
            this.send(player, this.config.getString("messages.request-denied", "&cTeleport-Anfrage von &e%player% &cabgelehnt.").replace("%player%", value));
            break;
         case "request-denied-by":
            this.send(
               player, this.config.getString("messages.request-denied-by", "&e%player% &chat deine Teleport-Anfrage abgelehnt.").replace("%player%", value)
            );
            break;
         case "no-request":
            this.proxyPendingReceivers.remove(player.getUniqueId());
            this.sendPath(player, "messages.no-request", "&cDu hast keine offene Teleport-Anfrage.");
            break;
         case "request-expired":
            this.proxyPendingReceivers.remove(player.getUniqueId());
            this.sendPath(player, "messages.request-expired", "&cDiese Teleport-Anfrage ist abgelaufen.");
            break;
         case "player-not-found":
            this.sendPath(player, "messages.player-not-found", "&cDieser Spieler ist nicht online.");
            break;
         case "cannot-target-self":
            this.sendPath(player, "messages.cannot-target-self", "&cDu kannst dich nicht selbst auswählen.");
            break;
         case "auto-enabled":
            this.sendPath(player, "messages.auto-enabled", "&aAutomatisches Annehmen von TPA-Anfragen ist aktiviert.");
            break;
         case "auto-disabled":
            this.sendPath(player, "messages.auto-disabled", "&cAutomatisches Annehmen von TPA-Anfragen ist deaktiviert.");
      }
   }

   private boolean hasFreshProxySnapshot() {
      return this.proxySnapshotAt > 0L && System.currentTimeMillis() - this.proxySnapshotAt <= 15000L;
   }

   private void requestProxyPlayerList() {
      Player carrier = (Player)Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
      if (carrier != null) {
         this.requestProxyPlayerList(carrier);
      }
   }

   private void requestProxyPlayerList(Player carrier) {
      try {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream();

         try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("players");
         }

         carrier.sendPluginMessage(this.plugin, "greenattack:home_connect", bytes.toByteArray());
      } catch (IOException exception) {
         this.plugin.getLogger().fine("Proxy-Spielerliste konnte nicht angefragt werden: " + exception.getMessage());
      }
   }

   private boolean sendProxyTpaRequest(Player requester, String targetName, boolean here, boolean autoAcceptEnabled) {
      long timeoutMillis = Math.max(0L, this.config.getLong("request.expires-seconds", 0L)) * 1000L;
      return this.sendProxyPacket(requester, output -> {
         output.writeUTF("tpa-request");
         output.writeUTF(targetName);
         output.writeBoolean(here);
         output.writeLong(timeoutMillis);
         output.writeBoolean(autoAcceptEnabled);
      });
   }

   private boolean sendProxyTpaControl(Player player, String action) {
      return this.sendProxyPacket(player, output -> output.writeUTF(action));
   }

   private boolean sendProxyTpaAuto(Player player, boolean enabled) {
      return this.sendProxyPacket(player, output -> {
         output.writeUTF("tpa-auto");
         output.writeBoolean(enabled);
      });
   }

   private boolean sendProxyTeleport(Player actor, UUID moverId, UUID destinationId, String type) {
      return this.sendProxyPacket(actor, output -> {
         output.writeUTF("teleport");
         output.writeUTF(actor.getUniqueId().toString());
         output.writeUTF(moverId.toString());
         output.writeUTF(destinationId.toString());
         output.writeUTF(type);
      });
   }

   private boolean sendProxyPacket(Player carrier, TeleportFeature.ProxyPacketWriter writer) {
      try {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream();

         try (DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
         }

         carrier.sendPluginMessage(this.plugin, "greenattack:home_connect", bytes.toByteArray());
         return true;
      } catch (IOException exception) {
         this.plugin.getLogger().warning("Velocity-Nachricht konnte nicht erstellt werden: " + exception.getMessage());
         return false;
      }
   }

   private record NetworkPlayer(UUID uuid, String name, String serverId) {
   }

   private record NetworkTeleportRequest(UUID requesterUuid, String requesterName, String requesterServer, boolean here, long createdAt, long expiresAt) {
      private boolean expired() {
         return this.expiresAt > 0L && System.currentTimeMillis() >= this.expiresAt;
      }
   }

   @FunctionalInterface
   private interface ProxyPacketWriter {
      void write(DataOutputStream var1) throws IOException;
   }

   private record TeleportRequest(UUID requester, UUID receiver, boolean here, long createdAt, long timeoutMillis) {
      private boolean isExpired() {
         return this.timeoutMillis > 0L && System.currentTimeMillis() - this.createdAt >= this.timeoutMillis;
      }
   }

   private static final class TeleportStorageException extends RuntimeException {
      private TeleportStorageException(Throwable cause) {
         super(cause);
      }
   }
}

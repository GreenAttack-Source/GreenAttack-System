package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class MsgFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   private static final String USE_PERMISSION = "system.msg.use";
   private static final String LOG_PERMISSION = "system.msg.see";
   private static final AtomicInteger THREAD_NUMBER = new AtomicInteger();
   private ExecutorService databaseExecutor;
   private DatabaseSettings databaseSettings;
   private boolean databaseConfigured;
   private volatile boolean mysqlReady;

   public MsgFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "msg");
      this.ensureDefaults();
      this.initializeStorage();
   }

   public void reloadFeature() {
      super.reload();
      this.ensureDefaults();
      this.initializeStorage();
   }

   public void shutdownFeature() {
      if (this.databaseExecutor != null) {
         this.databaseExecutor.shutdown();
      }
   }

   private void ensureDefaults() {
      boolean changed = false;
      Map<String, Object> defaults = Map.ofEntries(
         Map.entry("enabled", true),
         Map.entry("database.enabled", false),
         Map.entry("database.jdbc-url", ""),
         Map.entry("database.host", ""),
         Map.entry("database.port", 3306),
         Map.entry("database.name", ""),
         Map.entry("database.user", ""),
         Map.entry("database.password", ""),
         Map.entry("database.server-id", ""),
         Map.entry("database.table-name", "greenattack_messages"),
         Map.entry("database.connection-timeout-ms", 3000),
         Map.entry("logs.page-size", 15),
         Map.entry("logs.timezone", "Europe/Berlin"),
         Map.entry("permissions.use", USE_PERMISSION),
         Map.entry("permissions.see", LOG_PERMISSION),
         Map.entry("messages.disabled", "&cDas Nachrichten-System ist deaktiviert."),
         Map.entry("messages.no-permission", "&cDafür hast du keine Berechtigung."),
         Map.entry("messages.player-only", "&cDiesen Befehl können nur Spieler verwenden."),
         Map.entry("messages.usage", "&7Verwendung: &f/msg <Spieler> <Nachricht>"),
         Map.entry("messages.logs-usage", "&7Verwendung: &f/msglogs [Seite]"),
         Map.entry("messages.player-not-found", "&cDieser Spieler ist auf diesem Server nicht online."),
         Map.entry("messages.player-not-found-network", "&cDieser Spieler ist im Netzwerk nicht online."),
         Map.entry("messages.cannot-message-self", "&cDu kannst dir selbst keine Nachricht senden."),
         Map.entry("messages.sent", "&8[&aDu &7-> &e%player%&8] &f%message%"),
         Map.entry("messages.received", "&8[&e%player% &7-> &aDu&8] &f%message%"),
         Map.entry("messages.logs-unavailable", "&cDie Nachrichtenlogs sind nicht verfügbar. Aktiviere und konfiguriere MySQL in features/msg/config.yml."),
         Map.entry("messages.log-header", "&6Nachrichtenlogs &7(Seite &e%page%&7/&e%pages%&7):"),
         Map.entry("messages.log-entry", "&8%time% &e%sender% &7-> &e%recipient% &8(&b%server%&8)&7: &f%message%"),
         Map.entry("messages.log-empty", "&eEs wurden noch keine Nachrichten gespeichert."),
         Map.entry("messages.page-invalid", "&cDie Seite muss eine Zahl ab 1 sein."),
         Map.entry("messages.page-not-found", "&cDiese Log-Seite gibt es nicht."),
         Map.entry("messages.database-error", "&cDie Nachrichtenlogs konnten gerade nicht geladen werden.")
      );

      for (Map.Entry<String, Object> entry : defaults.entrySet()) {
         if (!this.config.contains(entry.getKey())) {
            this.config.set(entry.getKey(), entry.getValue());
            changed = true;
         }
      }

      if (changed) {
         this.save();
      }
   }

   private void initializeStorage() {
      if (this.databaseExecutor != null) {
         this.databaseExecutor.shutdown();
      }

      ThreadFactory threadFactory = task -> {
         Thread thread = new Thread(task, "GreenAttack-MSG-DB-" + THREAD_NUMBER.incrementAndGet());
         thread.setDaemon(true);
         return thread;
      };
      this.databaseExecutor = Executors.newSingleThreadExecutor(threadFactory);
      this.databaseConfigured = this.isEnabled() && this.config.getBoolean("database.enabled", false);
      this.databaseSettings = this.readDatabaseSettings();
      this.mysqlReady = false;

      if (!this.isEnabled()) {
         this.plugin.getLogger().info("Das MSG-Feature ist deaktiviert.");
      } else if (!this.databaseConfigured) {
         this.plugin.getLogger().info("MSG ist aktiv; MySQL-Nachrichtenlogs sind in features/msg/config.yml noch deaktiviert.");
      } else if (this.databaseSettings == null) {
         this.plugin.getLogger().severe("MSG-MySQL ist unvollständig konfiguriert. Prüfe plugins/GreenAttack-System/features/msg/config.yml.");
      } else {
         DatabaseSettings settings = this.databaseSettings;
         this.databaseExecutor.execute(() -> this.createTable(settings));
      }
   }

   private DatabaseSettings readDatabaseSettings() {
      if (!this.databaseConfigured) {
         return null;
      }

      String table = this.config.getString("database.table-name", "greenattack_messages").trim();
      String user = this.config.getString("database.user", "").trim();
      String password = this.config.getString("database.password", "");
      String serverId = this.config.getString("database.server-id", "").trim();
      String url = this.config.getString("database.jdbc-url", "").trim();
      if (!table.matches("[A-Za-z0-9_]{1,48}") || user.isBlank() || serverId.isBlank() || serverId.length() > 64) {
         return null;
      }

      if (url.isEmpty()) {
         String host = this.config.getString("database.host", "").trim();
         String database = this.config.getString("database.name", "").trim();
         if (host.isBlank() || database.isBlank()) {
            return null;
         }

         int port = Math.max(1, Math.min(65535, this.config.getInt("database.port", 3306)));
         int timeout = Math.max(1000, this.config.getInt("database.connection-timeout-ms", 3000));
         url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?connectTimeout=" + timeout + "&socketTimeout=5000&autoReconnect=true";
      }

      if (!url.startsWith("jdbc:mysql:") && !url.startsWith("jdbc:mariadb:")) {
         return null;
      }

      String zoneName = this.config.getString("logs.timezone", "Europe/Berlin").trim();
      ZoneId zone;
      try {
         zone = ZoneId.of(zoneName);
      } catch (Exception ignored) {
         zone = ZoneId.of("Europe/Berlin");
      }

      String logHeader = this.config.getString("messages.log-header", "&6Nachrichtenlogs &7(Seite &e%page%&7/&e%pages%&7):");
      String logEntry = this.config.getString("messages.log-entry", "&8%time% &e%sender% &7-> &e%recipient% &8(&b%server%&8)&7: &f%message%");
      return new DatabaseSettings(url, user, password, serverId, table, zone, logHeader, logEntry);
   }

   private void createTable(DatabaseSettings settings) {
      String driver = settings.jdbcUrl().startsWith("jdbc:mariadb:") ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
      try {
         Class.forName(driver);
         try (Connection connection = this.openConnection(settings); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
               "CREATE TABLE IF NOT EXISTS `" + settings.tableName()
                  + "` (`message_id` BIGINT NOT NULL AUTO_INCREMENT, `sender_uuid` CHAR(36) NOT NULL, `sender_name` VARCHAR(32) NOT NULL, `recipient_uuid` CHAR(36) NOT NULL, `recipient_name` VARCHAR(32) NOT NULL, `message_text` TEXT NOT NULL, `server_id` VARCHAR(64) NOT NULL, `sent_at_ms` BIGINT NOT NULL, PRIMARY KEY (`message_id`), INDEX `idx_gc_msg_sent` (`sent_at_ms`), INDEX `idx_gc_msg_sender` (`sender_uuid`), INDEX `idx_gc_msg_recipient` (`recipient_uuid`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
         }
         this.mysqlReady = true;
         this.plugin.getLogger().info("MSG-MySQL ist bereit; Logs werden in Tabelle '" + settings.tableName() + "' gespeichert.");
      } catch (Exception exception) {
         this.mysqlReady = false;
         this.plugin.getLogger().severe("MSG-MySQL konnte nicht initialisiert werden. Prüfe features/msg/config.yml: " + exception.getMessage());
      }
   }

   private Connection openConnection(DatabaseSettings settings) throws SQLException {
      return DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Nachrichten-System ist deaktiviert.");
         return true;
      }

      if (command.getName().equalsIgnoreCase("msglogs")) {
         return this.showLogs(sender, args);
      }

      if (!this.hasFeaturePermission(sender, this.config.getString("permissions.use", USE_PERMISSION))) {
         this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return true;
      }

      if (args.length < 2) {
         this.sendPath(sender, "messages.usage", "&7Verwendung: &f/msg <Spieler> <Nachricht>");
         return true;
      }

      String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
      String safeMessage = message.replace('§', ' ').replace('&', '＆');
      if (safeMessage.length() > 256) {
         this.sendPath(sender, "messages.message-too-long", "&cDeine Nachricht ist zu lang (maximal 256 Zeichen).");
         return true;
      }

      Player target = Bukkit.getPlayerExact(args[0]);
      if (sender instanceof Player player) {
         if (target != null && player.getUniqueId().equals(target.getUniqueId())) {
            this.sendPath(sender, "messages.cannot-message-self", "&cDu kannst dir selbst keine Nachricht senden.");
            return true;
         }

         if (target != null) {
            this.send(sender, this.config.getString("messages.sent", "&8[&aDu &7-> &e%player%&8] &f%message%")
               .replace("%player%", target.getName()).replace("%message%", safeMessage));
            this.send(target, this.config.getString("messages.received", "&8[&e%player% &7-> &aDu&8] &f%message%")
               .replace("%player%", player.getName()).replace("%message%", safeMessage));
            this.logMessage(player.getUniqueId(), player.getName(), target.getUniqueId(), target.getName(), safeMessage);
         } else {
            TeleportFeature teleport = this.plugin.getTeleportFeature();
            String sentLine = this.proxyLine(player, this.config.getString("messages.sent", "&8[&aDu &7-> &e%player%&8] &f%message%")
               .replace("%player%", args[0]).replace("%message%", safeMessage));
            String receivedLine = this.proxyLine(player, this.config.getString("messages.received", "&8[&e%player% &7-> &aDu&8] &f%message%")
               .replace("%player%", player.getName()).replace("%message%", safeMessage));
            String notFoundLine = this.proxyLine(player, this.config.getString("messages.player-not-found-network", "&cDieser Spieler ist im Netzwerk nicht online."));
            String selfLine = this.proxyLine(player, this.config.getString("messages.cannot-message-self", "&cDu kannst dir selbst keine Nachricht senden."));
            if (teleport == null || !teleport.sendProxyMessageRequest(player, args[0], safeMessage, sentLine, receivedLine, notFoundLine, selfLine)) {
               this.sendPath(sender, "messages.player-not-found-network", "&cDieser Spieler ist im Netzwerk nicht online.");
            }
         }
      } else {
         if (target == null) {
            this.sendPath(sender, "messages.player-not-found", "&cDieser Spieler ist im Netzwerk nicht online.");
            return true;
         }
         this.send(target, this.config.getString("messages.received", "&8[&e%player% &7-> &aDu&8] &f%message%")
            .replace("%player%", sender.getName()).replace("%message%", safeMessage));
         this.send(sender, this.config.getString("messages.sent", "&8[&aDu &7-> &e%player%&8] &f%message%")
            .replace("%player%", target.getName()).replace("%message%", safeMessage));
         this.logMessage(new UUID(0L, 0L), sender.getName(), target.getUniqueId(), target.getName(), safeMessage);
      }
      return true;
   }

   private String proxyLine(Player player, String line) {
      String prefix = this.plugin.getConfig().getString("messages.prefix", "&2GreenAttack-Plugin &8» &7");
      return this.plugin.applyPlaceholders(player, prefix + line);
   }

   public void handleProxyMessageLog(Player carrier, UUID senderId, String senderName, UUID targetId, String targetName, String message) {
      if (carrier.getUniqueId().equals(senderId)) {
         this.logMessage(senderId, senderName, targetId, targetName, message);
      }
   }

   private boolean showLogs(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, this.config.getString("permissions.see", LOG_PERMISSION))) {
         this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return true;
      }

      if (args.length > 1) {
         this.sendPath(sender, "messages.logs-usage", "&7Verwendung: &f/msglogs [Seite]");
         return true;
      }

      int page = 1;
      if (args.length == 1) {
         try {
            page = Integer.parseInt(args[0]);
            if (page < 1) {
               throw new NumberFormatException();
            }
         } catch (NumberFormatException exception) {
            this.sendPath(sender, "messages.page-invalid", "&cDie Seite muss eine Zahl ab 1 sein.");
            return true;
         }
      }

      if (!this.databaseConfigured || this.databaseSettings == null || this.databaseExecutor == null) {
         this.sendPath(sender, "messages.logs-unavailable", "&cDie Nachrichtenlogs sind nicht verfügbar. Aktiviere und konfiguriere MySQL in features/msg/config.yml.");
         return true;
      }

      int pageSize = Math.max(1, Math.min(100, this.config.getInt("logs.page-size", 15)));
      int requestedPage = page;
      DatabaseSettings settings = this.databaseSettings;
      this.databaseExecutor.execute(() -> this.fetchLogs(sender, settings, requestedPage, pageSize));
      return true;
   }

   private void fetchLogs(CommandSender sender, DatabaseSettings settings, int requestedPage, int pageSize) {
      if (!this.mysqlReady) {
         this.runOnMainThread(() -> this.sendPath(sender, "messages.logs-unavailable", "&cDie Nachrichtenlogs sind nicht verfügbar. Aktiviere und konfiguriere MySQL in features/msg/config.yml."));
         return;
      }
      try (Connection connection = this.openConnection(settings)) {
         long total;
         try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + settings.tableName() + "`")) {
            total = result.next() ? result.getLong(1) : 0L;
         }

         if (total == 0L) {
            this.runOnMainThread(() -> this.sendPath(sender, "messages.log-empty", "&eEs wurden noch keine Nachrichten gespeichert."));
            return;
         }

         int totalPages = (int)Math.max(1L, (total + pageSize - 1L) / pageSize);
         if (requestedPage > totalPages) {
            this.runOnMainThread(() -> this.sendPath(sender, "messages.page-not-found", "&cDiese Log-Seite gibt es nicht."));
            return;
         }

         long offset = (long)(requestedPage - 1) * pageSize;
         List<String> lines = new ArrayList<>();
         String sql = "SELECT `sender_name`,`recipient_name`,`message_text`,`server_id`,`sent_at_ms` FROM `" + settings.tableName()
            + "` ORDER BY `message_id` DESC LIMIT ? OFFSET ?";
         try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, pageSize);
            statement.setLong(2, offset);
            try (ResultSet result = statement.executeQuery()) {
               DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).withZone(settings.timeZone());
               while (result.next()) {
                  String time = formatter.format(Instant.ofEpochMilli(result.getLong("sent_at_ms")));
                  String line = settings.logEntryTemplate()
                     .replace("%time%", time)
                     .replace("%sender%", result.getString("sender_name"))
                     .replace("%recipient%", result.getString("recipient_name"))
                     .replace("%server%", result.getString("server_id"))
                     .replace("%message%", result.getString("message_text").replace('&', '＆').replace('§', ' '));
                  lines.add(line);
               }
            }
         }

         String header = settings.logHeaderTemplate()
            .replace("%page%", Integer.toString(requestedPage)).replace("%pages%", Integer.toString(totalPages));
         this.runOnMainThread(() -> {
            this.send(sender, header);
            for (String line : lines) {
               this.send(sender, line);
            }
         });
      } catch (SQLException exception) {
         this.plugin.getLogger().warning("MSG-Logs konnten nicht aus MySQL gelesen werden: " + exception.getMessage());
         this.runOnMainThread(() -> this.sendPath(sender, "messages.database-error", "&cDie Nachrichtenlogs konnten gerade nicht geladen werden."));
      }
   }

   private void logMessage(UUID senderUuid, String senderName, UUID recipientUuid, String recipientName, String message) {
      if (!this.databaseConfigured || this.databaseSettings == null || this.databaseExecutor == null) {
         return;
      }

      DatabaseSettings settings = this.databaseSettings;
      long sentAt = System.currentTimeMillis();
      this.databaseExecutor.execute(() -> {
         if (!this.mysqlReady) {
            return;
         }
         String sql = "INSERT INTO `" + settings.tableName()
            + "` (`sender_uuid`,`sender_name`,`recipient_uuid`,`recipient_name`,`message_text`,`server_id`,`sent_at_ms`) VALUES (?,?,?,?,?,?,?)";
         try (Connection connection = this.openConnection(settings); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, senderUuid.toString());
            statement.setString(2, senderName);
            statement.setString(3, recipientUuid.toString());
            statement.setString(4, recipientName);
            statement.setString(5, message);
            statement.setString(6, settings.serverId());
            statement.setLong(7, sentAt);
            statement.executeUpdate();
         } catch (SQLException exception) {
            this.plugin.getLogger().warning("MSG konnte gesendet, aber nicht im MySQL-Log gespeichert werden: " + exception.getMessage());
         }
      });
   }

   private void runOnMainThread(Runnable task) {
      if (!this.plugin.isEnabled()) {
         return;
      }
      try {
         Bukkit.getScheduler().runTask(this.plugin, task);
      } catch (IllegalStateException ignored) {
      }
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      String usePermission = this.config.getString("permissions.use", USE_PERMISSION);
      if (command.getName().equalsIgnoreCase("msg") && args.length == 1 && this.hasFeaturePermission(sender, usePermission)) {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         if (this.plugin.getTeleportFeature() != null) {
            return this.plugin.getTeleportFeature().onlineNetworkNamesForCompletion(sender, prefix);
         }
         return Bukkit.getOnlinePlayers().stream().map(Player::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
      }
      return List.of();
   }

   private record DatabaseSettings(
      String jdbcUrl,
      String user,
      String password,
      String serverId,
      String tableName,
      ZoneId timeZone,
      String logHeaderTemplate,
      String logEntryTemplate
   ) {
   }
}

package de.greenattack.system.features;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Speicher des Clan-Systems. Alle Methoden blockieren und dürfen nur außerhalb des Server-Threads laufen. */
interface ClanStorage {
   Snapshot loadSnapshot() throws Exception;

   CreateResult createClan(ClanData clan, UUID leader, String leaderName, String tagKey) throws Exception;

   AddResult addMember(String clanKey, UUID uuid, String name, int maxMembers) throws Exception;

   boolean removeMember(UUID uuid) throws Exception;

   void setMemberRole(UUID uuid, String roleKey) throws Exception;

   void transferOwnership(String clanKey, UUID from, UUID to) throws Exception;

   boolean disband(String clanKey) throws Exception;

   void setColor(String clanKey, String color) throws Exception;

   void setTagStyle(String clanKey, String style) throws Exception;

   /** false, wenn der Tag schon vergeben ist. */
   boolean setTag(String clanKey, String tag, String tagKey) throws Exception;

   /** false, wenn der neue Name schon vergeben ist. */
   boolean renameClan(String oldKey, String newKey, String newName) throws Exception;

   void setJoinMode(String clanKey, JoinMode mode) throws Exception;

   RoleResult createRole(String clanKey, RoleData role, int maxCustomRoles) throws Exception;

   void updateRole(String clanKey, String roleKey, int weight, Set<String> permissions) throws Exception;

   boolean deleteRole(String clanKey, String roleKey) throws Exception;

   HomeResult setHome(HomeData home, int maxHomes) throws Exception;

   boolean deleteHome(String clanKey, String name) throws Exception;

   /** Datenbank-Einstellungen aus features/clans/config.yml (Abschnitt database). */
   final class DbSettings {
      final String driverClass;
      final String url;
      final String user;
      final String password;
      final String prefix;
      final String shown;

      private DbSettings(String driverClass, String url, String user, String password, String prefix, String shown) {
         this.driverClass = driverClass;
         this.url = url;
         this.user = user;
         this.password = password;
         this.prefix = prefix;
         this.shown = shown;
      }

      static DbSettings fromConfig(boolean enabled, String type, String jdbcUrl, String host, int port, String name, String user, String password, String prefix) {
         if (!enabled) {
            return null;
         }
         boolean mariadb = type != null && type.trim().equalsIgnoreCase("mariadb");
         String cleanPrefix = prefix == null ? "" : prefix.trim();
         if (!cleanPrefix.matches("[A-Za-z0-9_]{0,32}")) {
            cleanPrefix = "gc_";
         }

         String url = jdbcUrl == null ? "" : jdbcUrl.trim();
         String shown = url;
         if (url.isEmpty()) {
            String cleanHost = host == null ? "" : host.trim();
            String cleanName = name == null ? "" : name.trim();
            int cleanPort = Math.max(1, Math.min(65535, port));
            if (!cleanHost.isEmpty() && !cleanName.isEmpty()) {
               String base = (mariadb ? "jdbc:mariadb://" : "jdbc:mysql://") + cleanHost + ":" + cleanPort + "/" + cleanName;
               url = base + (mariadb ? "?connectTimeout=5000&socketTimeout=10000" : "?connectTimeout=5000&socketTimeout=10000&characterEncoding=UTF-8");
               shown = cleanHost + ":" + cleanPort + "/" + cleanName;
            }
         } else {
            mariadb = url.startsWith("jdbc:mariadb:");
            shown = "(jdbc-url)";
         }

         String driver = mariadb ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
         return new DbSettings(driver, url, user == null ? "" : user.trim(), password == null ? "" : password, cleanPrefix, shown);
      }

      String signature() {
         return this.url + "|" + this.user + "|" + this.password.hashCode() + "|" + this.prefix;
      }

      String describe() {
         return this.shown + " (Tabellen " + this.prefix + "clans, " + this.prefix + "clan_members, " + this.prefix + "clan_roles, " + this.prefix + "clan_homes)";
      }
   }

   interface SqlWork<T> {
      T run(Connection connection) throws SQLException;
   }

   /** MySQL/MariaDB. Jede Operation öffnet eine eigene Verbindung; Tabellen und Migrationen laufen automatisch. */
   final class SqlClanStorage implements ClanStorage {
      private final DbSettings settings;
      private final Defaults defaults;
      private final String clans;
      private final String members;
      private final String roles;
      private final String homes;
      private boolean ready;

      SqlClanStorage(DbSettings settings, Defaults defaults) {
         this.settings = settings;
         this.defaults = defaults;
         this.clans = "`" + settings.prefix + "clans`";
         this.members = "`" + settings.prefix + "clan_members`";
         this.roles = "`" + settings.prefix + "clan_roles`";
         this.homes = "`" + settings.prefix + "clan_homes`";
      }

      private Connection connect() throws SQLException {
         if (this.settings.url.isEmpty() || this.settings.user.isEmpty()) {
            throw new SQLException("database.host/name/user sind in features/clans/config.yml noch nicht gesetzt.");
         }
         try {
            Class.forName(this.settings.driverClass);
         } catch (ClassNotFoundException exception) {
            throw new SQLException("JDBC-Treiber nicht gefunden: " + this.settings.driverClass, exception);
         }
         Connection connection = DriverManager.getConnection(this.settings.url, this.settings.user, this.settings.password);
         if (!this.ready) {
            try {
               this.prepareTables(connection);
            } catch (SQLException exception) {
               connection.close();
               throw exception;
            }
            this.ready = true;
         }
         return connection;
      }

      private void prepareTables(Connection connection) throws SQLException {
         try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + this.clans + " ("
               + "clan_key VARCHAR(24) NOT NULL, clan_name VARCHAR(24) NOT NULL, tag VARCHAR(8) NOT NULL, tag_key VARCHAR(8) NOT NULL, "
               + "color VARCHAR(16) NOT NULL, tag_style VARCHAR(16) NOT NULL DEFAULT 'normal', created_at BIGINT NOT NULL, "
               + "join_mode VARCHAR(8) NOT NULL DEFAULT 'invite', "
               + "PRIMARY KEY (clan_key), UNIQUE KEY uk_tag_key (tag_key)) DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + this.members + " ("
               + "player_uuid CHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, clan_key VARCHAR(24) NOT NULL, "
               + "member_role VARCHAR(16) NOT NULL, joined_at BIGINT NOT NULL, PRIMARY KEY (player_uuid), "
               + "KEY idx_clan_key (clan_key), KEY idx_member_name (player_name)) DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + this.roles + " ("
               + "clan_key VARCHAR(24) NOT NULL, role_key VARCHAR(16) NOT NULL, role_name VARCHAR(16) NOT NULL, "
               + "weight INT NOT NULL, perms VARCHAR(255) NOT NULL, system_role TINYINT NOT NULL DEFAULT 0, "
               + "PRIMARY KEY (clan_key, role_key)) DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + this.homes + " ("
               + "clan_key VARCHAR(24) NOT NULL, home_name VARCHAR(16) NOT NULL, server_id VARCHAR(64) NOT NULL, "
               + "world VARCHAR(64) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
               + "yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, created_by VARCHAR(32) NOT NULL, "
               + "PRIMARY KEY (clan_key, home_name)) DEFAULT CHARSET=utf8mb4");

            // Migration von älteren Tabellen (vor Rollen/Beitrittsmodus)
            try {
               statement.executeUpdate("ALTER TABLE " + this.clans + " ADD COLUMN join_mode VARCHAR(8) NOT NULL DEFAULT 'invite'");
            } catch (SQLException exception) {
               if (exception.getErrorCode() != 1060 && !"42S21".equals(exception.getSQLState())) {
                  throw exception;
               }
            }
            try {
               statement.executeUpdate("ALTER TABLE " + this.clans + " ADD COLUMN tag_style VARCHAR(16) NOT NULL DEFAULT 'normal'");
            } catch (SQLException exception) {
               if (exception.getErrorCode() != 1060 && !"42S21".equals(exception.getSQLState())) {
                  throw exception;
               }
            }
            statement.executeUpdate("ALTER TABLE " + this.members + " MODIFY member_role VARCHAR(16) NOT NULL");
            statement.executeUpdate("UPDATE " + this.members + " SET member_role = 'owner' WHERE member_role = 'LEADER'");
            statement.executeUpdate("UPDATE " + this.members + " SET member_role = 'member' WHERE member_role IN ('MODERATOR', 'MEMBER')");
         }
         for (RoleData role : new RoleData[] {this.defaults.ownerRole(), this.defaults.memberRole()}) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT IGNORE INTO " + this.roles
               + " (clan_key, role_key, role_name, weight, perms, system_role) SELECT clan_key, ?, ?, ?, ?, 1 FROM " + this.clans)) {
               insert.setString(1, role.key);
               insert.setString(2, role.name);
               insert.setInt(3, role.weight);
               insert.setString(4, ClanModel.joinPermissions(role.permissions));
               insert.executeUpdate();
            }
         }
      }

      private <T> T read(SqlWork<T> work) throws SQLException {
         try (Connection connection = this.connect()) {
            return work.run(connection);
         }
      }

      private <T> T transaction(SqlWork<T> work) throws SQLException {
         try (Connection connection = this.connect()) {
            connection.setAutoCommit(false);
            try {
               T result = work.run(connection);
               connection.commit();
               return result;
            } catch (SQLException | RuntimeException exception) {
               connection.rollback();
               throw exception;
            }
         }
      }

      private static boolean isDuplicate(SQLException exception) {
         String state = exception.getSQLState();
         return exception.getErrorCode() == 1062 || (state != null && state.startsWith("23"));
      }

      private static int execute(Connection connection, String sql, Object... values) throws SQLException {
         try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
               Object value = values[index];
               if (value instanceof Integer number) {
                  statement.setInt(index + 1, number);
               } else if (value instanceof Long number) {
                  statement.setLong(index + 1, number);
               } else if (value instanceof Double number) {
                  statement.setDouble(index + 1, number);
               } else if (value instanceof Float number) {
                  statement.setFloat(index + 1, number);
               } else {
                  statement.setString(index + 1, String.valueOf(value));
               }
            }
            return statement.executeUpdate();
         }
      }

      private static boolean exists(Connection connection, String sql, String... values) throws SQLException {
         try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
               statement.setString(index + 1, values[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
               return rows.next();
            }
         }
      }

      private static int count(Connection connection, String sql, String value) throws SQLException {
         try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
               return rows.next() ? rows.getInt(1) : 0;
            }
         }
      }

      @Override
      public Snapshot loadSnapshot() throws SQLException {
         return this.read(connection -> {
            Map<String, ClanData> clanMap = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT clan_key, clan_name, tag, color, tag_style, created_at, join_mode FROM " + this.clans);
               ResultSet rows = statement.executeQuery()) {
               while (rows.next()) {
                  clanMap.put(rows.getString(1), new ClanData(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                     rows.getString(5), rows.getLong(6), JoinMode.parse(rows.getString(7))));
               }
            }
            List<String> roleClans = new ArrayList<>();
            List<RoleData> roleRows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT clan_key, role_key, role_name, weight, perms, system_role FROM " + this.roles);
               ResultSet rows = statement.executeQuery()) {
               while (rows.next()) {
                  if (clanMap.containsKey(rows.getString(1))) {
                     roleClans.add(rows.getString(1));
                     roleRows.add(new RoleData(rows.getString(2), rows.getString(3), rows.getInt(4), ClanModel.parsePermissions(rows.getString(5)), rows.getInt(6) != 0));
                  }
               }
            }
            Map<UUID, MemberData> memberMap = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT player_uuid, player_name, clan_key, member_role, joined_at FROM " + this.members);
               ResultSet rows = statement.executeQuery()) {
               while (rows.next()) {
                  try {
                     UUID id = UUID.fromString(rows.getString(1));
                     if (clanMap.containsKey(rows.getString(3))) {
                        memberMap.put(id, new MemberData(id, rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5)));
                     }
                  } catch (IllegalArgumentException ignored) {
                     // kaputte Zeile überspringen
                  }
               }
            }
            List<HomeData> homeRows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT clan_key, home_name, server_id, world, x, y, z, yaw, pitch, created_by FROM " + this.homes);
               ResultSet rows = statement.executeQuery()) {
               while (rows.next()) {
                  if (clanMap.containsKey(rows.getString(1))) {
                     homeRows.add(new HomeData(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getDouble(5), rows.getDouble(6), rows.getDouble(7), rows.getFloat(8), rows.getFloat(9), rows.getString(10)));
                  }
               }
            }
            return new Snapshot(clanMap, memberMap, roleClans, roleRows, homeRows, this.defaults);
         });
      }

      @Override
      public CreateResult createClan(ClanData clan, UUID leader, String leaderName, String tagKey) throws SQLException {
         try {
            return this.transaction(connection -> {
               if (exists(connection, "SELECT 1 FROM " + this.members + " WHERE player_uuid = ?", leader.toString())) {
                  return CreateResult.ALREADY_IN_CLAN;
               }
               if (exists(connection, "SELECT 1 FROM " + this.clans + " WHERE clan_key = ?", clan.key)) {
                  return CreateResult.NAME_TAKEN;
               }
               if (exists(connection, "SELECT 1 FROM " + this.clans + " WHERE tag_key = ?", tagKey)) {
                  return CreateResult.TAG_TAKEN;
               }
               execute(connection, "INSERT INTO " + this.clans + " (clan_key, clan_name, tag, tag_key, color, created_at, join_mode) VALUES (?, ?, ?, ?, ?, ?, ?)",
                  clan.key, clan.name, clan.tag, tagKey, clan.color, clan.createdAt, clan.joinMode.key());
               for (RoleData role : new RoleData[] {this.defaults.ownerRole(), this.defaults.memberRole()}) {
                  execute(connection, "INSERT INTO " + this.roles + " (clan_key, role_key, role_name, weight, perms, system_role) VALUES (?, ?, ?, ?, ?, 1)",
                     clan.key, role.key, role.name, role.weight, ClanModel.joinPermissions(role.permissions));
               }
               execute(connection, "INSERT INTO " + this.members + " (player_uuid, player_name, clan_key, member_role, joined_at) VALUES (?, ?, ?, ?, ?)",
                  leader.toString(), leaderName, clan.key, ClanModel.OWNER, clan.createdAt);
               return CreateResult.OK;
            });
         } catch (SQLException exception) {
            if (isDuplicate(exception)) {
               String text = String.valueOf(exception.getMessage()).toLowerCase(Locale.ROOT);
               if (text.contains("tag")) {
                  return CreateResult.TAG_TAKEN;
               }
               return text.contains("player") ? CreateResult.ALREADY_IN_CLAN : CreateResult.NAME_TAKEN;
            }
            throw exception;
         }
      }

      @Override
      public AddResult addMember(String clanKey, UUID uuid, String name, int maxMembers) throws SQLException {
         try {
            return this.transaction(connection -> {
               if (exists(connection, "SELECT 1 FROM " + this.members + " WHERE player_uuid = ?", uuid.toString())) {
                  return AddResult.ALREADY_IN_CLAN;
               }
               if (!exists(connection, "SELECT 1 FROM " + this.clans + " WHERE clan_key = ?", clanKey)) {
                  return AddResult.NO_CLAN;
               }
               if (count(connection, "SELECT COUNT(*) FROM " + this.members + " WHERE clan_key = ?", clanKey) >= maxMembers) {
                  return AddResult.FULL;
               }
               execute(connection, "INSERT INTO " + this.members + " (player_uuid, player_name, clan_key, member_role, joined_at) VALUES (?, ?, ?, ?, ?)",
                  uuid.toString(), name, clanKey, ClanModel.MEMBER, System.currentTimeMillis());
               return AddResult.OK;
            });
         } catch (SQLException exception) {
            if (isDuplicate(exception)) {
               return AddResult.ALREADY_IN_CLAN;
            }
            throw exception;
         }
      }

      @Override
      public boolean removeMember(UUID uuid) throws SQLException {
         return this.read(connection -> execute(connection, "DELETE FROM " + this.members + " WHERE player_uuid = ?", uuid.toString()) > 0);
      }

      @Override
      public void setMemberRole(UUID uuid, String roleKey) throws SQLException {
         this.read(connection -> execute(connection, "UPDATE " + this.members + " SET member_role = ? WHERE player_uuid = ?", roleKey, uuid.toString()));
      }

      @Override
      public void transferOwnership(String clanKey, UUID from, UUID to) throws SQLException {
         this.transaction(connection -> {
            execute(connection, "UPDATE " + this.members + " SET member_role = ? WHERE player_uuid = ? AND clan_key = ?", ClanModel.MEMBER, from.toString(), clanKey);
            execute(connection, "UPDATE " + this.members + " SET member_role = ? WHERE player_uuid = ? AND clan_key = ?", ClanModel.OWNER, to.toString(), clanKey);
            return Boolean.TRUE;
         });
      }

      @Override
      public boolean disband(String clanKey) throws SQLException {
         return this.transaction(connection -> {
            execute(connection, "DELETE FROM " + this.homes + " WHERE clan_key = ?", clanKey);
            execute(connection, "DELETE FROM " + this.roles + " WHERE clan_key = ?", clanKey);
            execute(connection, "DELETE FROM " + this.members + " WHERE clan_key = ?", clanKey);
            return execute(connection, "DELETE FROM " + this.clans + " WHERE clan_key = ?", clanKey) > 0;
         });
      }

      @Override
      public void setColor(String clanKey, String color) throws SQLException {
         this.read(connection -> execute(connection, "UPDATE " + this.clans + " SET color = ? WHERE clan_key = ?", color, clanKey));
      }

      @Override
      public void setTagStyle(String clanKey, String style) throws SQLException {
         this.read(connection -> execute(connection, "UPDATE " + this.clans + " SET tag_style = ? WHERE clan_key = ?", style, clanKey));
      }

      @Override
      public boolean setTag(String clanKey, String tag, String tagKey) throws SQLException {
         try {
            this.read(connection -> execute(connection, "UPDATE " + this.clans + " SET tag = ?, tag_key = ? WHERE clan_key = ?", tag, tagKey, clanKey));
            return true;
         } catch (SQLException exception) {
            if (isDuplicate(exception)) {
               return false;
            }
            throw exception;
         }
      }

      @Override
      public boolean renameClan(String oldKey, String newKey, String newName) throws SQLException {
         return this.transaction(connection -> {
            if (!oldKey.equals(newKey)) {
               if (exists(connection, "SELECT 1 FROM " + this.clans + " WHERE clan_key = ?", newKey)) {
                  return false;
               }
               execute(connection, "UPDATE " + this.clans + " SET clan_key = ?, clan_name = ? WHERE clan_key = ?", newKey, newName, oldKey);
               execute(connection, "UPDATE " + this.members + " SET clan_key = ? WHERE clan_key = ?", newKey, oldKey);
               execute(connection, "UPDATE " + this.roles + " SET clan_key = ? WHERE clan_key = ?", newKey, oldKey);
               execute(connection, "UPDATE " + this.homes + " SET clan_key = ? WHERE clan_key = ?", newKey, oldKey);
            } else {
               execute(connection, "UPDATE " + this.clans + " SET clan_name = ? WHERE clan_key = ?", newName, oldKey);
            }
            return true;
         });
      }

      @Override
      public void setJoinMode(String clanKey, JoinMode mode) throws SQLException {
         this.read(connection -> execute(connection, "UPDATE " + this.clans + " SET join_mode = ? WHERE clan_key = ?", mode.key(), clanKey));
      }

      @Override
      public RoleResult createRole(String clanKey, RoleData role, int maxCustomRoles) throws SQLException {
         try {
            return this.transaction(connection -> {
               if (exists(connection, "SELECT 1 FROM " + this.roles + " WHERE clan_key = ? AND role_key = ?", clanKey, role.key)) {
                  return RoleResult.EXISTS;
               }
               if (count(connection, "SELECT COUNT(*) FROM " + this.roles + " WHERE system_role = 0 AND clan_key = ?", clanKey) >= maxCustomRoles) {
                  return RoleResult.LIMIT;
               }
               execute(connection, "INSERT INTO " + this.roles + " (clan_key, role_key, role_name, weight, perms, system_role) VALUES (?, ?, ?, ?, ?, 0)",
                  clanKey, role.key, role.name, role.weight, ClanModel.joinPermissions(role.permissions));
               return RoleResult.OK;
            });
         } catch (SQLException exception) {
            if (isDuplicate(exception)) {
               return RoleResult.EXISTS;
            }
            throw exception;
         }
      }

      @Override
      public void updateRole(String clanKey, String roleKey, int weight, Set<String> permissions) throws SQLException {
         this.read(connection -> execute(connection, "UPDATE " + this.roles + " SET weight = ?, perms = ? WHERE clan_key = ? AND role_key = ?",
            weight, ClanModel.joinPermissions(permissions), clanKey, roleKey));
      }

      @Override
      public boolean deleteRole(String clanKey, String roleKey) throws SQLException {
         return this.transaction(connection -> {
            execute(connection, "UPDATE " + this.members + " SET member_role = ? WHERE clan_key = ? AND member_role = ?", ClanModel.MEMBER, clanKey, roleKey);
            return execute(connection, "DELETE FROM " + this.roles + " WHERE clan_key = ? AND role_key = ? AND system_role = 0", clanKey, roleKey) > 0;
         });
      }

      @Override
      public HomeResult setHome(HomeData home, int maxHomes) throws SQLException {
         return this.transaction(connection -> {
            boolean replacing = exists(connection, "SELECT 1 FROM " + this.homes + " WHERE clan_key = ? AND home_name = ?", home.clanKey, home.name);
            if (!replacing && count(connection, "SELECT COUNT(*) FROM " + this.homes + " WHERE clan_key = ?", home.clanKey) >= maxHomes) {
               return HomeResult.LIMIT;
            }
            execute(connection, "DELETE FROM " + this.homes + " WHERE clan_key = ? AND home_name = ?", home.clanKey, home.name);
            execute(connection, "INSERT INTO " + this.homes + " (clan_key, home_name, server_id, world, x, y, z, yaw, pitch, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
               home.clanKey, home.name, home.serverId, home.world, home.x, home.y, home.z, home.yaw, home.pitch, home.createdBy);
            return HomeResult.OK;
         });
      }

      @Override
      public boolean deleteHome(String clanKey, String name) throws SQLException {
         return this.read(connection -> execute(connection, "DELETE FROM " + this.homes + " WHERE clan_key = ? AND home_name = ?", clanKey, name) > 0);
      }
   }
}

package de.greenattack.system.features;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Datentypen des Clan-Systems (Clans, Rollen, Mitglieder, Homes) und der Zwischenspeicher-Schnappschuss. */
final class ClanModel {
   static final String OWNER = "owner";
   static final String MEMBER = "member";

   /** Rechte, die einer Rolle zugewiesen werden können. Der Owner hat immer alle. */
   static final List<String> PERMISSIONS = List.of(
      "roles_view", "roles_edit", "members_view", "invite", "kick", "role_assign", "home_use", "home_set",
      "plot", "tasks", "color", "rename", "tag", "settings");

   private ClanModel() {
   }

   static String permissionLabel(String permission) {
      switch (permission) {
         case "roles_view":
            return "Rollen ansehen";
         case "roles_edit":
            return "Rollen bearbeiten";
         case "members_view":
            return "Mitglieder anzeigen";
         case "invite":
            return "Spieler einladen";
         case "kick":
            return "Mitglieder rauswerfen";
         case "role_assign":
            return "Rollen vergeben";
         case "home_use":
            return "Clan-Home nutzen";
         case "home_set":
            return "Clan-Homes setzen/löschen";
         case "plot":
            return "Clan-Plot";
         case "tasks":
            return "Clan-Aufgaben";
         case "color":
            return "Tag-Farbe ändern";
         case "rename":
            return "Clan-Name ändern";
         case "tag":
            return "Clan-Tag ändern";
         case "settings":
            return "Einstellungen (Beitritt)";
         default:
            return permission;
      }
   }

   static Set<String> parsePermissions(String csv) {
      Set<String> result = new LinkedHashSet<>();
      if (csv != null) {
         for (String part : csv.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (PERMISSIONS.contains(trimmed)) {
               result.add(trimmed);
            }
         }
      }
      return result;
   }

   static String joinPermissions(Collection<String> permissions) {
      List<String> ordered = new ArrayList<>();
      for (String permission : PERMISSIONS) {
         if (permissions.contains(permission)) {
            ordered.add(permission);
         }
      }
      return String.join(",", ordered);
   }

   enum JoinMode {
      CLOSED,
      OPEN,
      INVITE;

      static JoinMode parse(String text) {
         if (text != null) {
            String value = text.trim().toLowerCase(Locale.ROOT);
            switch (value) {
               case "closed":
               case "close":
               case "geschlossen":
                  return CLOSED;
               case "open":
               case "offen":
               case "free":
                  return OPEN;
               case "invite":
               case "invitation":
               case "einladung":
                  return INVITE;
               default:
                  break;
            }
         }
         return null;
      }

      String key() {
         return this.name().toLowerCase(Locale.ROOT);
      }
   }

   enum CreateResult {
      OK,
      ALREADY_IN_CLAN,
      NAME_TAKEN,
      TAG_TAKEN
   }

   enum AddResult {
      OK,
      ALREADY_IN_CLAN,
      FULL,
      NO_CLAN
   }

   enum RoleResult {
      OK,
      EXISTS,
      LIMIT
   }

   enum HomeResult {
      OK,
      LIMIT
   }

   /** Standardwerte für die beiden Systemrollen (aus der Config). */
   static final class Defaults {
      final int ownerWeight;
      final int memberWeight;
      final Set<String> memberPermissions;

      Defaults(int ownerWeight, int memberWeight, Set<String> memberPermissions) {
         this.ownerWeight = ownerWeight;
         this.memberWeight = memberWeight;
         this.memberPermissions = memberPermissions;
      }

      RoleData ownerRole() {
         return new RoleData(OWNER, "Owner", this.ownerWeight, new LinkedHashSet<>(PERMISSIONS), true);
      }

      RoleData memberRole() {
         return new RoleData(MEMBER, "Member", this.memberWeight, new LinkedHashSet<>(this.memberPermissions), true);
      }
   }

   static final class ClanData {
      final String key;
      final String name;
      final String tag;
      final String color;
      final String tagStyle;
      final long createdAt;
      final JoinMode joinMode;

      ClanData(String key, String name, String tag, String color, String tagStyle, long createdAt, JoinMode joinMode) {
         this.key = key;
         this.name = name;
         this.tag = tag;
         this.color = color;
         this.tagStyle = "bold".equalsIgnoreCase(tagStyle) ? "bold" : "normal";
         this.createdAt = createdAt;
         this.joinMode = joinMode == null ? JoinMode.INVITE : joinMode;
      }
   }

   static final class RoleData {
      final String key;
      final String name;
      final int weight;
      final Set<String> permissions;
      final boolean system;

      RoleData(String key, String name, int weight, Set<String> permissions, boolean system) {
         this.key = key;
         this.name = name;
         this.weight = weight;
         this.permissions = permissions;
         this.system = system;
      }

      RoleData withWeight(int newWeight) {
         return new RoleData(this.key, this.name, newWeight, this.permissions, this.system);
      }
   }

   static final class MemberData {
      final UUID uuid;
      final String name;
      final String clanKey;
      final String roleKey;
      final long joinedAt;

      MemberData(UUID uuid, String name, String clanKey, String roleKey, long joinedAt) {
         this.uuid = uuid;
         this.name = name;
         this.clanKey = clanKey;
         this.roleKey = roleKey;
         this.joinedAt = joinedAt;
      }
   }

   static final class HomeData {
      final String clanKey;
      final String name;
      final String serverId;
      final String world;
      final double x;
      final double y;
      final double z;
      final float yaw;
      final float pitch;
      final String createdBy;

      HomeData(String clanKey, String name, String serverId, String world, double x, double y, double z, float yaw, float pitch, String createdBy) {
         this.clanKey = clanKey;
         this.name = name;
         this.serverId = serverId == null ? "" : serverId;
         this.world = world;
         this.x = x;
         this.y = y;
         this.z = z;
         this.yaw = yaw;
         this.pitch = pitch;
         this.createdBy = createdBy == null ? "" : createdBy;
      }
   }

   /** Unveränderlicher Schnappschuss aller Clans. Owner steht immer über allen eigenen Rollen, Member immer darunter. */
   static final class Snapshot {
      final Map<String, ClanData> clans;
      final Map<UUID, MemberData> members;
      private final Map<String, Map<String, RoleData>> roles = new HashMap<>();
      private final Map<String, Map<String, HomeData>> homes = new HashMap<>();
      private final Map<String, List<MemberData>> byClan = new HashMap<>();
      private final Defaults defaults;

      Snapshot(Map<String, ClanData> clans, Map<UUID, MemberData> members, List<String> roleClanKeys, List<RoleData> roleRows, List<HomeData> homeRows, Defaults defaults) {
         this.clans = clans;
         this.members = members;
         this.defaults = defaults;
         for (MemberData member : members.values()) {
            this.byClan.computeIfAbsent(member.clanKey, key -> new ArrayList<>()).add(member);
         }
         for (int index = 0; index < roleRows.size(); index++) {
            this.roles.computeIfAbsent(roleClanKeys.get(index), key -> new LinkedHashMap<>()).put(roleRows.get(index).key, roleRows.get(index));
         }
         for (String clanKey : clans.keySet()) {
            Map<String, RoleData> own = this.roles.computeIfAbsent(clanKey, key -> new LinkedHashMap<>());
            own.putIfAbsent(OWNER, defaults.ownerRole());
            own.putIfAbsent(MEMBER, defaults.memberRole());
            int highest = 0;
            int lowest = Integer.MAX_VALUE;
            for (RoleData role : own.values()) {
               if (!role.system) {
                  highest = Math.max(highest, role.weight);
                  lowest = Math.min(lowest, role.weight);
               }
            }
            RoleData owner = own.get(OWNER);
            if (owner.weight <= highest) {
               own.put(OWNER, owner.withWeight(highest + 1));
            }
            RoleData member = own.get(MEMBER);
            if (lowest != Integer.MAX_VALUE && member.weight >= lowest) {
               own.put(MEMBER, member.withWeight(lowest - 1));
            }
         }
         for (HomeData home : homeRows) {
            this.homes.computeIfAbsent(home.clanKey, key -> new LinkedHashMap<>()).put(home.name.toLowerCase(Locale.ROOT), home);
         }
      }

      List<MemberData> membersOf(String clanKey) {
         List<MemberData> list = this.byClan.get(clanKey);
         return list == null ? List.of() : list;
      }

      RoleData role(String clanKey, String roleKey) {
         Map<String, RoleData> own = this.roles.get(clanKey);
         RoleData role = own == null ? null : own.get(roleKey);
         if (role != null) {
            return role;
         }
         return MEMBER.equals(roleKey) || own == null ? this.defaults.memberRole() : own.get(MEMBER);
      }

      RoleData roleByName(String clanKey, String name) {
         Map<String, RoleData> own = this.roles.get(clanKey);
         return own == null || name == null ? null : own.get(name.toLowerCase(Locale.ROOT));
      }

      List<RoleData> rolesOf(String clanKey) {
         Map<String, RoleData> own = this.roles.get(clanKey);
         List<RoleData> list = own == null ? new ArrayList<>() : new ArrayList<>(own.values());
         list.sort(Comparator.<RoleData>comparingInt(role -> -role.weight).thenComparing(role -> role.key));
         return list;
      }

      int weight(MemberData member) {
         return this.role(member.clanKey, member.roleKey).weight;
      }

      boolean can(MemberData member, String permission) {
         return OWNER.equals(member.roleKey) || this.role(member.clanKey, member.roleKey).permissions.contains(permission);
      }

      List<HomeData> homesOf(String clanKey) {
         Map<String, HomeData> own = this.homes.get(clanKey);
         List<HomeData> list = own == null ? new ArrayList<>() : new ArrayList<>(own.values());
         list.sort(Comparator.comparing(home -> home.name.toLowerCase(Locale.ROOT)));
         return list;
      }

      HomeData home(String clanKey, String name) {
         Map<String, HomeData> own = this.homes.get(clanKey);
         return own == null || name == null ? null : own.get(name.toLowerCase(Locale.ROOT));
      }

      static Snapshot empty(Defaults defaults) {
         return new Snapshot(new HashMap<>(), new HashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), defaults);
      }
   }
}

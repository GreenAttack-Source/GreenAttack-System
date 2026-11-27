package de.greenattack.system.features;

import de.greenattack.system.features.ClanModel.ClanData;
import de.greenattack.system.features.ClanModel.HomeData;
import de.greenattack.system.features.ClanModel.JoinMode;
import de.greenattack.system.features.ClanModel.MemberData;
import de.greenattack.system.features.ClanModel.RoleData;
import de.greenattack.system.features.ClanModel.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Clan-Menü (Inventar-Oberfläche). Alle Aktionen laufen über dieselben Methoden wie die Befehle. */
public final class ClanMenus {
   private static final String[][] COLOR_ITEMS = {
      {"black", "BLACK_WOOL"}, {"dark_blue", "BLUE_WOOL"}, {"dark_green", "GREEN_WOOL"}, {"dark_aqua", "CYAN_WOOL"},
      {"dark_red", "RED_TERRACOTTA"}, {"dark_purple", "PURPLE_WOOL"}, {"gold", "ORANGE_WOOL"}, {"gray", "LIGHT_GRAY_WOOL"},
      {"dark_gray", "GRAY_WOOL"}, {"blue", "LIGHT_BLUE_WOOL"}, {"green", "LIME_WOOL"}, {"aqua", "CYAN_STAINED_GLASS"},
      {"red", "RED_WOOL"}, {"pink", "PINK_WOOL"}, {"yellow", "YELLOW_WOOL"}, {"white", "WHITE_WOOL"}};

   interface MenuAction {
      void run(Player player, boolean rightClick);
   }

   static final class MenuHolder implements InventoryHolder {
      final Map<Integer, MenuAction> actions = new HashMap<>();
      private Inventory inventory;

      @Override
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private final ClanFeature feature;

   ClanMenus(ClanFeature feature) {
      this.feature = feature;
   }

   // ------------------------------------------------------------------ Bausteine

   private ItemStack item(String material, String name, List<String> lore) {
      Material type = Material.matchMaterial(material);
      ItemStack stack = new ItemStack(type == null ? Material.PAPER : type);
      ItemMeta meta = stack.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(this.feature.colorize(name));
         List<String> colored = new ArrayList<>();
         for (String line : lore) {
            colored.add(this.feature.colorize(line));
         }
         meta.setLore(colored);
         stack.setItemMeta(meta);
      }
      return stack;
   }

   private MenuHolder open(Player player, int size, String title) {
      MenuHolder holder = new MenuHolder();
      Inventory inventory = Bukkit.createInventory(holder, size, this.feature.colorize(title));
      holder.inventory = inventory;
      ItemStack filler = this.item("GRAY_STAINED_GLASS_PANE", " ", List.of());
      for (int slot = 0; slot < size; slot++) {
         inventory.setItem(slot, filler);
      }
      return holder;
   }

   private void put(MenuHolder holder, int slot, ItemStack stack, MenuAction action) {
      holder.inventory.setItem(slot, stack);
      if (action != null) {
         holder.actions.put(slot, action);
      } else {
         holder.actions.remove(slot);
      }
   }

   private void show(Player player, MenuHolder holder) {
      player.openInventory(holder.inventory);
   }

   private void back(MenuHolder holder, int slot, Runnable target) {
      this.put(holder, slot, this.item("ARROW", "&7Zurück", List.of()), (player, right) -> target.run());
   }

   private static List<String> lines(String... text) {
      return new ArrayList<>(List.of(text));
   }

   private MemberData me(Player player) {
      return this.feature.snapshot().members.get(player.getUniqueId());
   }

   private void denied(Player player) {
      this.feature.tell(player, "messages.no-rights", "&cDazu hast du im Clan keine Rechte.");
   }

   // ------------------------------------------------------------------ Hauptmenü

   void openMain(Player player) {
      Snapshot current = this.feature.snapshot();
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.openNoClan(player);
         return;
      }
      ClanData clan = current.clans.get(me.clanKey);
      if (clan == null) {
         this.openNoClan(player);
         return;
      }

      MenuHolder holder = this.open(player, 36, "&8Clan: &e" + clan.name);
      int count = current.membersOf(me.clanKey).size();
      this.put(holder, 4, this.item("BOOK", "&e" + clan.name, lines(
         "&7Tag: " + this.feature.formatTag(clan),
         "&7Mitglieder: &f" + count,
         "&7Beitritt: &f" + this.feature.joinModeLabel(clan.joinMode),
         "&7Deine Rolle: &f" + current.role(me.clanKey, me.roleKey).name)), null);

      this.put(holder, 10, this.item("PLAYER_HEAD", "&aMitglieder", lines("&7Mitglieder ansehen und verwalten.", this.hint(current.can(me, "members_view")))),
         (p, right) -> this.guarded(p, "members_view", () -> this.openMembers(p)));
      this.put(holder, 11, this.item("NAME_TAG", "&aRollen", lines("&7Rollen, Gewichte und Rechte.", this.hint(current.can(me, "roles_view")))),
         (p, right) -> this.guarded(p, "roles_view", () -> this.openRoles(p)));
      this.put(holder, 12, this.item("RED_BED", "&aClan-Homes", lines("&7Homes: &f" + current.homesOf(me.clanKey).size() + "&7/&f" + this.feature.maxHomes(), this.hint(current.can(me, "home_use")))),
         (p, right) -> this.guarded(p, "home_use", () -> this.openHomes(p)));
      this.put(holder, 13, this.item("PINK_DYE", "&aTag-Farbe", lines("&7Farbe deines Clan-Tags ändern.", "&7Kostet Kristalle.", this.hint(current.can(me, "color")))),
         (p, right) -> this.guarded(p, "color", () -> this.openColors(p)));
      this.put(holder, 14, this.item("COMPARATOR", "&aEinstellungen", lines("&7Beitritt: &f" + this.feature.joinModeLabel(clan.joinMode), this.hint(current.can(me, "settings")))),
         (p, right) -> this.openSettings(p));
      this.put(holder, 15, this.item("ANVIL", "&aName und Tag ändern", lines("&7Linksklick: &fClan-Name", "&7Rechtsklick: &fClan-Tag &7(kostenlos)", this.hint(current.can(me, "rename") || current.can(me, "tag")))),
         (p, right) -> {
            if (right) {
               this.guarded(p, "tag", () -> this.feature.startPrompt(p, "tag", "", "messages.prompt-tag", "&aSchreibe den neuen Clan-Tag in den Chat."));
            } else {
               this.guarded(p, "rename", () -> this.feature.startPrompt(p, "rename", "", "messages.prompt-rename", "&aSchreibe den neuen Clan-Namen in den Chat."));
            }
         });
      this.put(holder, 16, this.item("WRITABLE_BOOK", "&aClan-Chat", lines("&7Klick: Clan-Chat ein- oder ausschalten.", "&7Oder nutze &f/cc <Nachricht>&7.")),
         (p, right) -> {
            p.closeInventory();
            p.performCommand("clan chat");
         });
      this.put(holder, 19, this.item("GRASS_BLOCK", "&7Clan-Plot", lines("&8Kommt bald.", "&7Das Recht kann schon vergeben werden.")), null);
      this.put(holder, 20, this.item("CHEST", "&7Clan-Aufgaben", lines("&8Kommt bald.", "&7Das Recht kann schon vergeben werden.")), null);
      if (ClanModel.OWNER.equals(me.roleKey)) {
         this.put(holder, 27, this.item("TNT", "&cClan löschen", lines("&7Klick: &f/clan delete", "&7Die Löschung muss bestätigt werden.")), (p, right) -> {
            p.closeInventory();
            p.performCommand("clan delete");
         });
      } else {
         this.put(holder, 27, this.item("OAK_DOOR", "&cClan verlassen", lines("&7Klick: &f/clan leave")), (p, right) -> {
            p.closeInventory();
            p.performCommand("clan leave");
         });
      }
      this.put(holder, 31, this.item("BARRIER", "&cSchließen", lines()), (p, right) -> p.closeInventory());
      this.show(player, holder);
   }

   private String hint(boolean allowed) {
      return allowed ? "&aKlick zum Öffnen" : "&cKein Recht im Clan";
   }

   private void guarded(Player player, String permission, Runnable action) {
      MemberData me = this.me(player);
      if (me == null || !this.feature.snapshot().can(me, permission)) {
         this.denied(player);
         return;
      }
      action.run();
   }

   private void openNoClan(Player player) {
      Snapshot current = this.feature.snapshot();
      MenuHolder holder = this.open(player, 27, "&8Clan");
      this.put(holder, 11, this.item("NETHER_STAR", "&aClan gründen", lines("&7Befehl: &f/clan create <Name> <Tag>",
         "&7Kosten: &e" + this.feature.creationCostLabel())), null);
      int open = 0;
      for (ClanData clan : current.clans.values()) {
         if (clan.joinMode == JoinMode.OPEN) {
            open++;
         }
      }
      this.put(holder, 13, this.item("BOOK", "&aOffene Clans", lines("&7Clans, denen du direkt beitreten kannst.", "&7Offen: &f" + open)), (p, right) -> this.openJoinable(p));
      if (this.feature.hasInvite(player.getUniqueId())) {
         this.put(holder, 15, this.item("LIME_DYE", "&aEinladung annehmen", lines("&7Klick: &f/clan accept")), (p, right) -> {
            p.closeInventory();
            p.performCommand("clan accept");
         });
      }
      this.put(holder, 22, this.item("BARRIER", "&cSchließen", lines()), (p, right) -> p.closeInventory());
      this.show(player, holder);
   }

   void openJoinable(Player player) {
      Snapshot current = this.feature.snapshot();
      MenuHolder holder = this.open(player, 54, "&8Offene Clans");
      List<ClanData> open = new ArrayList<>();
      for (ClanData clan : current.clans.values()) {
         if (clan.joinMode == JoinMode.OPEN) {
            open.add(clan);
         }
      }
      open.sort(Comparator.<ClanData>comparingInt(clan -> -current.membersOf(clan.key).size()).thenComparing(clan -> clan.key));
      for (int index = 0; index < open.size() && index < 45; index++) {
         ClanData clan = open.get(index);
         this.put(holder, index, this.item("PAPER", "&e" + clan.name, lines("&7Tag: " + this.feature.formatTag(clan), "&7Mitglieder: &f" + current.membersOf(clan.key).size(), "&aKlick zum Beitreten")),
            (p, right) -> {
               p.closeInventory();
               this.feature.joinClan(p, clan.key, clan.name, true);
            });
      }
      this.back(holder, 49, () -> this.openMain(player));
      this.show(player, holder);
   }

   // ------------------------------------------------------------------ Mitglieder

   void openMembers(Player player) {
      Snapshot current = this.feature.snapshot();
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null || !current.can(me, "members_view")) {
         this.denied(player);
         return;
      }
      MenuHolder holder = this.open(player, 54, "&8Mitglieder");
      List<MemberData> members = new ArrayList<>(current.membersOf(me.clanKey));
      members.sort(Comparator.<MemberData>comparingInt(member -> -current.weight(member)).thenComparing(member -> member.name.toLowerCase(Locale.ROOT)));
      boolean manage = current.can(me, "kick") || current.can(me, "role_assign");
      for (int index = 0; index < members.size() && index < 45; index++) {
         MemberData member = members.get(index);
         boolean online = Bukkit.getPlayer(member.uuid) != null;
         boolean lower = current.weight(member) < current.weight(me);
         List<String> lore = lines("&7Rolle: &f" + current.role(member.clanKey, member.roleKey).name, online ? "&aOnline" : "&7Offline");
         if (manage && lower) {
            lore.add("&aKlick zum Verwalten");
         }
         this.put(holder, index, this.item("PLAYER_HEAD", (online ? "&a" : "&7") + member.name, lore), (manage && lower) ? (p, right) -> this.openMemberActions(p, member.name) : null);
      }
      this.back(holder, 49, () -> this.openMain(player));
      this.show(player, holder);
   }

   void openMemberActions(Player player, String targetName) {
      Snapshot current = this.feature.snapshot();
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.openNoClan(player);
         return;
      }
      MemberData target = null;
      for (MemberData member : current.membersOf(me.clanKey)) {
         if (member.name.equalsIgnoreCase(targetName)) {
            target = member;
         }
      }
      if (target == null) {
         this.openMembers(player);
         return;
      }

      final MemberData chosen = target;
      MenuHolder holder = this.open(player, 36, "&8Mitglied: &e" + chosen.name);
      this.put(holder, 4, this.item("PLAYER_HEAD", "&e" + chosen.name, lines("&7Rolle: &f" + current.role(chosen.clanKey, chosen.roleKey).name)), null);
      int weight = current.weight(me);
      if (current.can(me, "role_assign")) {
         int slot = 9;
         for (RoleData role : current.rolesOf(me.clanKey)) {
            if (ClanModel.OWNER.equals(role.key) || role.weight >= weight || slot > 26) {
               continue;
            }
            boolean active = role.key.equals(chosen.roleKey);
            this.put(holder, slot++, this.item("NAME_TAG", (active ? "&a" : "&f") + role.name, lines("&7Gewicht: &f" + role.weight, active ? "&aAktuelle Rolle" : "&7Klick: Rolle vergeben")),
               (p, right) -> this.feature.setRole(p, chosen.name, role.name, () -> this.openMemberActions(p, chosen.name)));
         }
      }
      if (current.can(me, "kick")) {
         this.put(holder, 31, this.item("TNT", "&cAus dem Clan werfen", lines("&7Klick: &f" + chosen.name + " &7rauswerfen")),
            (p, right) -> this.feature.kick(p, chosen.name, () -> this.openMembers(p)));
      }
      this.back(holder, 35, () -> this.openMembers(player));
      this.show(player, holder);
   }

   // ------------------------------------------------------------------ Rollen

   void openRoles(Player player) {
      Snapshot current = this.feature.snapshot();
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null || !current.can(me, "roles_view")) {
         this.denied(player);
         return;
      }
      MenuHolder holder = this.open(player, 54, "&8Rollen");
      int weight = current.weight(me);
      boolean edit = current.can(me, "roles_edit");
      List<RoleData> roles = current.rolesOf(me.clanKey);
      for (int index = 0; index < roles.size() && index < 45; index++) {
         RoleData role = roles.get(index);
         boolean editable = edit && !ClanModel.OWNER.equals(role.key) && role.weight < weight;
         List<String> lore = lines("&7Gewicht: &f" + role.weight, role.system ? "&8Standardrolle (nicht löschbar)" : "&8Eigene Rolle");
         lore.add("&7Rechte:");
         if (ClanModel.OWNER.equals(role.key)) {
            lore.add("&f- alle Rechte");
         } else if (role.permissions.isEmpty()) {
            lore.add("&f- keine");
         } else {
            for (String permission : ClanModel.PERMISSIONS) {
               if (role.permissions.contains(permission)) {
                  lore.add("&f- " + ClanModel.permissionLabel(permission));
               }
            }
         }
         if (editable) {
            lore.add("&aKlick zum Bearbeiten");
         }
         this.put(holder, index, this.item("NAME_TAG", "&e" + role.name, lore), editable ? (p, right) -> this.openRoleEditor(p, role.key) : null);
      }
      if (edit) {
         this.put(holder, 45, this.item("EMERALD", "&aRolle erstellen", lines("&7Klick, dann im Chat: &fName Gewicht", "&7Beispiel: &fsupporter 50", "&7Gewicht muss unter deiner Rolle liegen.")),
            (p, right) -> this.feature.startPrompt(p, "role-create", "", "messages.prompt-role", "&aSchreibe &fName Gewicht &ain den Chat, zum Beispiel &fsupporter 50&a."));
      }
      this.back(holder, 49, () -> this.openMain(player));
      this.show(player, holder);
   }

   void openRoleEditor(Player player, String roleKey) {
      Snapshot current = this.feature.snapshot();
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null || !current.can(me, "roles_edit")) {
         this.denied(player);
         return;
      }
      final RoleData role = current.roleByName(me.clanKey, roleKey);
      if (role == null || ClanModel.OWNER.equals(role.key)) {
         this.openRoles(player);
         return;
      }

      MenuHolder holder = this.open(player, 54, "&8Rolle: &e" + role.name);
      int slot = 10;
      for (String permission : ClanModel.PERMISSIONS) {
         if (slot == 17) {
            slot = 19;
         }
         boolean on = role.permissions.contains(permission);
         final String key = permission;
         this.put(holder, slot++, this.item(on ? "LIME_DYE" : "GRAY_DYE", (on ? "&a" : "&7") + ClanModel.permissionLabel(permission),
            lines(on ? "&aAktiv" : "&cAus", "&7Klick zum Umschalten")),
            (p, right) -> this.feature.rolePerm(p, role.name, key, null, () -> this.openRoleEditor(p, role.key)));
      }
      this.put(holder, 40, this.item("PAPER", "&eGewicht: &f" + role.weight, lines(role.system ? "&8Standardrolle: Gewicht fest" : "&7Mit den Pfeilen ändern.")), null);
      if (!role.system) {
         int[][] steps = {{37, -10}, {38, -1}, {42, 1}, {43, 10}};
         for (int[] step : steps) {
            final int delta = step[1];
            this.put(holder, step[0], this.item(delta < 0 ? "RED_DYE" : "LIME_DYE", (delta < 0 ? "&c" : "&a") + (delta > 0 ? "+" : "") + delta, lines()),
               (p, right) -> this.feature.roleWeight(p, role.name, role.weight + delta, () -> this.openRoleEditor(p, role.key)));
         }
         this.put(holder, 53, this.item("TNT", "&cRolle löschen", lines("&7Rechtsklick zum Löschen.", "&7Mitglieder werden wieder Member.")), (p, right) -> {
            if (right) {
               this.feature.roleDelete(p, role.name, () -> this.openRoles(p));
            }
         });
      }
      this.back(holder, 45, () -> this.openRoles(player));
      this.show(player, holder);
   }

   // ------------------------------------------------------------------ Homes

   void openHomes(Player player) {
      Snapshot current = this.feature.snapshot();
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null || !current.can(me, "home_use")) {
         this.denied(player);
         return;
      }
      MenuHolder holder = this.open(player, 36, "&8Clan-Homes");
      boolean manage = current.can(me, "home_set");
      List<HomeData> homes = current.homesOf(me.clanKey);
      for (int index = 0; index < homes.size() && index < 27; index++) {
         HomeData home = homes.get(index);
         List<String> lore = lines("&7Server: &f" + (home.serverId.isEmpty() ? "lokal" : home.serverId), "&7Welt: &f" + home.world, "&aLinksklick: teleportieren");
         if (manage) {
            lore.add("&cRechtsklick: löschen");
         }
         this.put(holder, index, this.item("RED_BED", "&e" + home.name, lore), (p, right) -> {
            if (right) {
               if (manage) {
                  this.feature.deleteHome(p, home.name, () -> this.openHomes(p));
               }
            } else {
               p.closeInventory();
               this.feature.teleportHome(p, home.name);
            }
         });
      }
      if (manage) {
         this.put(holder, 31, this.item("EMERALD", "&aHome hier setzen", lines("&7Klick, dann den Namen in den Chat schreiben.", "&7Homes: &f" + homes.size() + "&7/&f" + this.feature.maxHomes())),
            (p, right) -> this.feature.startPrompt(p, "home-set", "", "messages.prompt-home", "&aSchreibe den Namen für das Clan-Home in den Chat."));
      }
      this.back(holder, 35, () -> this.openMain(player));
      this.show(player, holder);
   }

   // ------------------------------------------------------------------ Farbe

   void openColors(Player player) {
      Snapshot current = this.feature.snapshot();
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null || !current.can(me, "color")) {
         this.denied(player);
         return;
      }
      ClanData clan = current.clans.get(me.clanKey);
      MenuHolder holder = this.open(player, 36, "&8Tag-Farbe");
      int slot = 10;
      for (String[] entry : COLOR_ITEMS) {
         if (slot == 17) {
            slot = 19;
         }
         final String stored = ClanFeature.parseColor(entry[0]);
         boolean active = clan != null && clan.color.equalsIgnoreCase(stored);
         String shown = ClanFeature.legacyColor(stored).replace('§', '&') + entry[0];
         this.put(holder, slot++, this.item(entry[1], shown, lines(active ? "&aAktuelle Farbe" : "&7Kosten: &b" + this.feature.colorCost(stored) + " Kristalle", "&7Vorschau: " + this.feature.formatTag(new ClanData(clan.key, clan.name, clan.tag, stored, clan.tagStyle, clan.createdAt, clan.joinMode)))),
            (p, right) -> this.feature.buyColor(p, stored, () -> this.openColors(p)));
      }
      this.put(holder, 31, this.item("NAME_TAG", "&bEigene Hex-Farbe", lines("&7Klick, dann im Chat: &f#RRGGBB", "&7Kosten: &b" + this.feature.colorCost("#ffffff") + " Kristalle")),
         (p, right) -> this.feature.startPrompt(p, "hex", "", "messages.prompt-hex", "&aSchreibe die Farbe als &f#RRGGBB &ain den Chat, zum Beispiel &f#ff8800&a."));
      this.back(holder, 35, () -> this.openMain(player));
      this.show(player, holder);
   }

   // ------------------------------------------------------------------ Einstellungen

   void openSettings(Player player) {
      Snapshot current = this.feature.snapshot();
      MemberData me = current.members.get(player.getUniqueId());
      if (me == null) {
         this.openNoClan(player);
         return;
      }
      ClanData clan = current.clans.get(me.clanKey);
      boolean edit = current.can(me, "settings");
      MenuHolder holder = this.open(player, 27, "&8Einstellungen");
      Object[][] modes = {
         {JoinMode.CLOSED, "BARRIER", "&cGeschlossen", "&7Niemand kann beitreten, auch nicht per Einladung."},
         {JoinMode.OPEN, "OAK_DOOR", "&aOffen", "&7Jeder kann mit &f/clan join <Clan> &7beitreten."},
         {JoinMode.INVITE, "PAPER", "&eNur mit Einladung", "&7Beitritt nur mit &f/clan invite&7."}};
      int[] slots = {11, 13, 15};
      for (int index = 0; index < modes.length; index++) {
         final JoinMode mode = (JoinMode) modes[index][0];
         boolean active = clan != null && clan.joinMode == mode;
         this.put(holder, slots[index], this.item((String) modes[index][1], (String) modes[index][2],
            lines((String) modes[index][3], active ? "&a✔ Aktuell aktiv" : (edit ? "&7Klick zum Aktivieren" : "&cKein Recht im Clan"))),
            (p, right) -> {
               if (edit) {
                  this.feature.setJoinMode(p, mode, () -> this.openSettings(p));
               } else {
                  this.denied(p);
               }
            });
      }
      this.back(holder, 22, () -> this.openMain(player));
      this.show(player, holder);
   }

   // ------------------------------------------------------------------ Ereignisse

   public static final class MenuListener implements Listener {
      @EventHandler
      public void onMenuClick(InventoryClickEvent event) {
         if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
         }
         event.setCancelled(true);
         if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
         }
         if (!(event.getWhoClicked() instanceof Player player)) {
            return;
         }
         MenuAction action = holder.actions.get(event.getRawSlot());
         if (action != null) {
            action.run(player, event.isRightClick());
         }
      }

      @EventHandler
      public void onMenuDrag(InventoryDragEvent event) {
         if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
         }
      }
   }
}

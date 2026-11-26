package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.permissions.PermissionAttachment;

public final class InventoryFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String VANISHED_METADATA = "vanished";
   private final Set<UUID> vanishedPlayers = ConcurrentHashMap.newKeySet();
   private final Map<UUID, InventoryFeature.Inspection> inspections = new HashMap<>();
   private final Map<UUID, Set<Integer>> pendingInspectionChanges = new HashMap<>();
   private final Map<UUID, PermissionAttachment> tabVanishPermissionOverrides = new HashMap<>();

   public InventoryFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "inventory");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
         return true;
      }

      return switch (command.getName().toLowerCase(Locale.ROOT)) {
         case "vanish" -> this.toggleVanish(sender, args);
         case "invsee" -> this.inspectInventory(sender, args, false);
         case "ecsee" -> this.inspectInventory(sender, args, true);
         default -> false;
      };
   }

   private boolean toggleVanish(CommandSender sender, String[] args) {
      if (!this.hasVanishUsePermission(sender)) {
         return true;
      }

      if (sender instanceof Player player) {
         if (args.length != 0) {
            this.sendPath(player, "messages.usage-vanish", "&7Verwendung: &f/vanish");
            return true;
         }

         boolean nowVanished;
         if (this.vanishedPlayers.remove(player.getUniqueId())) {
            nowVanished = false;
            player.removeMetadata("vanished", this.plugin);

            for (Player viewer : Bukkit.getOnlinePlayers()) {
               if (viewer != player) {
                  viewer.showPlayer(this.plugin, player);
               }
            }

            this.refreshTabVanishPermissions();
            this.sendPath(player, "messages.vanish-off", "&aDu bist wieder sichtbar.");
         } else {
            nowVanished = true;
            this.vanishedPlayers.add(player.getUniqueId());
            player.setMetadata("vanished", new FixedMetadataValue(this.plugin, true));
            this.hideFromUnauthorizedViewers(player);
            this.refreshTabVanishPermissions();
            Bukkit.getScheduler().runTask(this.plugin, () -> {
               if (player.isOnline() && this.vanishedPlayers.contains(player.getUniqueId())) {
                  this.hideFromUnauthorizedViewers(player);
               }
            });
            this.sendPath(player, "messages.vanish-on", "&aDu bist jetzt unsichtbar.");
         }

         this.plugin.getLogger().fine(player.getName() + (nowVanished ? " ist unsichtbar." : " ist wieder sichtbar."));
         return true;
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onVanishCommand(PlayerCommandPreprocessEvent event) {
      if (this.isEnabled()) {
         String message = event.getMessage();
         if (message != null && message.length() >= 2 && message.charAt(0) == '/') {
            String[] parts = message.substring(1).trim().split("\\s+");
            String label = parts[0].toLowerCase(Locale.ROOT);
            if (label.equals("vanish") || label.equals("v")) {
               event.setCancelled(true);
               this.toggleVanish(event.getPlayer(), Arrays.copyOfRange(parts, 1, parts.length));
            }
         }
      }
   }

   private boolean inspectInventory(CommandSender sender, String[] args, boolean enderChest) {
      String type = enderChest ? "ecsee" : "invsee";
      String permissionPath = enderChest ? "permissions.ecsee" : "permissions.invsee";
      String permission = enderChest ? "system.ecsee" : "system.invsee";
      if (!this.hasPermission(sender, permissionPath, permission)) {
         return true;
      }

      if (sender instanceof Player viewer) {
         if (args.length != 1) {
            this.sendPath(
               viewer,
               enderChest ? "messages.usage-ecsee" : "messages.usage-invsee",
               enderChest ? "&7Verwendung: &f/ecsee <Spieler>" : "&7Verwendung: &f/invsee <Spieler>"
            );
            return true;
         } else {
            Player target = Bukkit.getOnlinePlayers().stream().filter(online -> online.getName().equalsIgnoreCase(args[0])).findFirst().orElse(null);
            if (target != null && (!this.vanishedPlayers.contains(target.getUniqueId()) || this.canSeeVanished(viewer))) {
               Inventory inventory = this.createInspectionMenu(target, enderChest);
               viewer.openInventory(inventory);
               this.inspections.put(viewer.getUniqueId(), new InventoryFeature.Inspection(target.getUniqueId(), enderChest, inventory));
               String message = this.config
                  .getString(
                     enderChest ? "messages.ecsee-opened" : "messages.invsee-opened",
                     enderChest ? "&aDu siehst jetzt die Endertruhe von &e%player%&a." : "&aDu siehst jetzt das Inventar von &e%player%&a."
                  );
               this.send(viewer, message.replace("%player%", target.getName()));
               return true;
            } else {
               this.sendPath(viewer, "messages.player-not-found", "&cDieser Spieler ist auf diesem Server nicht online.");
               return true;
            }
         }
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
   }

   private Inventory createInspectionMenu(Player target, boolean enderChest) {
      String titlePath = enderChest ? "gui.ecsee-title" : "gui.invsee-title";
      String titleDefault = enderChest ? "&8Endertruhe: &e%player%" : "&8Player: &e%player%";
      int size = enderChest ? 45 : 54;
      String title = color(this.config.getString(titlePath, titleDefault).replace("%player%", target.getName()));
      Inventory menu = Bukkit.createInventory(null, size, title);
      this.refreshInspectionMenu(menu, target, enderChest);
      return menu;
   }

   private void refreshInspectionMenu(Inventory menu, Player target, boolean enderChest) {
      ItemStack filler = this.namedItem(Material.BLACK_STAINED_GLASS_PANE, " ");
      if (enderChest) {
         ItemStack[] contents = target.getEnderChest().getContents();

         for (int slot = 0; slot < 27; slot++) {
            menu.setItem(slot, this.cloneItem(contents[slot]));
         }

         for (int slot = 27; slot < menu.getSize() - 1; slot++) {
            menu.setItem(slot, filler.clone());
         }

         ItemStack clearButton = this.namedItem(Material.BARRIER, this.config.getString("gui.clear-ecsee-name", "&c&lENDERTRUHE LEEREN"));
         this.setClearLore(clearButton, target.getName());
         menu.setItem(menu.getSize() - 1, clearButton);
      } else {
         PlayerInventory playerInventory = target.getInventory();
         ItemStack[] storage = playerInventory.getStorageContents();

         for (int menuSlot = 0; menuSlot < 36; menuSlot++) {
            int row = menuSlot / 9;
            int column = menuSlot % 9;

            int storageSlot = switch (row) {
               case 0 -> 9 + column;
               case 1 -> 18 + column;
               case 2 -> 27 + column;
               default -> column;
            };
            menu.setItem(menuSlot, this.cloneItem(storage[storageSlot]));
         }

         for (int slot = 36; slot <= 44; slot++) {
            menu.setItem(slot, filler.clone());
         }

         ItemStack[] armor = playerInventory.getArmorContents();
         menu.setItem(45, this.cloneItem(armor[3]));
         menu.setItem(46, this.cloneItem(armor[2]));
         menu.setItem(47, this.cloneItem(armor[1]));
         menu.setItem(48, this.cloneItem(armor[0]));
         menu.setItem(49, this.cloneItem(playerInventory.getItemInMainHand()));
         menu.setItem(50, this.cloneItem(playerInventory.getItemInOffHand()));
         menu.setItem(51, filler.clone());
         menu.setItem(52, filler.clone());
         ItemStack clearButton = this.namedItem(Material.BARRIER, this.config.getString("gui.clear-inventory-name", "&c&lINVENTAR LÖSCHEN"));
         this.setClearLore(clearButton, target.getName());
         menu.setItem(53, clearButton);
      }
   }

   private void setClearLore(ItemStack item, String targetName) {
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setLore(List.of(color("&7Löscht den Inhalt von &e" + targetName)));
         item.setItemMeta(meta);
      }
   }

   private ItemStack namedItem(Material material, String name) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(color(name));
         item.setItemMeta(meta);
      }

      return item;
   }

   private ItemStack cloneItem(ItemStack item) {
      return item == null ? null : item.clone();
   }

   private boolean canModifyInventory(Player viewer, InventoryFeature.Inspection inspection) {
      String permissionPath = inspection.enderChest() ? "permissions.ecsee-modify" : "permissions.invsee-modify";
      String permission = inspection.enderChest() ? "system.ecsee.modify" : "system.invsee.modify";
      return this.hasPermission(viewer, permissionPath, permission);
   }

   private boolean isEditableInspectionSlot(InventoryFeature.Inspection inspection, int slot) {
      return inspection.enderChest() ? slot >= 0 && slot < 27 : slot >= 0 && slot < 36 || slot >= 45 && slot <= 48 || slot == 50;
   }

   private void scheduleInvseeSync(Player viewer, InventoryFeature.Inspection inspection) {
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         if (this.inspections.get(viewer.getUniqueId()) == inspection) {
            Set<Integer> changedSlots = this.pendingInspectionChanges.remove(viewer.getUniqueId());
            if (changedSlots != null && !changedSlots.isEmpty()) {
               Player target = Bukkit.getPlayer(inspection.target());
               if (target != null && target.isOnline()) {
                  this.applyInspectionMenu(inspection, target, changedSlots);
                  target.updateInventory();
               }
            }
         }
      });
   }

   private void applyInspectionMenu(InventoryFeature.Inspection inspection, Player target, Set<Integer> changedSlots) {
      Inventory menu = inspection.inventory();
      if (inspection.enderChest()) {
         ItemStack[] contents = target.getEnderChest().getContents();

         for (int slot : changedSlots) {
            if (slot >= 0 && slot < 27) {
               contents[slot] = this.cloneItem(menu.getItem(slot));
            }
         }

         target.getEnderChest().setContents(contents);
         this.refreshInspectionMenu(menu, target, true);
      } else {
         PlayerInventory playerInventory = target.getInventory();
         ItemStack[] storage = playerInventory.getStorageContents();
         ItemStack[] armor = playerInventory.getArmorContents();
         boolean storageChanged = false;
         boolean armorChanged = false;

         for (int menuSlot : changedSlots) {
            if (menuSlot >= 0 && menuSlot < menu.getSize()) {
               if (menuSlot >= 45 && menuSlot <= 48) {
                  armor[48 - menuSlot] = this.cloneItem(menu.getItem(menuSlot));
                  armorChanged = true;
               } else if (menuSlot == 50) {
                  playerInventory.setItemInOffHand(this.cloneItem(menu.getItem(menuSlot)));
               } else if (menuSlot < 36) {
                  int row = menuSlot / 9;
                  int column = menuSlot % 9;

                  int storageSlot = switch (row) {
                     case 0 -> 9 + column;
                     case 1 -> 18 + column;
                     case 2 -> 27 + column;
                     default -> column;
                  };
                  storage[storageSlot] = this.cloneItem(menu.getItem(menuSlot));
                  storageChanged = true;
               }
            }
         }

         if (storageChanged) {
            playerInventory.setStorageContents(storage);
         }

         if (armorChanged) {
            playerInventory.setArmorContents(armor);
         }

         this.refreshInspectionMenu(menu, target, false);
      }
   }

   private void clearInspectedInventory(Player viewer, InventoryFeature.Inspection inspection) {
      if (!this.canModifyInventory(viewer, inspection)) {
         this.sendPath(viewer, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
      } else {
         Player target = Bukkit.getPlayer(inspection.target());
         if (target != null && target.isOnline()) {
            if (inspection.enderChest()) {
               target.getEnderChest().clear();
            } else {
               PlayerInventory targetInventory = target.getInventory();
               targetInventory.clear();
               targetInventory.setArmorContents(new ItemStack[4]);
               targetInventory.setItemInOffHand(new ItemStack(Material.AIR));
            }

            target.updateInventory();
            this.pendingInspectionChanges.remove(viewer.getUniqueId());
            this.refreshInspectionMenu(inspection.inventory(), target, inspection.enderChest());
            String messagePath = inspection.enderChest() ? "messages.enderchest-cleared" : "messages.inventory-cleared";
            String messageDefault = inspection.enderChest()
               ? "&aDie Endertruhe von &e%player% &awurde geleert."
               : "&aDas Inventar von &e%player% &awurde gelöscht.";
            this.send(viewer, this.config.getString(messagePath, messageDefault).replace("%player%", target.getName()));
         } else {
            this.sendPath(viewer, "messages.player-not-found", "&cDieser Spieler ist auf diesem Server nicht online.");
         }
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (this.isEnabled() && args.length == 1) {
         String commandName = command.getName().toLowerCase(Locale.ROOT);
         if (!commandName.equals("invsee") && !commandName.equals("ecsee")) {
            return List.of();
         }

         String permissionPath = commandName.equals("ecsee") ? "permissions.ecsee" : "permissions.invsee";
         String fallback = commandName.equals("ecsee") ? "system.ecsee" : "system.invsee";
         if (!this.hasPermission(sender, permissionPath, fallback)) {
            return List.of();
         }

         String partial = args[0].toLowerCase(Locale.ROOT);
         boolean seeVanished = sender instanceof Player viewer && this.canSeeVanished(viewer);
         List<String> online = Bukkit.getOnlinePlayers()
            .stream()
            .filter(player -> !this.vanishedPlayers.contains(player.getUniqueId()) || seeVanished)
            .<String>map(Player::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
            .toList();
         // Offline-Spieler werden nur zur Auswahl vorgeschlagen; /invsee und /ecsee öffnen weiterhin
         // nur Inventare online. Für einen echten Spieler steht sonst "Dieser Spieler ist nicht online."
         List<String> result = new ArrayList<>(online);
         for (String offline : OfflinePlayerNames.suggest(partial)) {
            if (!result.contains(offline)) {
               result.add(offline);
            }
         }
         result.sort(String.CASE_INSENSITIVE_ORDER);
         return result;
      } else {
         return List.of();
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player joining = event.getPlayer();
      Bukkit.getScheduler().runTask(this.plugin, () -> {
         if (joining.isOnline()) {
            for (UUID uuid : this.vanishedPlayers) {
               Player vanished = Bukkit.getPlayer(uuid);
               if (vanished != null && vanished != joining) {
                  if (this.canSeeVanished(joining)) {
                     joining.showPlayer(this.plugin, vanished);
                  } else {
                     joining.hidePlayer(this.plugin, vanished);
                  }
               }
            }

            this.refreshTabVanishPermissions();
         }
      });
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent event) {
      UUID leaving = event.getPlayer().getUniqueId();
      this.vanishedPlayers.remove(leaving);
      event.getPlayer().removeMetadata("vanished", this.plugin);
      PermissionAttachment attachment = this.tabVanishPermissionOverrides.remove(leaving);
      if (attachment != null) {
         attachment.remove();
      }

      this.refreshTabVanishPermissions();
      this.inspections.remove(leaving);
      this.pendingInspectionChanges.remove(leaving);

      for (Entry<UUID, InventoryFeature.Inspection> entry : List.copyOf(this.inspections.entrySet())) {
         if (entry.getValue().target().equals(leaving)) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
               viewer.closeInventory();
            }

            this.inspections.remove(entry.getKey());
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getWhoClicked() instanceof Player viewer) {
         InventoryFeature.Inspection inspection = this.inspections.get(viewer.getUniqueId());
         if (inspection != null && event.getView().getTopInventory() == inspection.inventory()) {
            int rawSlot = event.getRawSlot();
            if (rawSlot == inspection.inventory().getSize() - 1) {
               event.setCancelled(true);
               this.clearInspectedInventory(viewer, inspection);
            } else if (event.getAction().name().equals("COLLECT_TO_CURSOR")) {
               event.setCancelled(true);
            } else if (!this.canModifyInventory(viewer, inspection)) {
               if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize() || event.isShiftClick()) {
                  event.setCancelled(true);
               }
            } else {
               if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                  if (!this.isEditableInspectionSlot(inspection, rawSlot)) {
                     event.setCancelled(true);
                     return;
                  }

                  this.pendingInspectionChanges.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>()).add(rawSlot);
                  this.scheduleInvseeSync(viewer, inspection);
               } else if (event.isShiftClick()) {
                  event.setCancelled(true);
               }
            }
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getWhoClicked() instanceof Player viewer) {
         InventoryFeature.Inspection inspection = this.inspections.get(viewer.getUniqueId());
         if (inspection != null && event.getView().getTopInventory() == inspection.inventory()) {
            boolean touchesTop = event.getRawSlots().stream().anyMatch(slotx -> slotx >= 0 && slotx < event.getView().getTopInventory().getSize());
            if (touchesTop) {
               if (!this.canModifyInventory(viewer, inspection)) {
                  event.setCancelled(true);
               } else {
                  boolean touchesLockedSlot = event.getRawSlots()
                     .stream()
                     .anyMatch(slotx -> slotx >= 0 && slotx < inspection.inventory().getSize() && !this.isEditableInspectionSlot(inspection, slotx));
                  if (touchesLockedSlot) {
                     event.setCancelled(true);
                  } else {
                     Set<Integer> changedSlots = this.pendingInspectionChanges.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());

                     for (int slot : event.getRawSlots()) {
                        if (slot >= 0 && slot < inspection.inventory().getSize()) {
                           changedSlots.add(slot);
                        }
                     }

                     this.scheduleInvseeSync(viewer, inspection);
                  }
               }
            }
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onInventoryClose(InventoryCloseEvent event) {
      if (event.getPlayer() instanceof Player viewer) {
         InventoryFeature.Inspection inspection = this.inspections.get(viewer.getUniqueId());
         if (inspection != null && event.getView().getTopInventory() == inspection.inventory()) {
            Set<Integer> changedSlots = this.pendingInspectionChanges.remove(viewer.getUniqueId());
            if (changedSlots != null && !changedSlots.isEmpty() && this.canModifyInventory(viewer, inspection)) {
               Player target = Bukkit.getPlayer(inspection.target());
               if (target != null && target.isOnline()) {
                  this.applyInspectionMenu(inspection, target, changedSlots);
                  target.updateInventory();
               }
            }

            this.inspections.remove(viewer.getUniqueId());
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onVanishedPlayerDamaged(EntityDamageByEntityEvent event) {
      if (event.getEntity() instanceof Player target
         && this.vanishedPlayers.contains(target.getUniqueId())
         && event.getDamager() instanceof Player attacker
         && !this.canSeeVanished(attacker)) {
         event.setCancelled(true);
      } else if (event.getDamager() instanceof Player attacker && this.vanishedPlayers.contains(attacker.getUniqueId()) && event.getEntity() != attacker) {
         event.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onVanishedPlayerTargeted(EntityTargetLivingEntityEvent event) {
      if (event.getTarget() instanceof Player target && this.vanishedPlayers.contains(target.getUniqueId())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onVanishedPlayerPickup(EntityPickupItemEvent event) {
      if (event.getEntity() instanceof Player player && this.vanishedPlayers.contains(player.getUniqueId())) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onVanishedPlayerChat(AsyncChatEvent event) {
      if (this.vanishedPlayers.contains(event.getPlayer().getUniqueId())) {
         event.viewers().removeIf(audience -> audience instanceof Player viewer && !this.canSeeVanished(viewer));
      }
   }

   public void reloadFeature() {
      this.reload();
      if (!this.isEnabled()) {
         this.clearVanishes();
      }
   }

   public void shutdownFeature() {
      this.clearVanishes();
      this.inspections.clear();
      this.pendingInspectionChanges.clear();
   }

   private void clearVanishes() {
      for (UUID uuid : List.copyOf(this.vanishedPlayers)) {
         Player vanished = Bukkit.getPlayer(uuid);
         if (vanished != null) {
            vanished.removeMetadata("vanished", this.plugin);

            for (Player viewer : Bukkit.getOnlinePlayers()) {
               if (viewer != vanished) {
                  viewer.showPlayer(this.plugin, vanished);
               }
            }
         }
      }

      this.vanishedPlayers.clear();

      for (PermissionAttachment attachment : List.copyOf(this.tabVanishPermissionOverrides.values())) {
         attachment.remove();
      }

      this.tabVanishPermissionOverrides.clear();
   }

   private boolean canSeeVanished(Player player) {
      return player.hasPermission("system.vanish.see");
   }

   private boolean hasVanishUsePermission(CommandSender sender) {
      String configured = this.config.getString("permissions.vanish", "system.vanish");
      String usePermission = this.config.getString("permissions.vanish-use", "system.vanish.use");
      return sender.hasPermission(configured) || sender.hasPermission(usePermission);
   }

   private void hideFromUnauthorizedViewers(Player vanished) {
      for (Player viewer : Bukkit.getOnlinePlayers()) {
         if (viewer != vanished) {
            if (this.canSeeVanished(viewer)) {
               viewer.showPlayer(this.plugin, vanished);
            } else {
               viewer.hidePlayer(this.plugin, vanished);
            }
         }
      }
   }

   private void refreshTabVanishPermissions() {
      boolean anyVanished = !this.vanishedPlayers.isEmpty();

      for (Player viewer : Bukkit.getOnlinePlayers()) {
         if (!anyVanished) {
            this.removeTabVanishOverride(viewer.getUniqueId());
         } else {
            PermissionAttachment attachment = this.tabVanishPermissionOverrides
               .computeIfAbsent(viewer.getUniqueId(), ignored -> viewer.addAttachment(this.plugin));
            attachment.setPermission("tab.seevanished", this.canSeeVanished(viewer));
         }
      }
   }

   private void removeTabVanishOverride(UUID viewerId) {
      PermissionAttachment attachment = this.tabVanishPermissionOverrides.remove(viewerId);
      if (attachment != null) {
         attachment.remove();
      }
   }

   private boolean hasPermission(CommandSender sender, String path, String fallback) {
      return this.hasFeaturePermission(sender, this.config.getString(path, fallback));
   }

   private record Inspection(UUID target, boolean enderChest, Inventory inventory) {
   }
}

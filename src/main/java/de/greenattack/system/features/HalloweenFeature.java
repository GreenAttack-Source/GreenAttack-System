package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import de.greenattack.system.economy.EconomyService;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class HalloweenFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private final NamespacedKey halloweenHeadTag;
   private final File dataFile;
   private YamlConfiguration data;

   public HalloweenFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "halloween");
      this.halloweenHeadTag = new NamespacedKey(plugin, "halloween_head");
      this.dataFile = this.featureFile("data.yml");
      this.ensureRewardDefault();
      this.loadData();
      Bukkit.getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
         if (!this.hasPermission(sender, "reset")) {
            this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Berechtigung.");
            return true;
         } else {
            this.resetEvent(sender);
            return true;
         }
      } else {
         if (!this.isEnabled()) {
            this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
            return true;
         }

         if (args.length == 0) {
            if (sender instanceof Player player) {
               if (!this.hasPermission(sender, "user")) {
                  this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Berechtigung.");
                  return true;
               } else {
                  this.showProgress(player);
                  return true;
               }
            } else {
               this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
               return true;
            }
         } else if (args.length < 1 || !args[0].equalsIgnoreCase("set")) {
            this.sendPath(sender, "messages.usage", "&cNutze: &e/halloween [set [skull]|reset]");
            return true;
         } else if (!this.hasPermission(sender, "set")) {
            this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Berechtigung.");
            return true;
         } else if (!(sender instanceof Player player)) {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         } else {
            if (args.length == 1) {
               this.giveHalloweenHead(player);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("skull")) {
               this.saveHeldSkull(player);
            } else {
               this.sendPath(sender, "messages.usage", "&cNutze: &e/halloween [set [skull]|reset]");
            }

            return true;
         }
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.isEnabled()) {
         return List.of();
      }

      if (args.length == 2 && args[0].equalsIgnoreCase("set") && this.hasPermission(sender, "set")) {
         return "skull".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("skull") : List.of();
      }

      if (args.length != 1) {
         return List.of();
      }

      List<String> options = new ArrayList<>();
      if (this.hasPermission(sender, "set")) {
         options.add("set");
      }

      if (this.hasPermission(sender, "reset")) {
         options.add("reset");
      }

      String partial = args[0].toLowerCase(Locale.ROOT);
      return options.stream().filter(value -> value.startsWith(partial)).toList();
   }

   private boolean hasPermission(CommandSender sender, String action) {
      String configuredAdmin = this.config.getString("permissions.admin", "system.halloween.admin");
      if (this.hasFeaturePermission(sender, configuredAdmin)) {
         return true;
      }

      String path = "permissions." + action;

      String fallback = switch (action) {
         case "user" -> "system.halloween.user";
         case "set" -> "system.halloween.set";
         case "reset" -> "system.halloween.reset";
         default -> "system.halloween.user";
      };
      return this.hasFeaturePermission(sender, this.config.getString(path, fallback));
   }

   private void showProgress(Player player) {
      Set<String> registered = this.registeredHeadIds();
      Set<String> collected = this.collectedHeadIds(player.getUniqueId());
      int total = registered.size();
      int found = collected.size();
      String progressText = this.config
         .getString("messages.progress", "&7Du hast &e%found%&7 von &e%total%&7 Halloween-Köpfen gefunden.")
         .replace("%found%", Integer.toString(found))
         .replace("%total%", Integer.toString(total));
      this.openProgressMenu(player, progressText);
      if (total > 0 && found >= total) {
         this.tryCompletionReward(player);
      }
   }

   private void openProgressMenu(Player player, String progressText) {
      HalloweenFeature.ProgressMenuHolder holder = new HalloweenFeature.ProgressMenuHolder();
      Inventory inventory = Bukkit.createInventory(holder, 27, color(this.config.getString("gui.title", "&c&lHalloween")));
      holder.inventory = inventory;
      ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
      ItemMeta glassMeta = glass.getItemMeta();
      glassMeta.setDisplayName(" ");
      glass.setItemMeta(glassMeta);

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         inventory.setItem(slot, glass);
      }

      ItemStack pumpkin = new ItemStack(Material.CARVED_PUMPKIN);
      ItemMeta pumpkinMeta = pumpkin.getItemMeta();
      pumpkinMeta.setDisplayName(color(progressText));
      pumpkin.setItemMeta(pumpkinMeta);
      inventory.setItem(13, pumpkin);
      player.openInventory(inventory);
   }

   @EventHandler
   public void onProgressMenuClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof HalloweenFeature.ProgressMenuHolder) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onProgressMenuDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof HalloweenFeature.ProgressMenuHolder) {
         event.setCancelled(true);
      }
   }

   private void giveHalloweenHead(Player player) {
      ItemStack configuredSkull = this.config.getItemStack("head.skull-item");
      ItemStack item;
      if (configuredSkull != null && configuredSkull.getType() == Material.PLAYER_HEAD) {
         item = configuredSkull.clone();
      } else {
         item = new ItemStack(Material.PLAYER_HEAD);
         ItemMeta meta = item.getItemMeta();
         meta.setDisplayName(color(this.config.getString("head.display-name", "&c&lHalloween-Kopf")));
         meta.setLore(List.of(color(this.config.getString("head.lore", "&7Platziere diesen Kopf als Event-Kopf."))));
         item.setItemMeta(meta);
      }

      item.setAmount(1);
      ItemMeta meta = item.getItemMeta();
      meta.getPersistentDataContainer().set(this.halloweenHeadTag, PersistentDataType.BYTE, (byte)1);
      item.setItemMeta(meta);
      Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack[]{item});
      leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
      this.sendPath(
         player,
         leftovers.isEmpty() ? "messages.head-given" : "messages.head-dropped",
         leftovers.isEmpty()
            ? "&aDu hast einen markierten Halloween-Kopf erhalten. Platziere ihn, um ihn zum Event hinzuzufügen."
            : "&eDein Inventar war voll. Der Halloween-Kopf wurde neben dir abgelegt."
      );
   }

   private void saveHeldSkull(Player player) {
      ItemStack held = player.getInventory().getItemInMainHand();
      if (held.getType() != Material.PLAYER_HEAD) {
         this.sendPath(player, "messages.skull-invalid", "&cHalte einen Spieler-Kopf (PLAYER_HEAD) in deiner Haupthand.");
      } else {
         ItemStack template = held.clone();
         template.setAmount(1);
         ItemMeta meta = template.getItemMeta();
         meta.getPersistentDataContainer().remove(this.halloweenHeadTag);
         template.setItemMeta(meta);
         this.config.set("head.skull-item", template);
         if (!this.save()) {
            this.sendPath(player, "messages.config-save-failed", "&cDie Kopf-Vorlage konnte nicht gespeichert werden.");
         } else {
            this.sendPath(player, "messages.skull-saved", "&aDer Spieler-Kopf aus deiner Hand ist jetzt die Vorlage für &e/halloween set&a.");
         }
      }
   }

   private void resetEvent(CommandSender sender) {
      int removed = 0;
      ConfigurationSection heads = this.data.getConfigurationSection("heads");
      if (heads != null) {
         for (String id : new ArrayList<String>(heads.getKeys(false))) {
            String path = "heads." + id + ".";
            String worldName = this.data.getString(path + "world");
            if (worldName != null) {
               World world = Bukkit.getWorld(worldName);
               if (world == null) {
                  world = this.loadExistingWorld(worldName);
               }

               if (world != null) {
                  Block block = world.getBlockAt(this.data.getInt(path + "x"), this.data.getInt(path + "y"), this.data.getInt(path + "z"));
                  if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
                     block.setType(Material.AIR, false);
                     removed++;
                  }
               }
            }
         }
      }

      this.data.set("heads", null);
      this.data.set("progress", null);
      this.data.set("reward-claimed", null);
      if (!this.saveData()) {
         this.sendPath(sender, "messages.save-failed", "&cDie Halloween-Daten konnten nicht gespeichert werden.");
      } else {
         this.send(
            sender,
            this.config
               .getString(
                  "messages.reset", "&aHalloween wurde zurückgesetzt. &e%heads%&a Köpfe wurden entfernt und alle Fortschritte sowie Belohnungen zurückgesetzt."
               )
               .replace("%heads%", Integer.toString(removed))
         );
      }
   }

   private World loadExistingWorld(String worldName) {
      if (worldName != null && !worldName.isBlank()) {
         Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
         Path folder = root.resolve(worldName).normalize();
         if (folder.startsWith(root) && folder.toFile().isDirectory()) {
            try {
               return Bukkit.createWorld(new WorldCreator(worldName));
            } catch (RuntimeException exception) {
               this.plugin.getLogger().warning("Halloween-Reset konnte Welt '" + worldName + "' nicht laden: " + exception.getMessage());
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onMarkedHeadPlaced(BlockPlaceEvent event) {
      ItemStack item = event.getItemInHand();
      if (this.hasHalloweenMarker(item)) {
         String id = UUID.randomUUID().toString();
         this.storeHead(id, event.getBlockPlaced().getLocation());
         if (!this.saveData()) {
            this.plugin.getLogger().severe("Ein Halloween-Kopf wurde platziert, aber features/halloween/data.yml konnte nicht gespeichert werden.");
            this.sendPath(event.getPlayer(), "messages.save-failed", "&cDie Halloween-Kopfdaten konnten nicht gespeichert werden.");
         } else {
            this.sendPath(event.getPlayer(), "messages.head-registered", "&aDer Kopf wurde als Halloween-Event-Kopf gespeichert.");
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onRegisteredHeadBroken(BlockBreakEvent event) {
      String id = this.headIdAt(event.getBlock().getLocation());
      if (id != null) {
         this.data.set("heads." + id, null);
         ConfigurationSection players = this.data.getConfigurationSection("progress");
         if (players != null) {
            for (String playerId : players.getKeys(false)) {
               List<String> collected = new ArrayList<>(this.data.getStringList("progress." + playerId + ".collected"));
               if (collected.remove(id)) {
                  this.data.set("progress." + playerId + ".collected", collected);
               }
            }
         }

         if (!this.saveData()) {
            this.plugin.getLogger().severe("Ein Halloween-Kopf wurde abgebaut, aber die Änderung konnte nicht gespeichert werden.");
         }
      }
   }

   @EventHandler
   public void onHalloweenHeadClick(PlayerInteractEvent event) {
      if (event.getHand() == EquipmentSlot.HAND && event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null && this.isEnabled()) {
         String id = this.headIdAt(event.getClickedBlock().getLocation());
         if (id != null) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (!this.hasPermission(player, "user")) {
               this.sendPath(player, "messages.no-permission", "&cDazu hast du keine Berechtigung.");
            } else {
               Set<String> collected = this.collectedHeadIds(player.getUniqueId());
               if (!collected.add(id)) {
                  this.sendPath(player, "messages.already-collected", "&eDiesen Halloween-Kopf hast du bereits gefunden.");
                  this.tryCompletionReward(player);
               } else {
                  this.data.set("progress." + player.getUniqueId() + ".collected", new ArrayList<>(collected));
                  if (!this.saveData()) {
                     collected.remove(id);
                     this.data.set("progress." + player.getUniqueId() + ".collected", new ArrayList<>(collected));
                     this.sendPath(player, "messages.save-failed", "&cDein Fortschritt konnte nicht gespeichert werden.");
                  } else {
                     player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7F, 1.4F);
                     this.send(
                        player,
                        this.config
                           .getString("messages.head-found", "&aHalloween-Kopf gefunden! (&e%found%&a/&e%total%&a)")
                           .replace("%found%", Integer.toString(collected.size()))
                           .replace("%total%", Integer.toString(this.registeredHeadIds().size()))
                     );
                     this.tryCompletionReward(player);
                  }
               }
            }
         }
      }
   }

   private void tryCompletionReward(Player player) {
      int total = this.registeredHeadIds().size();
      if (total != 0 && this.collectedHeadIds(player.getUniqueId()).size() >= total && !this.data.getBoolean("reward-claimed." + player.getUniqueId())) {
         BigDecimal configuredAmount;
         try {
            configuredAmount = CompactAmountParser.parse(this.config.getString("reward.completion-amount", "25k"));
            if (configuredAmount.signum() <= 0 || !Double.isFinite(configuredAmount.doubleValue())) {
               throw new NumberFormatException("Belohnung muss eine positive, endliche Zahl sein.");
            }
         } catch (NumberFormatException exception) {
            this.sendPath(player, "messages.reward-invalid", "&cDie Halloween-Belohnung in features/halloween/config.yml ist ungültig.");
            this.plugin.getLogger().warning("Ungültige Halloween-Abschlussbelohnung: " + this.config.getString("reward.completion-amount", "25k"));
            return;
         }

         EconomyService economy = this.plugin.getEconomyService();
         if (economy == null) {
            this.sendPath(player, "messages.reward-economy-unavailable", "&cDas GreenAttack-Economy-System ist gerade nicht verfügbar.");
         } else {
            String claimPath = "reward-claimed." + player.getUniqueId();
            this.data.set(claimPath, true);
            if (!this.saveData()) {
               this.data.set(claimPath, null);
               this.sendPath(player, "messages.save-failed", "&cDein Belohnungsanspruch konnte nicht gespeichert werden.");
            } else if (!economy.deposit(player, configuredAmount.doubleValue())) {
               this.data.set(claimPath, null);
               this.saveData();
               this.sendPath(player, "messages.reward-failed", "&cDie Belohnung konnte nicht ausgezahlt werden. Versuche es erneut.");
            } else {
               this.send(
                  player,
                  this.config
                     .getString("messages.reward-paid", "&6Alle Halloween-Köpfe gefunden! &aDu erhältst &e%amount%&a.")
                     .replace("%amount%", economy.format(configuredAmount.doubleValue()))
               );
            }
         }
      }
   }

   private boolean hasHalloweenMarker(ItemStack item) {
      return item != null && item.hasItemMeta() ? item.getItemMeta().getPersistentDataContainer().has(this.halloweenHeadTag, PersistentDataType.BYTE) : false;
   }

   private Set<String> registeredHeadIds() {
      ConfigurationSection heads = this.data.getConfigurationSection("heads");
      return heads == null ? new HashSet<>() : new HashSet<>(heads.getKeys(false));
   }

   private Set<String> collectedHeadIds(UUID playerId) {
      Set<String> registered = this.registeredHeadIds();
      Set<String> collected = new HashSet<>(this.data.getStringList("progress." + playerId + ".collected"));
      collected.retainAll(registered);
      return collected;
   }

   private String headIdAt(Location location) {
      if (location != null && location.getWorld() != null) {
         ConfigurationSection heads = this.data.getConfigurationSection("heads");
         if (heads == null) {
            return null;
         }

         for (String id : heads.getKeys(false)) {
            String path = "heads." + id + ".";
            if (location.getWorld().getName().equals(this.data.getString(path + "world"))
               && location.getBlockX() == this.data.getInt(path + "x")
               && location.getBlockY() == this.data.getInt(path + "y")
               && location.getBlockZ() == this.data.getInt(path + "z")) {
               return id;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private void storeHead(String id, Location location) {
      String path = "heads." + id + ".";
      this.data.set(path + "world", location.getWorld().getName());
      this.data.set(path + "x", location.getBlockX());
      this.data.set(path + "y", location.getBlockY());
      this.data.set(path + "z", location.getBlockZ());
   }

   private void ensureRewardDefault() {
      boolean changed = false;
      if (!this.config.contains("reward.completion-amount")) {
         this.config.set("reward.completion-amount", "25k");
         changed = true;
      }

      String expectedUsage = "&cNutze: &e/halloween [set [skull]|reset]";
      if (!expectedUsage.equals(this.config.getString("messages.usage"))) {
         this.config.set("messages.usage", expectedUsage);
         changed = true;
      }

      if (changed) {
         this.save();
      }
   }

   private void loadData() {
      boolean existed = this.dataFile.isFile();
      this.data = YamlConfiguration.loadConfiguration(this.dataFile);
      if (!existed) {
         this.migrateLegacyData();
      }

      this.saveData();
   }

   private void migrateLegacyData() {
      List<String> legacyLocations = this.config.getStringList("active-pumpkins");
      Map<String, String> idsByLocation = new HashMap<>();

      for (String key : legacyLocations) {
         HalloweenFeature.LocationParts parts = this.parseLegacyLocation(key);
         if (parts != null) {
            String id = UUID.nameUUIDFromBytes(("greenattack-halloween:" + key).getBytes(StandardCharsets.UTF_8)).toString();
            this.data.set("heads." + id + ".world", parts.world());
            this.data.set("heads." + id + ".x", parts.x());
            this.data.set("heads." + id + ".y", parts.y());
            this.data.set("heads." + id + ".z", parts.z());
            idsByLocation.put(key, id);
         }
      }

      ConfigurationSection oldClaims = this.config.getConfigurationSection("claimed");
      ConfigurationSection oldFound = this.config.getConfigurationSection("found");
      Set<String> playerIds = new HashSet<>();
      if (oldClaims != null) {
         playerIds.addAll(oldClaims.getKeys(false));
      }

      if (oldFound != null) {
         playerIds.addAll(oldFound.getKeys(false));
      }

      List<String> orderedIds = idsByLocation.values().stream().sorted().toList();

      for (String playerId : playerIds) {
         Set<String> collected = new HashSet<>();

         for (String locationKey : this.config.getStringList("claimed." + playerId)) {
            String id = idsByLocation.get(locationKey);
            if (id != null) {
               collected.add(id);
            }
         }

         int legacyCount = Math.max(0, this.config.getInt("found." + playerId, 0));

         for (String id : orderedIds) {
            if (collected.size() >= legacyCount) {
               break;
            }

            collected.add(id);
         }

         if (!collected.isEmpty()) {
            this.data.set("progress." + playerId + ".collected", new ArrayList<>(collected));
         }
      }

      if (!idsByLocation.isEmpty()) {
         this.plugin.getLogger().info("Halloween-Kopfdaten wurden aus der alten Konfiguration nach features/halloween/data.yml übernommen.");
      }
   }

   private HalloweenFeature.LocationParts parseLegacyLocation(String value) {
      String[] parts = value.split("\\|", -1);
      if (parts.length != 4) {
         return null;
      }

      try {
         return new HalloweenFeature.LocationParts(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
      } catch (NumberFormatException exception) {
         return null;
      }
   }

   private boolean saveData() {
      try {
         File parent = this.dataFile.getParentFile();
         if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Feature-Ordner konnte nicht erstellt werden.");
         }

         this.data.save(this.dataFile);
         return true;
      } catch (IOException exception) {
         this.plugin.getLogger().severe("Halloween-Daten konnten nicht gespeichert werden: " + exception.getMessage());
         return false;
      }
   }

   private record LocationParts(String world, int x, int y, int z) {
   }

   private static final class ProgressMenuHolder implements InventoryHolder {
      private Inventory inventory;

      public Inventory getInventory() {
         return this.inventory;
      }
   }
}

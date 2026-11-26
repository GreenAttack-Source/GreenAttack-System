package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import de.greenattack.system.economy.EconomyService;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.view.AnvilView;

public final class AktionshausFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final int PAGE_SIZE = 24;
   private static final int CREATE_ITEM_SLOT = 13;
   private static final int CREATE_BUY_NOW_SLOT = 29;
   private static final int CREATE_START_BID_SLOT = 31;
   private static final int CREATE_DURATION_SLOT = 33;
   private static final int CREATE_BACK_SLOT = 36;
   private static final int CREATE_READY_SLOT = 44;
   private static final List<Integer> DEFAULT_DURATIONS_MINUTES = List.of(5, 15, 60, 180, 360, 540, 720, 1440, 4320);
   private final Map<UUID, String> searchTerms = new ConcurrentHashMap<>();
   private final Map<UUID, AktionshausFeature.SortMode> sortModes = new ConcurrentHashMap<>();
   private final Map<UUID, AktionshausFeature.CreationState> creationStates = new ConcurrentHashMap<>();

   public AktionshausFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "aktionshaus");
      this.mergeGuiDefaults();
      this.migrateLegacyTitles();
      this.migrateCategoryColors();
      Bukkit.getPluginManager().registerEvents(this, plugin);
      Bukkit.getScheduler().runTaskTimer(plugin, this::expireListings, 600L, 600L);
   }

   public void reloadFeature() {
      this.reload();
      this.mergeGuiDefaults();
      this.migrateLegacyTitles();
      this.migrateCategoryColors();
   }

   private void mergeGuiDefaults() {
      try (InputStream resource = this.plugin.getResource("features/aktionshaus/config.yml")) {
         if (resource != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
            boolean changed = false;

            for (String path : defaults.getKeys(true)) {
               if ((path.startsWith("gui.") || path.startsWith("messages.") || path.startsWith("creation."))
                  && !defaults.isConfigurationSection(path)
                  && !this.config.contains(path, true)) {
                  this.config.set(path, defaults.get(path));
                  changed = true;
               }
            }

            if (changed) {
               this.save();
            }
         }
      } catch (IOException exception) {
         this.plugin.getLogger().warning("Konnte Aktionshaus-Standardwerte nicht ergänzen: " + exception.getMessage());
      }
   }

   private void migrateLegacyTitles() {
      boolean changed = false;
      Map<String, String> legacyTitles = Map.of(
         "gui.title",
         "&aGreenAttack &8» &7Aktionshaus",
         "gui.my-title",
         "&aGreenAttack &8» &7Deine Auktionen",
         "gui.overview-title",
         "&aGreenAttack &8» &7Deine Übersicht",
         "gui.sort-title",
         "&aGreenAttack &8» &7Sortierung"
      );

      for (Entry<String, String> entry : legacyTitles.entrySet()) {
         String current = this.config.getString(entry.getKey());
         if (current != null) {
            String updated = current;
            if ("&8GreenAttack Aktionshaus".equals(current) || "&8Meine Angebote".equals(current)) {
               updated = entry.getValue();
            } else if (current.contains("OPSUCHT")) {
               String withoutLeadingColors = current.replace("OPSUCHT", "GreenAttack").replaceFirst("(?i)^(?:[&§][0-9A-FK-OR])+", "");
               updated = "&a" + withoutLeadingColors;
            }

            if (!updated.equals(current)) {
               this.config.set(entry.getKey(), updated);
               changed = true;
            }
         }
      }

      if (changed) {
         this.save();
      }
   }

   private void migrateCategoryColors() {
      boolean changed = false;
      Map<String, String> categoryNames = Map.of(
         "gui.categories.custom-items",
         "&c&lCustom Items",
         "gui.categories.weapons",
         "&c&lWerkzeuge & Kampf",
         "gui.categories.armor",
         "&c&lRüstungen",
         "gui.categories.op-items.name",
         "&c&lOP Items",
         "gui.categories.books",
         "&c&lBücher",
         "gui.categories.other",
         "&c&lAnderes"
      );
      Map<String, String> oldDefaultNames = Map.of(
         "gui.categories.weapons",
         "Waffen [Noch nicht einsatzbereit]",
         "gui.categories.armor",
         "Rüstung [Noch nicht einsatzbereit]",
         "gui.categories.other",
         "Weitere Kategorien [Noch nicht einsatzbereit]"
      );

      for (Entry<String, String> entry : categoryNames.entrySet()) {
         String path = entry.getKey();
         String value = this.config.getString(path);
         if (value != null) {
            String oldDefault = oldDefaultNames.get(path);
            String withoutColors = value.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
            if (oldDefault != null && oldDefault.equals(withoutColors)) {
               this.config.set(path, entry.getValue());
               changed = true;
            } else if (!value.startsWith("&c&l")) {
               String withoutLeadingColors = value.replaceFirst("(?i)^(?:[&§][0-9A-FK-OR])+", "");
               this.config.set(path, "&c&l" + withoutLeadingColors);
               changed = true;
            }
         }
      }

      if (changed) {
         this.save();
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Aktionshaus ist deaktiviert.");
         return true;
      }

      if (args.length == 0) {
         if (sender instanceof Player player) {
            if (!this.hasPermission(player, "permissions.use", "system.aktionshaus.use")) {
               return true;
            }

            this.openListings(player, 0, false);
            return true;
         } else {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         }
      } else {
         String subcommand = args[0].toLowerCase(Locale.ROOT);
         if (subcommand.equals("reload")) {
            if (!this.hasPermission(sender, "permissions.admin", "system.aktionshaus.admin")) {
               return true;
            }

            this.reload();
            this.sendPath(sender, "messages.reload", "&aDie Aktionshaus-Konfiguration wurde neu geladen.");
            return true;
         } else if (subcommand.equals("admin")) {
            if (sender instanceof Player player) {
               if (!this.hasPermission(player, "permissions.admin", "system.aktionshaus.admin")) {
                  return true;
               }

               this.openAdminMenu(player, 0);
               return true;
            } else {
               this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
               return true;
            }
         } else if (!(sender instanceof Player player)) {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         } else {
            if (!this.hasPermission(player, "permissions.use", "system.aktionshaus.use")) {
               return true;
            }

            switch (subcommand) {
               case "verkaufen":
               case "sell":
                  if (args.length != 2) {
                     this.sendPath(player, "messages.sell-usage", "&7Verwendung: &f/ah verkaufen <preis>");
                     return true;
                  }

                  this.createListing(player, args[1]);
                  break;
               case "suche":
               case "search":
                  if (args.length == 1) {
                     this.requestSearch(player);
                  } else {
                     String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                     if (!query.isEmpty() && !query.equalsIgnoreCase("clear") && !query.equalsIgnoreCase("zurücksetzen")) {
                        this.searchTerms.put(player.getUniqueId(), query);
                        this.send(player, this.config.getString("messages.search-result", "&7Suche nach: &e%query%").replace("%query%", query));
                     } else {
                        this.searchTerms.remove(player.getUniqueId());
                        this.sendPath(player, "messages.search-cleared", "&7Die Suche wurde zurückgesetzt.");
                     }

                     this.openListings(player, 0, false);
                  }
                  break;
               case "meine":
               case "mine":
                  this.openListings(player, 0, true);
                  break;
               case "abbrechen":
               case "cancel":
                  if (args.length != 2) {
                     this.sendPath(player, "messages.cancel-usage", "&7Verwendung: &f/ah abbrechen <id>");
                     return true;
                  }

                  this.cancelListing(player, args[1]);
                  break;
               default:
                  this.sendPath(player, "messages.usage", "&7Verwendung: &f/ah [suche <name>|verkaufen <preis>|meine|abbrechen <id>]");
            }

            return true;
         }
      }
   }

   private void createListing(Player seller, String priceInput) {
      EconomyService economy = this.plugin.getEconomyService();
      if (economy == null) {
         this.sendPath(seller, "messages.economy-unavailable", "&cDie Economy ist nicht verfügbar.");
      } else {
         ItemStack hand = seller.getInventory().getItemInMainHand();
         if (hand.getType().isAir()) {
            this.sendPath(seller, "messages.hold-item", "&cDu musst das Item zum Verkaufen in der Hand halten.");
         } else {
            double price;
            try {
               price = Double.parseDouble(priceInput.replace(',', '.'));
            } catch (NumberFormatException exception) {
               this.sendPath(seller, "messages.invalid-price", "&cBitte gib einen gültigen Preis größer als 0 an.");
               return;
            }

            if (Double.isFinite(price) && !(price <= 0.0) && !(price > this.config.getDouble("limits.max-price", 1.0E12))) {
               int maxListings = Math.max(1, this.config.getInt("limits.max-listings-per-player", 10));
               long ownCount = this.listings().stream().filter(listing -> listing.sellerId().equals(seller.getUniqueId())).count();
               if (ownCount >= maxListings) {
                  this.send(
                     seller,
                     this.config
                        .getString("messages.too-many", "&cDu kannst höchstens &e%limit% &cAngebote einstellen.")
                        .replace("%limit%", Integer.toString(maxListings))
                  );
               } else {
                  String id = Long.toString(this.config.getLong("storage.next-id", 1L));
                  this.config.set("storage.next-id", this.config.getLong("storage.next-id", 1L) + 1L);
                  String path = "storage.listings." + id;
                  ItemStack item = hand.clone();
                  this.config.set(path + ".seller", seller.getUniqueId().toString());
                  this.config.set(path + ".seller-name", seller.getName());
                  this.config.set(path + ".price", price);
                  this.config.set(path + ".created-at", System.currentTimeMillis());
                  this.config.set(path + ".item", item);
                  if (!this.save()) {
                     this.config.set(path, null);
                  } else {
                     seller.getInventory().setItemInMainHand(null);
                     this.send(
                        seller,
                        this.config
                           .getString("messages.listed", "&aAngebot &e#%id% &afür &e%price% &aeingestellt.")
                           .replace("%id%", id)
                           .replace("%price%", economy.format(price))
                     );
                  }
               }
            } else {
               this.sendPath(seller, "messages.invalid-price", "&cBitte gib einen gültigen Preis größer als 0 an.");
            }
         }
      }
   }

   private void createListingFromMenu(Player seller, AktionshausFeature.CreationState state) {
      this.expireListings();
      if (state.selectedItem != null && !state.selectedItem.getType().isAir()) {
         EconomyService economy = this.plugin.getEconomyService();
         if (economy == null) {
            this.sendPath(seller, "messages.economy-unavailable", "&cDie Economy ist nicht verfügbar.");
         } else {
            int maxListings = Math.max(1, this.config.getInt("limits.max-listings-per-player", 10));
            long ownCount = this.listings().stream().filter(listing -> listing.sellerId().equals(seller.getUniqueId())).count();
            if (ownCount >= maxListings) {
               this.send(
                  seller,
                  this.config
                     .getString("messages.too-many", "&cDu kannst höchstens &e%limit% &cAngebote einstellen.")
                     .replace("%limit%", Integer.toString(maxListings))
               );
            } else {
               Double instantPrice = state.instantPrice;
               Double startingBid = state.startingBid;
               if (instantPrice == null && startingBid == null) {
                  instantPrice = 0.0;
                  startingBid = 0.0;
               } else if (instantPrice != null && startingBid == null) {
                  startingBid = 0.0;
               }

               double displayPrice = instantPrice != null ? instantPrice : startingBid;
               List<Integer> durations = this.durationOptions();
               if (state.durationIndex < 0 || state.durationIndex >= durations.size()) {
                  state.durationIndex = 0;
               }

               int durationMinutes = durations.get(state.durationIndex);
               long createdAt = System.currentTimeMillis();
               String id = Long.toString(this.config.getLong("storage.next-id", 1L));
               this.config.set("storage.next-id", this.config.getLong("storage.next-id", 1L) + 1L);
               String path = this.listingPath(id);
               this.config.set(path + ".seller", seller.getUniqueId().toString());
               this.config.set(path + ".seller-name", seller.getName());
               this.config.set(path + ".price", displayPrice);
               if (instantPrice == null) {
                  this.config.set(path + ".instant-price", null);
               } else {
                  this.config.set(path + ".instant-price", instantPrice);
               }

               this.config.set(path + ".starting-bid", startingBid);
               this.config.set(path + ".duration-minutes", durationMinutes);
               this.config.set(path + ".expires-at", createdAt + durationMinutes * 60000L);
               this.config.set(path + ".created-at", createdAt);
               this.config.set(path + ".item", state.selectedItem.clone());
               if (!this.save()) {
                  this.config.set(path, null);
               } else {
                  state.selectedItem = null;
                  this.creationStates.remove(seller.getUniqueId());
                  this.send(
                     seller,
                     this.config
                        .getString("messages.listed", "&aAngebot &e#%id% &afür &e%price% &aeingestellt.")
                        .replace("%id%", id)
                        .replace("%price%", economy.format(displayPrice))
                  );
                  this.openListings(seller, 0, false);
               }
            }
         }
      } else {
         this.sendPath(seller, "messages.item-required", "&cWähle zuerst ein Item aus deinem Inventar aus.");
      }
   }

   private void openListings(Player player, int requestedPage, boolean ownOnly) {
      this.openListings(player, requestedPage, ownOnly, AktionshausFeature.ListingCategory.ALL);
   }

   private void openListings(Player player, int requestedPage, boolean ownOnly, boolean opItemsOnly) {
      this.openListings(player, requestedPage, ownOnly, opItemsOnly ? AktionshausFeature.ListingCategory.OP_ITEMS : AktionshausFeature.ListingCategory.ALL);
   }

   private void openListings(Player player, int requestedPage, boolean ownOnly, AktionshausFeature.ListingCategory category) {
      this.expireListings();
      List<AktionshausFeature.Listing> all = this.visibleListings(player, ownOnly, category);
      int pages = Math.max(1, (all.size() + 24 - 1) / 24);
      int page = Math.max(0, Math.min(requestedPage, pages - 1));
      AktionshausFeature.ListingsHolder holder = new AktionshausFeature.ListingsHolder(page, ownOnly, category);
      String titlePath = ownOnly ? "gui.my-title" : "gui.title";
      String fallback = ownOnly ? "&aGreenAttack &8» &7Deine Auktionen" : "&aGreenAttack &8» &7Aktionshaus";
      String title = color(this.plugin.applyPlaceholders(player, this.config.getString(titlePath, fallback)));
      Inventory inventory = Bukkit.createInventory(holder, 54, title);
      holder.inventory = inventory;
      ItemStack pane = this.named(Material.GRAY_STAINED_GLASS_PANE, " ");

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         inventory.setItem(slot, pane);
      }

      inventory.setItem(0, this.named(Material.DIAMOND, this.config.getString("gui.categories.custom-items", "&c&lCustom Items")));
      inventory.setItem(9, this.named(Material.NETHERITE_SWORD, this.config.getString("gui.categories.weapons", "&c&lWerkzeuge & Kampf")));
      inventory.setItem(18, this.named(Material.NETHERITE_CHESTPLATE, this.config.getString("gui.categories.armor", "&c&lRüstungen")));
      inventory.setItem(27, this.named(Material.BEACON, this.config.getString("gui.categories.op-items.name", "&c&lOP Items")));
      inventory.setItem(36, this.named(Material.ENCHANTED_BOOK, this.config.getString("gui.categories.books", "&c&lBücher")));
      inventory.setItem(45, this.named(Material.CHEST, this.config.getString("gui.categories.other", "&c&lAnderes")));
      int start = page * 24;

      for (int index = start; index < Math.min(start + 24, all.size()); index++) {
         AktionshausFeature.Listing listing = all.get(index);
         int slot = this.listingSlot(index - start);
         inventory.setItem(slot, this.listingItem(player, listing, ownOnly));
      }

      if (page > 0) {
         inventory.setItem(48, this.named(Material.ARROW, this.config.getString("gui.previous-page", "&e« Zurück")));
      }

      inventory.setItem(49, this.named(Material.SPYGLASS, this.config.getString("gui.search-name", "&bSuche nach Item")));
      inventory.setItem(50, this.named(Material.HOPPER, this.config.getString("gui.sort-name", "&cSortierung")));
      inventory.setItem(51, pane);
      if (page + 1 < pages) {
         inventory.setItem(52, this.named(Material.ARROW, this.config.getString("gui.next-page", "&eNächste Seite »")));
      }

      inventory.setItem(53, this.playerHead(player, this.config.getString("gui.overview-name", "&cDeine Übersicht")));
      player.openInventory(inventory);
   }

   private List<AktionshausFeature.Listing> visibleListings(Player player, boolean ownOnly) {
      return this.visibleListings(player, ownOnly, AktionshausFeature.ListingCategory.ALL);
   }

   private List<AktionshausFeature.Listing> visibleListings(Player player, boolean ownOnly, boolean opItemsOnly) {
      return this.visibleListings(player, ownOnly, opItemsOnly ? AktionshausFeature.ListingCategory.OP_ITEMS : AktionshausFeature.ListingCategory.ALL);
   }

   private List<AktionshausFeature.Listing> visibleListings(Player player, boolean ownOnly, AktionshausFeature.ListingCategory category) {
      String search = ownOnly ? "" : this.searchTerms.getOrDefault(player.getUniqueId(), "").toLowerCase(Locale.ROOT);
      List<String> opItemPatterns = this.config
         .getStringList("gui.categories.op-items.materials")
         .stream()
         .map(value -> value.toUpperCase(Locale.ROOT))
         .toList();
      List<AktionshausFeature.Listing> result = this.listings()
         .stream()
         .filter(listing -> !ownOnly || listing.sellerId().equals(player.getUniqueId()))
         .filter(listing -> this.matchesCategory(listing.item(), category, opItemPatterns))
         .filter(
            listing -> search.isBlank()
               || listing.item().getType().name().toLowerCase(Locale.ROOT).contains(search)
               || this.itemDisplayName(listing.item()).toLowerCase(Locale.ROOT).contains(search)
         )
         .collect(Collectors.toCollection(ArrayList::new));
      AktionshausFeature.SortMode sort = this.sortModes.getOrDefault(player.getUniqueId(), AktionshausFeature.SortMode.LOWEST_PRICE);

      Comparator<AktionshausFeature.Listing> comparator = switch (sort) {
         case NEWEST -> Comparator.comparingLong(AktionshausFeature.Listing::createdAt).reversed();
         case OLDEST -> Comparator.comparingLong(AktionshausFeature.Listing::createdAt);
         case LOWEST_PRICE -> Comparator.comparingDouble(AktionshausFeature.Listing::price);
         case HIGHEST_PRICE -> Comparator.comparingDouble(AktionshausFeature.Listing::price).reversed();
      };
      result.sort(comparator);
      return result;
   }

   private boolean matchesCategory(ItemStack item, AktionshausFeature.ListingCategory category, List<String> opItemPatterns) {
      String material = item.getType().name();

      return switch (category) {
         case ALL -> true;
         case CUSTOM_ITEMS -> this.isCustomItem(item);
         case WEAPONS -> this.isCombatItem(material);
         case ARMOR -> this.isArmorItem(material);
         case OP_ITEMS -> this.isOpItem(material, opItemPatterns);
         case BOOKS -> this.isBookItem(material);
         case OTHER -> !this.isCustomItem(item)
            && !this.isCombatItem(material)
            && !this.isArmorItem(material)
            && !this.isOpItem(material, opItemPatterns)
            && !this.isBookItem(material);
      };
   }

   private boolean isCustomItem(ItemStack item) {
      ItemMeta meta = item.getItemMeta();
      return meta != null && (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants());
   }

   private boolean isCombatItem(String material) {
      return material.endsWith("_SWORD")
         || material.endsWith("_AXE")
         || material.endsWith("_PICKAXE")
         || material.endsWith("_SHOVEL")
         || material.endsWith("_HOE")
         || material.equals("BOW")
         || material.equals("CROSSBOW")
         || material.equals("TRIDENT")
         || material.equals("MACE")
         || material.endsWith("_ARROW");
   }

   private boolean isArmorItem(String material) {
      return material.endsWith("_HELMET")
         || material.endsWith("_CHESTPLATE")
         || material.endsWith("_LEGGINGS")
         || material.endsWith("_BOOTS")
         || material.equals("ELYTRA")
         || material.equals("SHIELD");
   }

   private boolean isBookItem(String material) {
      return List.of("BOOK", "ENCHANTED_BOOK", "WRITABLE_BOOK", "WRITTEN_BOOK", "KNOWLEDGE_BOOK").contains(material);
   }

   private boolean isOpItem(String material, List<String> patterns) {
      for (String pattern : patterns) {
         if (pattern.equals("*")) {
            return true;
         }

         if (pattern.startsWith("*") && pattern.endsWith("*") && pattern.length() > 2 && material.contains(pattern.substring(1, pattern.length() - 1))) {
            return true;
         }

         if (pattern.startsWith("*") && material.endsWith(pattern.substring(1))) {
            return true;
         }

         if (pattern.endsWith("*") && material.startsWith(pattern.substring(0, pattern.length() - 1))) {
            return true;
         }

         if (material.equals(pattern)) {
            return true;
         }
      }

      return false;
   }

   private int listingSlot(int index) {
      int row = index / 6;
      int column = index % 6;
      return (row + 1) * 9 + column + 2;
   }

   private void openAdminMenu(Player admin, int requestedPage) {
      List<AktionshausFeature.Listing> all = this.listings()
         .stream()
         .sorted(Comparator.comparingLong(AktionshausFeature.Listing::createdAt).reversed())
         .toList();
      int pages = Math.max(1, (all.size() + 24 - 1) / 24);
      int page = Math.max(0, Math.min(requestedPage, pages - 1));
      AktionshausFeature.AdminHolder holder = new AktionshausFeature.AdminHolder(page);
      Inventory inventory = Bukkit.createInventory(
         holder, 54, color(this.plugin.applyPlaceholders(admin, this.config.getString("gui.admin-title", "&aGreenAttack &8» &cAktionshaus Admin")))
      );
      holder.inventory = inventory;
      ItemStack pane = this.named(Material.GRAY_STAINED_GLASS_PANE, " ");

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         inventory.setItem(slot, pane);
      }

      int start = page * 24;

      for (int index = start; index < Math.min(start + 24, all.size()); index++) {
         AktionshausFeature.Listing listing = all.get(index);
         inventory.setItem(this.listingSlot(index - start), this.adminListingItem(admin, listing));
      }

      if (page > 0) {
         inventory.setItem(48, this.named(Material.ARROW, this.config.getString("gui.previous-page", "&e« Zurück")));
      }

      inventory.setItem(49, this.named(Material.ENDER_CHEST, this.config.getString("gui.admin-back", "&7Zum Aktionshaus")));
      if (page + 1 < pages) {
         inventory.setItem(52, this.named(Material.ARROW, this.config.getString("gui.next-page", "&eNächste Seite »")));
      }

      admin.openInventory(inventory);
   }

   private ItemStack adminListingItem(Player viewer, AktionshausFeature.Listing listing) {
      ItemStack item = listing.item().clone();
      ItemMeta meta = item.getItemMeta();
      if (meta == null) {
         return item;
      }

      List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
      lore.add("");
      List<String> configured = this.config.getStringList("gui.admin-listing-lore");
      if (configured.isEmpty()) {
         configured = List.of("&7Spieler: &e%seller%", "&7Angebot: &e#%id%", "", "&aKlicken, um das Item zurückzusenden");
      }

      EconomyService economy = this.plugin.getEconomyService();
      String price = listing.instantPrice() == null
         ? "-"
         : (economy == null ? Double.toString(listing.instantPrice()) : economy.format(listing.instantPrice()));

      for (String line : configured) {
         lore.add(
            color(this.plugin.applyPlaceholders(viewer, line.replace("%seller%", listing.sellerName()).replace("%id%", listing.id()).replace("%price%", price)))
         );
      }

      meta.setLore(lore);
      item.setItemMeta(meta);
      return item;
   }

   private String itemDisplayName(ItemStack item) {
      ItemMeta meta = item.getItemMeta();
      return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : item.getType().name();
   }

   private ItemStack playerHead(Player player, String name) {
      ItemStack head = new ItemStack(Material.PLAYER_HEAD);
      if (head.getItemMeta() instanceof SkullMeta meta) {
         meta.setOwningPlayer(player);
         meta.setDisplayName(color(name));
         head.setItemMeta(meta);
      }

      return head;
   }

   private void openOverview(Player player) {
      AktionshausFeature.OverviewHolder holder = new AktionshausFeature.OverviewHolder();
      Inventory inventory = Bukkit.createInventory(
         holder, 36, color(this.plugin.applyPlaceholders(player, this.config.getString("gui.overview-title", "&aGreenAttack &8» &7Deine Übersicht")))
      );
      holder.inventory = inventory;
      ItemStack pane = this.named(Material.GRAY_STAINED_GLASS_PANE, " ");

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         inventory.setItem(slot, pane);
      }

      inventory.setItem(11, this.named(Material.LIME_DYE, this.config.getString("gui.create-name", "&aAuktion erstellen")));
      inventory.setItem(13, this.named(Material.CHEST, this.config.getString("gui.my-auctions-name", "&eDeine Auktionen")));
      inventory.setItem(15, this.named(Material.GOLD_NUGGET, this.config.getString("gui.my-bids-name", "&eDeine Gebote &8[Noch nicht einsatzbereit]")));
      inventory.setItem(27, this.named(Material.ENDER_CHEST, this.config.getString("gui.back-name", "&7Zurück")));
      player.openInventory(inventory);
   }

   private void openSortMenu(Player player, boolean ownOnly, AktionshausFeature.ListingCategory category) {
      AktionshausFeature.SortHolder holder = new AktionshausFeature.SortHolder(ownOnly, category);
      Inventory inventory = Bukkit.createInventory(holder, 27, color(this.config.getString("gui.sort-title", "&aGreenAttack &8» &7Sortierung")));
      holder.inventory = inventory;
      ItemStack pane = this.named(Material.GRAY_STAINED_GLASS_PANE, " ");

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         inventory.setItem(slot, pane);
      }

      AktionshausFeature.SortMode selected = this.sortModes.getOrDefault(player.getUniqueId(), AktionshausFeature.SortMode.LOWEST_PRICE);
      inventory.setItem(10, this.sortItem(Material.GOLD_INGOT, "&bHöchste Gebote &8[Noch nicht einsatzbereit]", null, selected));
      inventory.setItem(11, this.sortItem(Material.GOLD_INGOT, "&bNiedrigste Gebote &8[Noch nicht einsatzbereit]", null, selected));
      inventory.setItem(12, this.sortItem(Material.EMERALD, "&bHöchster Sofortkaufpreis", AktionshausFeature.SortMode.HIGHEST_PRICE, selected));
      inventory.setItem(13, this.sortItem(Material.EMERALD, "&bNiedrigster Sofortkaufpreis", AktionshausFeature.SortMode.LOWEST_PRICE, selected));
      inventory.setItem(14, this.sortItem(Material.GOLD_NUGGET, "&bMeiste Gebote &8[Noch nicht einsatzbereit]", null, selected));
      inventory.setItem(15, this.sortItem(Material.CLOCK, "&bBald endend &8[Noch nicht einsatzbereit]", null, selected));
      inventory.setItem(22, this.named(Material.ARROW, this.config.getString("gui.back-name", "&7Zurück")));
      inventory.setItem(
         4, this.named(Material.NAME_TAG, this.config.getString("gui.selected-sort", "&7Aktuell: &e%sort%").replace("%sort%", selected.displayName))
      );
      player.openInventory(inventory);
   }

   private ItemStack sortItem(Material material, String name, AktionshausFeature.SortMode mode, AktionshausFeature.SortMode selected) {
      if (mode != null && mode == selected) {
         name = name + " &a✓";
      }

      return this.named(material, name);
   }

   private void requestSearch(Player player) {
      this.openSearchMenu(player);
   }

   private void openSearchMenu(Player player) {
      AktionshausFeature.SearchHolder holder = new AktionshausFeature.SearchHolder();
      String title = color(this.plugin.applyPlaceholders(player, this.config.getString("gui.search-title", "&aGreenAttack &8» &7Suche")));
      Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL, title);
      holder.inventory = inventory;
      String currentSearch = this.searchTerms.getOrDefault(player.getUniqueId(), "");
      String initialName = currentSearch.isBlank() ? this.config.getString("gui.search-placeholder", "Item suchen") : currentSearch;
      ItemStack searchItem = new ItemStack(Material.SPYGLASS);
      ItemMeta meta = searchItem.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(initialName);
         searchItem.setItemMeta(meta);
      }

      inventory.setItem(0, searchItem);
      player.openInventory(inventory);
      if (player.getOpenInventory() instanceof AnvilView view) {
         view.setRepairCost(0);
      }
   }

   @EventHandler
   public void onSearchAnvilPrepare(PrepareAnvilEvent event) {
      Object holder = event.getView().getTopInventory().getHolder();
      Material previewMaterial;
      if (holder instanceof AktionshausFeature.SearchHolder) {
         previewMaterial = Material.SPYGLASS;
      } else {
         if (!(holder instanceof AktionshausFeature.PriceInputHolder)) {
            return;
         }

         previewMaterial = Material.PAPER;
      }

      AnvilView view = event.getView();
      String query = view.getRenameText();
      if (query != null && !query.isBlank()) {
         ItemStack result = new ItemStack(previewMaterial);
         ItemMeta meta = result.getItemMeta();
         if (meta != null) {
            meta.setDisplayName(query);
            result.setItemMeta(meta);
         }

         event.setResult(result);
         view.setRepairCost(0);
      } else {
         event.setResult(null);
      }
   }

   @EventHandler
   public void onSearchAnvilClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof AktionshausFeature.SearchHolder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory() && event.getRawSlot() == 2) {
            AnvilView view = (AnvilView)event.getView();
            String query = view.getRenameText() == null ? "" : view.getRenameText().trim();
            String placeholder = this.config.getString("gui.search-placeholder", "Item suchen");
            if (!query.isBlank() && !query.equalsIgnoreCase(placeholder)) {
               this.searchTerms.put(player.getUniqueId(), query);
               this.send(player, this.config.getString("messages.search-result", "&7Suche nach: &e%query%").replace("%query%", query));
            } else {
               this.searchTerms.remove(player.getUniqueId());
               this.sendPath(player, "messages.search-cleared", "&7Die Suche wurde zurückgesetzt.");
            }

            this.openListings(player, 0, false);
         }
      }
   }

   private void requestCreate(Player player) {
      int defaultDuration = this.config.getInt("creation.default-duration-minutes", 5);
      List<Integer> durations = this.durationOptions();
      int durationIndex = durations.indexOf(defaultDuration);
      if (durationIndex < 0) {
         durationIndex = 0;
      }

      this.creationStates.put(player.getUniqueId(), new AktionshausFeature.CreationState(durationIndex));
      this.openCreateMenu(player);
   }

   private void openCreateMenu(Player player) {
      AktionshausFeature.CreationState state = this.creationStates.computeIfAbsent(player.getUniqueId(), ignored -> {
         int defaultDuration = this.config.getInt("creation.default-duration-minutes", 5);
         List<Integer> durationsx = this.durationOptions();
         int index = durationsx.indexOf(defaultDuration);
         return new AktionshausFeature.CreationState(Math.max(0, index));
      });
      List<Integer> durations = this.durationOptions();
      if (state.durationIndex < 0 || state.durationIndex >= durations.size()) {
         state.durationIndex = 0;
      }

      AktionshausFeature.CreateHolder holder = new AktionshausFeature.CreateHolder(player.getUniqueId());
      Inventory inventory = Bukkit.createInventory(
         holder, 45, color(this.plugin.applyPlaceholders(player, this.config.getString("gui.create-title", "&aGreenAttack &8» &7Auktion erstellen")))
      );
      holder.inventory = inventory;
      ItemStack pane = this.named(Material.GRAY_STAINED_GLASS_PANE, " ");

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         inventory.setItem(slot, pane);
      }

      if (state.selectedItem == null) {
         inventory.setItem(
            13,
            this.withLore(
               Material.BARRIER, this.config.getString("gui.create-no-item", "&cKein Item ausgewählt"), this.config.getStringList("gui.create-no-item-lore")
            )
         );
      } else {
         inventory.setItem(13, state.selectedItem.clone());
      }

      inventory.setItem(29, this.priceButton(Material.GOLD_NUGGET, this.config.getString("gui.buy-now-name", "&eSofortkaufpreis setzen"), state.instantPrice));
      inventory.setItem(31, this.priceButton(Material.BUNDLE, this.config.getString("gui.start-bid-name", "&eStartgebot setzen"), state.startingBid));
      inventory.setItem(33, this.durationButton(durations.get(state.durationIndex)));
      inventory.setItem(36, this.named(Material.ENDER_CHEST, this.config.getString("gui.back-name", "&7Zurück")));
      boolean ready = state.selectedItem != null;
      inventory.setItem(
         44,
         this.withLore(
            ready ? Material.GREEN_DYE : Material.RED_DYE,
            this.config.getString(ready ? "gui.create-ready-name" : "gui.create-not-ready-name", ready ? "&aBereit" : "&cNoch nicht bereit"),
            this.config.getStringList(ready ? "gui.create-ready-lore" : "gui.create-not-ready-lore")
         )
      );
      player.openInventory(inventory);
   }

   private ItemStack priceButton(Material material, String name, Double price) {
      List<String> lore = price == null
         ? this.config.getStringList("gui.price-unset-lore")
         : List.of(this.config.getString("gui.price-current-lore", "&7Aktuell: &e%price%").replace("%price%", this.formatPrice(price)));
      return this.withLore(material, name, lore);
   }

   private ItemStack durationButton(int minutes) {
      List<String> lore = new ArrayList<>(
         this.config.getStringList("gui.duration-lore").stream().map(line -> line.replace("%duration%", this.durationName(minutes))).toList()
      );
      lore.add(this.config.getString("gui.duration-options-header", "&7Mögliche Laufzeiten:"));

      for (int option : this.durationOptions()) {
         String path = option == minutes ? "gui.duration-option-selected" : "gui.duration-option";
         String fallback = option == minutes ? "&b» %duration%" : "&7• %duration%";
         lore.add(this.config.getString(path, fallback).replace("%duration%", this.durationName(option)));
      }

      return this.withLore(Material.CLOCK, this.config.getString("gui.duration-name", "&eDauer auswählen"), lore);
   }

   private ItemStack withLore(Material material, String name, List<String> lore) {
      ItemStack item = this.named(material, name);
      ItemMeta meta = item.getItemMeta();
      if (meta != null && !lore.isEmpty()) {
         meta.setLore(lore.stream().map(FeatureModule::color).toList());
         item.setItemMeta(meta);
      }

      return item;
   }

   private List<Integer> durationOptions() {
      List<Integer> configured = this.config.getIntegerList("creation.duration-options-minutes").stream().filter(minutes -> minutes > 0).distinct().toList();
      return configured.isEmpty() ? DEFAULT_DURATIONS_MINUTES : configured;
   }

   private String durationName(int minutes) {
      if (minutes < 60) {
         return minutes + (minutes == 1 ? " Minute" : " Minuten");
      } else if (minutes % 1440 == 0) {
         int days = minutes / 1440;
         return days + (days == 1 ? " Tag" : " Tage");
      } else if (minutes % 60 == 0) {
         int hours = minutes / 60;
         return hours + (hours == 1 ? " Stunde" : " Stunden");
      } else {
         return minutes + " Minuten";
      }
   }

   private String formatPrice(double price) {
      EconomyService economy = this.plugin.getEconomyService();
      return economy == null ? Double.toString(price) : economy.format(price);
   }

   private void openPriceInput(Player player, AktionshausFeature.PriceField field) {
      AktionshausFeature.CreationState state = this.creationStates.get(player.getUniqueId());
      if (state != null) {
         AktionshausFeature.PriceInputHolder holder = new AktionshausFeature.PriceInputHolder(player.getUniqueId(), field);
         String titlePath = field == AktionshausFeature.PriceField.INSTANT ? "gui.buy-now-title" : "gui.start-bid-title";
         String fallback = field == AktionshausFeature.PriceField.INSTANT ? "&aGreenAttack &8» &7Sofortkaufpreis" : "&aGreenAttack &8» &7Startgebot";
         Inventory inventory = Bukkit.createInventory(
            holder, InventoryType.ANVIL, color(this.plugin.applyPlaceholders(player, this.config.getString(titlePath, fallback)))
         );
         holder.inventory = inventory;
         Double current = field == AktionshausFeature.PriceField.INSTANT ? state.instantPrice : state.startingBid;
         String initialName = current == null ? this.config.getString("gui.price-placeholder", "Preis eingeben") : Double.toString(current);
         ItemStack paper = new ItemStack(Material.PAPER);
         ItemMeta meta = paper.getItemMeta();
         if (meta != null) {
            meta.setDisplayName(initialName);
            paper.setItemMeta(meta);
         }

         inventory.setItem(0, paper);
         player.openInventory(inventory);
         if (player.getOpenInventory() instanceof AnvilView view) {
            view.setRepairCost(0);
         }
      }
   }

   @EventHandler
   public void onCreateMenuClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof AktionshausFeature.CreateHolder holder) {
         if (event.getWhoClicked() instanceof Player player && holder.playerId.equals(player.getUniqueId())) {
            AktionshausFeature.CreationState state = this.creationStates.get(player.getUniqueId());
            if (state != null) {
               if (event.getClickedInventory() == event.getView().getTopInventory()) {
                  event.setCancelled(true);
                  switch (event.getRawSlot()) {
                     case 13:
                        if (state.selectedItem != null) {
                           this.returnSelectedItem(player, state);
                           this.openCreateMenu(player);
                        }
                        break;
                     case 29:
                        this.openPriceInput(player, AktionshausFeature.PriceField.INSTANT);
                        break;
                     case 31:
                        this.openPriceInput(player, AktionshausFeature.PriceField.START_BID);
                        break;
                     case 33:
                        List<Integer> durations = this.durationOptions();
                        if (event.isLeftClick()) {
                           state.durationIndex = (state.durationIndex + 1) % durations.size();
                        } else {
                           if (!event.isRightClick()) {
                              return;
                           }

                           state.durationIndex = Math.floorMod(state.durationIndex - 1, durations.size());
                        }

                        this.openCreateMenu(player);
                        break;
                     case 36:
                        this.returnSelectedItem(player, state);
                        this.creationStates.remove(player.getUniqueId());
                        this.openOverview(player);
                        break;
                     case 44:
                        this.createListingFromMenu(player, state);
                  }
               } else if (event.getClickedInventory() == player.getInventory()
                  && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)
                  && (event.getCursor() == null || event.getCursor().getType().isAir())) {
                  ItemStack source = event.getCurrentItem();
                  if (source != null && !source.getType().isAir()) {
                     event.setCancelled(true);
                     ItemStack replacement = source.clone();
                     event.getClickedInventory().setItem(event.getSlot(), null);
                     ItemStack previous = state.selectedItem;
                     state.selectedItem = replacement;
                     if (previous != null) {
                        this.giveItem(player, previous);
                     }

                     this.openCreateMenu(player);
                  }
               }
            }
         }
      }
   }

   @EventHandler
   public void onPriceInputClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof AktionshausFeature.PriceInputHolder holder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player
            && holder.playerId.equals(player.getUniqueId())
            && event.getClickedInventory() == event.getView().getTopInventory()
            && event.getRawSlot() == 2) {
            AktionshausFeature.CreationState state = this.creationStates.get(player.getUniqueId());
            if (state != null) {
               String query = ((AnvilView)event.getView()).getRenameText();
               String placeholder = this.config.getString("gui.price-placeholder", "Preis eingeben");
               if (query != null && !query.isBlank() && !query.equalsIgnoreCase(placeholder)) {
                  double price;
                  try {
                     price = Double.parseDouble(query.trim().replace(',', '.'));
                  } catch (NumberFormatException exception) {
                     this.sendPath(player, "messages.invalid-price", "&cBitte gib einen gültigen Preis ein.");
                     this.openCreateMenu(player);
                     return;
                  }

                  if (Double.isFinite(price) && !(price < 0.0) && !(price > this.config.getDouble("limits.max-price", 1.0E12))) {
                     this.setPrice(state, holder.field, price);
                     this.openCreateMenu(player);
                  } else {
                     this.sendPath(player, "messages.invalid-price", "&cBitte gib einen gültigen Preis ein.");
                     this.openCreateMenu(player);
                  }
               } else {
                  this.setPrice(state, holder.field, null);
                  this.openCreateMenu(player);
               }
            }
         }
      }
   }

   private void setPrice(AktionshausFeature.CreationState state, AktionshausFeature.PriceField field, Double price) {
      if (field == AktionshausFeature.PriceField.INSTANT) {
         state.instantPrice = price;
      } else {
         state.startingBid = price;
      }
   }

   private void returnSelectedItem(Player player, AktionshausFeature.CreationState state) {
      if (state.selectedItem != null) {
         this.giveItem(player, state.selectedItem);
         state.selectedItem = null;
      }
   }

   @EventHandler
   public void onCreationMenuClose(InventoryCloseEvent event) {
      Object holder = event.getInventory().getHolder();
      if (holder instanceof AktionshausFeature.CreateHolder || holder instanceof AktionshausFeature.PriceInputHolder) {
         if (event.getPlayer() instanceof Player player) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
               if (player.isOnline()) {
                  Object nextHolder = player.getOpenInventory().getTopInventory().getHolder();
                  if (holder instanceof AktionshausFeature.PriceInputHolder) {
                     if (!(nextHolder instanceof AktionshausFeature.CreateHolder)) {
                        this.openCreateMenu(player);
                     }
                  } else if (!(nextHolder instanceof AktionshausFeature.CreateHolder) && !(nextHolder instanceof AktionshausFeature.PriceInputHolder)) {
                     AktionshausFeature.CreationState state = this.creationStates.remove(player.getUniqueId());
                     if (state != null) {
                        this.returnSelectedItem(player, state);
                     }
                  }
               }
            });
         }
      }
   }

   @EventHandler
   public void onCreationPlayerQuit(PlayerQuitEvent event) {
      Player player = event.getPlayer();
      AktionshausFeature.CreationState state = this.creationStates.remove(player.getUniqueId());
      if (state != null && state.selectedItem != null) {
         this.config.set("storage.return-items." + player.getUniqueId() + ".draft", state.selectedItem);
         this.save();
      }
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      this.returnPendingItems(event.getPlayer(), true);
   }

   private int returnPendingItems(Player player, boolean notify) {
      String path = "storage.return-items." + player.getUniqueId();
      ConfigurationSection pending = this.config.getConfigurationSection(path);
      if (pending == null) {
         return 0;
      }

      int returned = 0;

      for (String key : pending.getKeys(false)) {
         ItemStack item = this.config.getItemStack(path + "." + key);
         if (item != null && !item.getType().isAir()) {
            this.giveItem(player, item);
            returned++;
         }
      }

      this.config.set(path, null);
      this.save();
      if (returned > 0 && notify) {
         this.send(player, this.config.getString("messages.items-returned", "&aDeine Items wurden dir zurückgegeben."));
      }

      return returned;
   }

   private void expireListings() {
      long now = System.currentTimeMillis();
      List<AktionshausFeature.Listing> expired = this.listings().stream().filter(listingx -> listingx.expiresAt() > 0L && listingx.expiresAt() <= now).toList();
      if (!expired.isEmpty()) {
         for (AktionshausFeature.Listing listing : expired) {
            this.config.set(this.listingPath(listing.id()), null);
            if (Bukkit.getPlayer(listing.sellerId()) == null) {
               this.config.set("storage.return-items." + listing.sellerId() + "." + listing.id(), listing.item());
            }
         }

         if (this.save()) {
            for (AktionshausFeature.Listing listing : expired) {
               Player seller = Bukkit.getPlayer(listing.sellerId());
               if (seller != null) {
                  this.giveItem(seller, listing.item());
                  this.send(
                     seller,
                     this.config
                        .getString("messages.listing-expired", "&eDein Aktionshaus-Angebot &f#%id% &eist abgelaufen und wurde zurückgegeben.")
                        .replace("%id%", listing.id())
                  );
               }
            }
         } else {
            for (AktionshausFeature.Listing listing : expired) {
               this.writeListingToConfig(listing);
            }

            for (AktionshausFeature.Listing listing : expired) {
               this.config.set("storage.return-items." + listing.sellerId() + "." + listing.id(), null);
            }
         }
      }
   }

   private ItemStack listingItem(Player viewer, AktionshausFeature.Listing listing, boolean ownOnly) {
      ItemStack item = listing.item().clone();
      ItemMeta meta = item.getItemMeta();
      if (meta == null) {
         return item;
      }

      EconomyService economy = this.plugin.getEconomyService();
      String price = economy == null ? Double.toString(listing.price()) : economy.format(listing.price());
      String startPrice = economy == null ? Double.toString(listing.startingBid()) : economy.format(listing.startingBid());
      List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
      lore.add("");
      String lorePath = ownOnly ? "gui.my-lore" : (listing.instantPrice() == null ? "gui.bid-listing-lore" : "gui.listing-lore");
      List<String> configured = this.config.getStringList(lorePath);
      if (configured.isEmpty()) {
         configured = ownOnly
            ? List.of("&7Angebot: &e#%id%", "&7Preis: &6%price%", "", "&cKlicken zum Zurücknehmen")
            : (
               listing.instantPrice() == null
                  ? List.of("&7Verkäufer: &e%seller%", "&7Startgebot: &6%starting_bid%", "&7Angebot: &e#%id%", "", "&cGebote sind noch nicht einsatzbereit")
                  : List.of("&7Verkäufer: &e%seller%", "&7Preis: &6%price%", "&7Angebot: &e#%id%", "", "&aKlicken zum Kaufen")
            );
      }

      for (String line : configured) {
         lore.add(
            color(
               this.plugin
                  .applyPlaceholders(
                     viewer,
                     line.replace("%seller%", listing.sellerName())
                        .replace("%price%", price)
                        .replace("%buy_now%", listing.instantPrice() == null ? "-" : price)
                        .replace("%starting_bid%", startPrice)
                        .replace("%duration%", listing.durationMinutes() > 0 ? this.durationName(listing.durationMinutes()) : "-")
                        .replace("%id%", listing.id())
                  )
            )
         );
      }

      meta.setLore(lore);
      item.setItemMeta(meta);
      return item;
   }

   @EventHandler
   public void onListingsClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof AktionshausFeature.ListingsHolder holder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player) {
            if (event.getClickedInventory() == event.getView().getTopInventory()) {
               int slot = event.getRawSlot();
               if (slot == 0) {
                  this.searchTerms.remove(player.getUniqueId());
                  this.openListings(player, 0, false, AktionshausFeature.ListingCategory.CUSTOM_ITEMS);
               } else if (slot == 49) {
                  this.requestSearch(player);
               } else {
                  AktionshausFeature.ListingCategory selectedCategory = switch (slot) {
                     case 9 -> AktionshausFeature.ListingCategory.WEAPONS;
                     case 18 -> AktionshausFeature.ListingCategory.ARMOR;
                     case 27 -> AktionshausFeature.ListingCategory.OP_ITEMS;
                     case 36 -> AktionshausFeature.ListingCategory.BOOKS;
                     case 45 -> AktionshausFeature.ListingCategory.OTHER;
                     default -> null;
                  };
                  if (selectedCategory != null) {
                     this.searchTerms.remove(player.getUniqueId());
                     this.openListings(player, 0, false, selectedCategory);
                  } else if (slot == 48 && holder.page > 0) {
                     this.openListings(player, holder.page - 1, holder.ownOnly, holder.category);
                  } else if (slot == 50) {
                     this.openSortMenu(player, holder.ownOnly, holder.category);
                  } else if (slot == 52) {
                     this.openListings(player, holder.page + 1, holder.ownOnly, holder.category);
                  } else if (slot == 53) {
                     this.openOverview(player);
                  } else {
                     int row = slot / 9;
                     int column = slot % 9;
                     if (row >= 1 && row <= 4 && column >= 2 && column <= 7) {
                        List<AktionshausFeature.Listing> pageListings = this.visibleListings(player, holder.ownOnly, holder.category);
                        int index = holder.page * 24 + (row - 1) * 6 + column - 2;
                        if (index < pageListings.size()) {
                           AktionshausFeature.Listing listing = pageListings.get(index);
                           if (holder.ownOnly) {
                              this.cancelListing(player, listing.id());
                           } else {
                              this.purchase(player, listing);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   @EventHandler
   public void onAdminMenuClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof AktionshausFeature.AdminHolder holder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player admin && event.getClickedInventory() == event.getView().getTopInventory()) {
            int slot = event.getRawSlot();
            if (slot == 48 && holder.page > 0) {
               this.openAdminMenu(admin, holder.page - 1);
            } else if (slot == 49) {
               this.openListings(admin, 0, false);
            } else if (slot == 52) {
               this.openAdminMenu(admin, holder.page + 1);
            } else {
               int row = slot / 9;
               int column = slot % 9;
               if (row >= 1 && row <= 4 && column >= 2 && column <= 7) {
                  List<AktionshausFeature.Listing> all = this.listings()
                     .stream()
                     .sorted(Comparator.comparingLong(AktionshausFeature.Listing::createdAt).reversed())
                     .toList();
                  int index = holder.page * 24 + (row - 1) * 6 + column - 2;
                  if (index < all.size()) {
                     this.returnListingToSeller(admin, all.get(index), holder.page);
                  }
               }
            }
         }
      }
   }

   private void returnListingToSeller(Player admin, AktionshausFeature.Listing listing, int currentPage) {
      String returnPath = "storage.return-items." + listing.sellerId() + "." + listing.id();
      this.config.set(this.listingPath(listing.id()), null);
      this.config.set(returnPath, listing.item());
      if (!this.save()) {
         this.config.set(returnPath, null);
         this.writeListingToConfig(listing);
         this.save();
         this.sendPath(admin, "messages.transaction-failed", "&cDas Angebot konnte nicht zurückgegeben werden.");
         this.openAdminMenu(admin, currentPage);
      } else {
         Player seller = Bukkit.getPlayer(listing.sellerId());
         if (seller != null) {
            this.returnPendingItems(seller, false);
            this.send(
               seller,
               this.config
                  .getString("messages.admin-returned-to-player", "&eDein Aktionshaus-Angebot &f#%id% &ewurde von einem Admin zurückgegeben.")
                  .replace("%id%", listing.id())
            );
         }

         this.send(
            admin,
            this.config
               .getString("messages.admin-returned", "&aAngebot &e#%id% &awurde an &e%seller% &azurückgesendet.")
               .replace("%id%", listing.id())
               .replace("%seller%", listing.sellerName())
         );
         this.openAdminMenu(admin, currentPage);
      }
   }

   @EventHandler
   public void onOverviewClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof AktionshausFeature.OverviewHolder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            switch (event.getRawSlot()) {
               case 11:
                  this.requestCreate(player);
                  break;
               case 13:
                  this.openListings(player, 0, true);
                  break;
               case 15:
                  this.sendPath(player, "messages.bids-unavailable", "&7Deine Gebote &8[Noch nicht einsatzbereit]");
                  break;
               case 27:
                  this.openListings(player, 0, false);
            }
         }
      }
   }

   @EventHandler
   public void onSortClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof AktionshausFeature.SortHolder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            AktionshausFeature.SortMode selected = switch (event.getRawSlot()) {
               case 12 -> AktionshausFeature.SortMode.HIGHEST_PRICE;
               case 13 -> AktionshausFeature.SortMode.LOWEST_PRICE;
               default -> null;
            };
            AktionshausFeature.SortHolder holder = (AktionshausFeature.SortHolder)event.getView().getTopInventory().getHolder();
            if (event.getRawSlot() == 22) {
               this.openListings(player, 0, holder.ownOnly, holder.category);
            } else if (selected != null) {
               this.sortModes.put(player.getUniqueId(), selected);
               this.openListings(player, 0, holder.ownOnly, holder.category);
            } else if (List.of(10, 11, 14, 15).contains(event.getRawSlot())) {
               this.sendPath(player, "messages.not-ready", "&7[Noch nicht einsatzbereit]");
            }
         }
      }
   }

   private void purchase(Player buyer, AktionshausFeature.Listing listing) {
      if (listing.expiresAt() > 0L && listing.expiresAt() <= System.currentTimeMillis()) {
         this.expireListings();
         this.sendPath(buyer, "messages.listing-already-expired", "&cDieses Angebot ist bereits abgelaufen.");
         this.openListings(buyer, 0, false);
      } else if (listing.sellerId().equals(buyer.getUniqueId())) {
         this.sendPath(buyer, "messages.buy-own", "&cDu kannst dein eigenes Angebot nicht kaufen.");
      } else if (listing.instantPrice() == null) {
         this.sendPath(buyer, "messages.bids-unavailable", "&7Gebote sind noch nicht einsatzbereit.");
      } else {
         EconomyService economy = this.plugin.getEconomyService();
         if (economy == null) {
            this.sendPath(buyer, "messages.economy-unavailable", "&cDie Economy ist nicht verfügbar.");
         } else if (!economy.has(buyer, listing.instantPrice())) {
            this.sendPath(buyer, "messages.not-enough-money", "&cDu hast nicht genug Geld.");
         } else {
            String path = this.listingPath(listing.id());
            this.config.set(path, null);
            if (!this.save()) {
               this.sendPath(buyer, "messages.transaction-failed", "&cDer Kauf konnte nicht gespeichert werden.");
            } else if (!economy.withdraw(buyer, listing.instantPrice())) {
               this.restore(listing);
               this.sendPath(buyer, "messages.transaction-failed", "&cDie Zahlung ist fehlgeschlagen.");
            } else {
               OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.sellerId());
               if (!economy.deposit(seller, listing.instantPrice())) {
                  economy.deposit(buyer, listing.instantPrice());
                  this.restore(listing);
                  this.sendPath(buyer, "messages.transaction-failed", "&cDie Zahlung an den Verkäufer ist fehlgeschlagen.");
               } else {
                  this.giveItem(buyer, listing.item());
                  this.send(
                     buyer,
                     this.config
                        .getString("messages.purchased", "&aDu hast Angebot &e#%id% &afür &e%price% &agekauft.")
                        .replace("%id%", listing.id())
                        .replace("%price%", economy.format(listing.instantPrice()))
                  );
                  Player onlineSeller = Bukkit.getPlayer(listing.sellerId());
                  if (onlineSeller != null) {
                     this.send(
                        onlineSeller,
                        this.config
                           .getString("messages.sold", "&aDein Angebot &e#%id% &awurde für &e%price% &averkauft.")
                           .replace("%id%", listing.id())
                           .replace("%price%", economy.format(listing.instantPrice()))
                     );
                  }

                  this.openListings(buyer, 0, false);
               }
            }
         }
      }
   }

   private void cancelListing(Player seller, String id) {
      AktionshausFeature.Listing listing = this.listing(id);
      if (listing == null) {
         this.send(seller, this.config.getString("messages.not-found", "&cAngebot &e#%id% &cwurde nicht gefunden.").replace("%id%", id));
      } else if (!listing.sellerId().equals(seller.getUniqueId())
         && !this.hasFeaturePermission(seller, this.config.getString("permissions.admin", "system.aktionshaus.admin"))) {
         this.sendPath(seller, "messages.not-owner", "&cDieses Angebot gehört dir nicht.");
      } else if (!this.removeListing(listing)) {
         this.sendPath(seller, "messages.transaction-failed", "&cDas Angebot konnte nicht zurückgenommen werden.");
      } else {
         this.giveItem(seller, listing.item());
         this.send(seller, this.config.getString("messages.cancelled", "&aAngebot &e#%id% &awurde zurückgenommen.").replace("%id%", id));
         if (seller.getOpenInventory().getTopInventory().getHolder() instanceof AktionshausFeature.ListingsHolder holder && holder.ownOnly) {
            this.openListings(seller, holder.page, true);
         }
      }
   }

   private boolean hasPermission(CommandSender sender, String path, String fallback) {
      if (this.hasFeaturePermission(sender, this.config.getString(path, fallback))) {
         return true;
      }

      this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Berechtigung.");
      return false;
   }

   private List<AktionshausFeature.Listing> listings() {
      ConfigurationSection section = this.config.getConfigurationSection("storage.listings");
      if (section == null) {
         return new ArrayList<>();
      }

      List<AktionshausFeature.Listing> result = new ArrayList<>();

      for (String id : section.getKeys(false)) {
         AktionshausFeature.Listing listing = this.listing(id);
         if (listing != null) {
            result.add(listing);
         }
      }

      result.sort(Comparator.comparingLong(AktionshausFeature.Listing::createdAt));
      return result;
   }

   private AktionshausFeature.Listing listing(String id) {
      String path = this.listingPath(id);
      String sellerText = this.config.getString(path + ".seller");
      ItemStack item = this.config.getItemStack(path + ".item");
      if (sellerText != null && item != null && !item.getType().isAir()) {
         try {
            UUID seller = UUID.fromString(sellerText);
            double price = this.config.getDouble(path + ".price");
            boolean usesAuctionFields = this.config.contains(path + ".instant-price")
               || this.config.contains(path + ".starting-bid")
               || this.config.contains(path + ".duration-minutes");
            Double instantPrice = this.config.contains(path + ".instant-price")
               ? this.config.getDouble(path + ".instant-price")
               : usesAuctionFields ? null : price;
            double startingBid = this.config.getDouble(path + ".starting-bid", 0.0);
            long createdAt = this.config.getLong(path + ".created-at");
            return new AktionshausFeature.Listing(
               id,
               seller,
               this.config.getString(path + ".seller-name", "Unbekannt"),
               price,
               instantPrice,
               startingBid,
               this.config.getInt(path + ".duration-minutes", 0),
               createdAt,
               this.config.getLong(path + ".expires-at", 0L),
               item.clone()
            );
         } catch (IllegalArgumentException exception) {
            return null;
         }
      } else {
         return null;
      }
   }

   private boolean removeListing(AktionshausFeature.Listing listing) {
      String path = this.listingPath(listing.id());
      this.config.set(path, null);
      if (this.save()) {
         return true;
      }

      this.restore(listing);
      return false;
   }

   private void restore(AktionshausFeature.Listing listing) {
      this.writeListingToConfig(listing);
      this.save();
   }

   private void writeListingToConfig(AktionshausFeature.Listing listing) {
      String path = this.listingPath(listing.id());
      this.config.set(path + ".seller", listing.sellerId().toString());
      this.config.set(path + ".seller-name", listing.sellerName());
      this.config.set(path + ".price", listing.price());
      this.config.set(path + ".instant-price", listing.instantPrice());
      this.config.set(path + ".starting-bid", listing.startingBid());
      this.config.set(path + ".duration-minutes", listing.durationMinutes());
      this.config.set(path + ".expires-at", listing.expiresAt());
      this.config.set(path + ".created-at", listing.createdAt());
      this.config.set(path + ".item", listing.item());
   }

   private String listingPath(String id) {
      return "storage.listings." + id;
   }

   private void giveItem(Player player, ItemStack item) {
      HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack[]{item.clone()});
      overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
   }

   private ItemStack named(Material material, String name) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(color(name));
         item.setItemMeta(meta);
      }

      return item;
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (this.isEnabled()
         && (
            this.hasFeaturePermission(sender, this.config.getString("permissions.use", "system.aktionshaus.use"))
               || this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.aktionshaus.admin"))
         )) {
         String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
         if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("suche", "verkaufen", "meine", "abbrechen"));
            if (this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.aktionshaus.admin"))) {
               options.add("admin");
               options.add("reload");
            }

            return options.stream().filter(option -> option.startsWith(partial)).toList();
         } else {
            return args.length == 2 && (args[0].equalsIgnoreCase("abbrechen") || args[0].equalsIgnoreCase("cancel")) && sender instanceof Player player
               ? this.listings()
                  .stream()
                  .filter(
                     listing -> listing.sellerId().equals(player.getUniqueId())
                        || this.hasFeaturePermission(player, this.config.getString("permissions.admin", "system.aktionshaus.admin"))
                  )
                  .map(AktionshausFeature.Listing::id)
                  .filter(id -> id.startsWith(partial))
                  .toList()
               : List.of();
         }
      } else {
         return List.of();
      }
   }

   private static final class AdminHolder implements InventoryHolder {
      private final int page;
      private Inventory inventory;

      private AdminHolder(int page) {
         this.page = page;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private static final class CreateHolder implements InventoryHolder {
      private final UUID playerId;
      private Inventory inventory;

      private CreateHolder(UUID playerId) {
         this.playerId = playerId;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private static final class CreationState {
      private int durationIndex;
      private ItemStack selectedItem;
      private Double instantPrice;
      private Double startingBid;

      private CreationState(int durationIndex) {
         this.durationIndex = durationIndex;
      }
   }

   private record Listing(
      String id,
      UUID sellerId,
      String sellerName,
      double price,
      Double instantPrice,
      double startingBid,
      int durationMinutes,
      long createdAt,
      long expiresAt,
      ItemStack item
   ) {
   }

   private enum ListingCategory {
      ALL,
      CUSTOM_ITEMS,
      WEAPONS,
      ARMOR,
      OP_ITEMS,
      BOOKS,
      OTHER;
   }

   private static final class ListingsHolder implements InventoryHolder {
      private final int page;
      private final boolean ownOnly;
      private final AktionshausFeature.ListingCategory category;
      private Inventory inventory;

      private ListingsHolder(int page, boolean ownOnly, AktionshausFeature.ListingCategory category) {
         this.page = page;
         this.ownOnly = ownOnly;
         this.category = category;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private static final class OverviewHolder implements InventoryHolder {
      private Inventory inventory;

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private enum PriceField {
      INSTANT,
      START_BID;
   }

   private static final class PriceInputHolder implements InventoryHolder {
      private final UUID playerId;
      private final AktionshausFeature.PriceField field;
      private Inventory inventory;

      private PriceInputHolder(UUID playerId, AktionshausFeature.PriceField field) {
         this.playerId = playerId;
         this.field = field;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private static final class SearchHolder implements InventoryHolder {
      private Inventory inventory;

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private static final class SortHolder implements InventoryHolder {
      private final boolean ownOnly;
      private final AktionshausFeature.ListingCategory category;
      private Inventory inventory;

      private SortHolder(boolean ownOnly, AktionshausFeature.ListingCategory category) {
         this.ownOnly = ownOnly;
         this.category = category;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private enum SortMode {
      NEWEST("Neueste zuerst"),
      OLDEST("Älteste zuerst"),
      LOWEST_PRICE("Niedrigster Preis"),
      HIGHEST_PRICE("Höchster Preis");

      private final String displayName;

      SortMode(String displayName) {
         this.displayName = displayName;
      }
   }
}

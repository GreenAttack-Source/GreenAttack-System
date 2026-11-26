package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import de.greenattack.system.economy.EconomyService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

public final class MarketFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final int MENU_SIZE = 54;
   private static final int PAGE_SIZE = 35;
   private static final int SEARCH_SLOT = 47;
   private static final int BALANCE_SLOT = 49;
   private static final int PREVIOUS_SLOT = 51;
   private static final int NEXT_SLOT = 52;
   private static final int SELL_SLOT = 8;
   private final File stockFile;
   private final SellFeature sellFeature;
   private YamlConfiguration stock;

   public MarketFeature(GreenAttackSystemPlugin plugin, SellFeature sellFeature) {
      super(plugin, "market");
      this.sellFeature = sellFeature;
      this.migrateCategoryLayout();
      this.stockFile = this.featureFile("stock.yml");
      this.loadStock();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDer Markt ist derzeit geschlossen.");
         return true;
      }

      if (args.length > 0) {
         if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!this.isAdmin(sender)) {
               return true;
            }

            this.reloadFeature();
            this.sendPath(sender, "messages.reloaded", "&aDer Markt wurde neu geladen.");
            return true;
         } else if ((args.length == 1 || args.length == 2) && args[0].equalsIgnoreCase("reset")) {
            if (!this.isAdmin(sender)) {
               return true;
            }

            this.resetStock(sender, args.length == 2 ? args[1] : null);
            return true;
         } else {
            this.sendPath(sender, "messages.usage", "&7Verwendung: &f/markt &7oder &f/markt <reload|reset [Kategorie]>");
            return true;
         }
      } else if (sender instanceof Player player) {
         if (!this.hasUsePermission(player)) {
            return true;
         } else if (this.plugin.getEconomyService() == null) {
            this.sendPath(player, "messages.economy-unavailable", "&cDer Markt ist momentan nicht verfügbar.");
            return true;
         } else {
            this.openMainMenu(player);
            return true;
         }
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.isAdmin(sender)) {
         return List.of();
      } else if (args.length == 1) {
         return List.of("reload", "reset").stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
      } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
         String partial = args[1].toLowerCase(Locale.ROOT);
         return this.categories().stream().map(MarketFeature.Category::id).filter(id -> id.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
      } else {
         return List.of();
      }
   }

   public void reloadFeature() {
      this.reload();
      this.loadStock();
   }

   private boolean isAdmin(CommandSender sender) {
      if (this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.market.admin"))) {
         return true;
      }

      this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
      return false;
   }

   private boolean hasUsePermission(Player player) {
      if (this.hasFeaturePermission(player, this.config.getString("permissions.use", "system.market.use"))) {
         return true;
      }

      this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
      return false;
   }

   private List<MarketFeature.Category> categories() {
      ConfigurationSection section = this.config.getConfigurationSection("categories");
      if (section == null) {
         return List.of();
      }

      List<MarketFeature.Category> result = new ArrayList<>();

      for (String id : section.getKeys(false)) {
         ConfigurationSection category = section.getConfigurationSection(id);
         if (category != null && category.getBoolean("enabled", true)) {
            Material icon = Material.matchMaterial(category.getString("icon", "CHEST"));
            if (icon == null || !icon.isItem()) {
               icon = Material.CHEST;
            }

            ConfigurationSection itemSection = category.getConfigurationSection("items");
            List<MarketFeature.Offer> offers = new ArrayList<>();
            if (itemSection != null) {
               for (String materialName : itemSection.getKeys(false)) {
                  Material material = Material.matchMaterial(materialName);
                  ConfigurationSection itemConfig = itemSection.getConfigurationSection(materialName);
                  if (material != null && material.isItem() && !material.isAir() && itemConfig != null) {
                     double basePrice = itemConfig.getDouble("base-price", -1.0);
                     if (Double.isFinite(basePrice) && !(basePrice <= 0.0)) {
                        long initialStock = Math.max(0L, itemConfig.getLong("initial-stock", 1000L));
                        double sellRatio = itemConfig.getDouble("sell-ratio", this.config.getDouble("pricing.sell-ratio", 0.55));
                        sellRatio = Math.max(0.0, Math.min(1.0, sellRatio));
                        String displayName = itemConfig.getString("name", "&e" + titleCase(material.name()));
                        offers.add(new MarketFeature.Offer(id, material, basePrice, initialStock, sellRatio, displayName));
                     }
                  }
               }
            }

            if (!offers.isEmpty()) {
               result.add(
                  new MarketFeature.Category(id, category.getString("name", "&a" + titleCase(id)), icon, category.getInt("order", result.size()), offers)
               );
            }
         }
      }

      result.sort(Comparator.comparingInt(MarketFeature.Category::order));
      return result;
   }

   private void openMainMenu(Player player) {
      List<MarketFeature.Category> available = this.categories();
      String preferred = this.config.getString("gui.default-category", "mining");
      String categoryId = available.stream()
         .filter(category -> category.id().equalsIgnoreCase(preferred))
         .map(MarketFeature.Category::id)
         .findFirst()
         .orElse(available.isEmpty() ? null : available.getFirst().id());
      if (categoryId == null) {
         this.sendPath(player, "messages.no-categories", "&cDer Markt hat derzeit keine Kategorien.");
      } else {
         this.openCategory(player, categoryId, 0);
      }
   }

   private Map<Integer, MarketFeature.Category> categorySlots(List<MarketFeature.Category> categories) {
      Map<Integer, MarketFeature.Category> slots = new LinkedHashMap<>();

      for (int index = 0; index < Math.min(6, categories.size()); index++) {
         slots.put(index * 9, categories.get(index));
      }

      return slots;
   }

   private ItemStack categoryIcon(MarketFeature.Category category, String selectedCategory) {
      List<String> lore = new ArrayList<>();
      lore.add("&7Angebote: &e" + category.offers().size());
      lore.add("");
      lore.add(
         category.id().equalsIgnoreCase(selectedCategory)
            ? this.config.getString("gui.category-selected", "&aAusgewählt")
            : this.config.getString("gui.category-click", "&eKlicke, um die Kategorie zu öffnen.")
      );
      return this.named(category.icon(), category.name(), lore);
   }

   private void openCategory(Player player, String categoryId, int page) {
      this.openCategory(player, categoryId, page, "");
   }

   private void openCategory(Player player, String categoryId, int page, String searchQuery) {
      MarketFeature.Category category = this.categories().stream().filter(value -> value.id().equalsIgnoreCase(categoryId)).findFirst().orElse(null);
      if (category == null) {
         this.openMainMenu(player);
      } else {
         List<MarketFeature.Offer> visibleOffers = this.offersFor(category, searchQuery);
         int pages = Math.max(1, (visibleOffers.size() + 35 - 1) / 35);
         int safePage = Math.max(0, Math.min(page, pages - 1));
         MarketFeature.MarketHolder holder = new MarketFeature.MarketHolder(category.id(), safePage, searchQuery);
         String title = searchQuery.isBlank()
            ? this.config.getString("gui.category-title", "&8Markt &8» &7%category% &8(%page%/%pages%)")
            : this.config.getString("gui.search-results-title", "&8Markt &8» &7Suche: %query% &8(%page%/%pages%)");
         title = title.replace("%category%", category.name())
            .replace("%query%", searchQuery)
            .replace("%page%", Integer.toString(safePage + 1))
            .replace("%pages%", Integer.toString(pages));
         Inventory inventory = Bukkit.createInventory(holder, 54, color(title));
         holder.inventory = inventory;
         this.fill(inventory);

         for (Entry<Integer, MarketFeature.Category> entry : this.categorySlots(this.categories()).entrySet()) {
            inventory.setItem(entry.getKey(), this.categoryIcon(entry.getValue(), searchQuery.isBlank() ? category.id() : ""));
         }

         int start = safePage * 35;

         for (int index = 0; index < 35 && start + index < visibleOffers.size(); index++) {
            inventory.setItem(this.productSlot(index), this.offerIcon(visibleOffers.get(start + index)));
         }

         inventory.setItem(
            8,
            this.named(
               Material.BELL,
               this.config.getString("gui.sell-name", "&eZum Verkauf"),
               List.of(this.config.getString("gui.sell-lore", "&7Öffnet das Verkaufsmenü."))
            )
         );
         inventory.setItem(
            47,
            this.named(
               Material.OAK_SIGN,
               this.config.getString("gui.search-name", "&eItems suchen"),
               List.of(this.config.getString("gui.search-lore", "&7Klicke, um den Suchtext einzugeben."))
            )
         );
         inventory.setItem(49, this.balanceIcon(player));
         if (safePage > 0) {
            inventory.setItem(51, this.named(Material.ARROW, this.config.getString("gui.previous-name", "&e← Vorherige Seite"), List.of()));
         }

         if (safePage + 1 < pages) {
            inventory.setItem(52, this.named(Material.ARROW, this.config.getString("gui.next-name", "&eNächste Seite →"), List.of()));
         }

         player.openInventory(inventory);
      }
   }

   private int productSlot(int index) {
      return index / 7 * 9 + 1 + index % 7;
   }

   private List<MarketFeature.Offer> offersFor(MarketFeature.Category category, String searchQuery) {
      return searchQuery != null && !searchQuery.isBlank() ? this.allSearchOffers(searchQuery) : category.offers();
   }

   private List<MarketFeature.Offer> allSearchOffers(String searchQuery) {
      if (searchQuery != null && !searchQuery.isBlank()) {
         String query = searchQuery.toLowerCase(Locale.ROOT);
         return this.categories()
            .stream()
            .flatMap(category -> category.offers().stream())
            .filter(
               offer -> offer.material().name().toLowerCase(Locale.ROOT).contains(query)
                  || plainName(offer.material()).toLowerCase(Locale.ROOT).contains(query)
                  || stripColors(offer.displayName()).toLowerCase(Locale.ROOT).contains(query)
                  || this.valueName(offer.categoryId()).toLowerCase(Locale.ROOT).contains(query)
            )
            .toList();
      } else {
         return List.of();
      }
   }

   private String valueName(String categoryId) {
      return this.categories()
         .stream()
         .filter(category -> category.id().equalsIgnoreCase(categoryId))
         .map(category -> stripColors(category.name()))
         .findFirst()
         .orElse(categoryId);
   }

   private static String stripColors(String value) {
      return value == null ? "" : value.replaceAll("(?i)&[0-9A-FK-ORX]", "").replaceAll("(?i)§[0-9A-FK-ORX]", "");
   }

   private ItemStack offerIcon(MarketFeature.Offer offer) {
      long currentStock = this.getStock(offer);
      double buyPrice = this.unitBuyPrice(offer, currentStock);
      double sellPrice = this.roundMoney(buyPrice * offer.sellRatio());
      List<String> lore = this.config.getStringList("gui.offer-lore");
      if (lore.isEmpty()) {
         lore = List.of(
            "&7Kaufpreis: &a%buy% &7pro Stück",
            "&7Verkaufspreis: &c%sell% &7pro Stück",
            "&7Marktbestand: &e%stock%",
            "",
            "&aLinksklick: 1 kaufen",
            "&aShift + Linksklick: Stack kaufen",
            "&cRechtsklick: 1 verkaufen",
            "&cShift + Rechtsklick: alle verkaufen"
         );
      }

      List<String> formattedLore = lore.stream()
         .map(
            line -> color(
               line.replace("%buy%", this.formatMoney(buyPrice))
                  .replace("%sell%", this.formatMoney(sellPrice))
                  .replace("%stock%", Long.toString(currentStock))
                  .replace("%item%", plainName(offer.material()))
            )
         )
         .toList();
      return this.named(offer.material(), offer.displayName(), formattedLore);
   }

   private ItemStack balanceIcon(Player player) {
      EconomyService economy = this.plugin.getEconomyService();
      double balance = economy == null ? 0.0 : economy.balance(player);
      return this.named(
         Material.EMERALD,
         this.config.getString("gui.balance-name", "&aDein Kontostand"),
         List.of(this.config.getString("gui.balance-lore", "&7Guthaben: &e%balance%").replace("%balance%", this.formatMoney(balance)))
      );
   }

   private void fill(Inventory inventory) {
      ItemStack filler = this.named(Material.GRAY_STAINED_GLASS_PANE, "&7 ", List.of());

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         inventory.setItem(slot, filler.clone());
      }
   }

   private ItemStack named(Material material, String name, List<String> lore) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(color(name));
         meta.setLore(lore.stream().map(FeatureModule::color).toList());
         item.setItemMeta(meta);
      }

      return item;
   }

   private void openSearchMenu(Player player) {
      MarketFeature.SearchHolder holder = new MarketFeature.SearchHolder();
      Inventory inventory = Bukkit.createInventory(
         holder, InventoryType.ANVIL, color(this.config.getString("gui.search-title", "&2GreenAttack-Markt &8» &7Suche"))
      );
      holder.inventory = inventory;
      inventory.setItem(0, this.named(Material.PAPER, this.config.getString("gui.search-placeholder", "&7Suchbegriff eingeben"), List.of()));
      player.openInventory(inventory);
      if (player.getOpenInventory() instanceof AnvilView view) {
         view.setRepairCost(0);
      }
   }

   @EventHandler
   public void onSearchAnvilPrepare(PrepareAnvilEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof MarketFeature.SearchHolder) {
         AnvilView view = event.getView();
         String query = view.getRenameText();
         if (query != null && !query.isBlank()) {
            ItemStack result = new ItemStack(Material.PAPER);
            ItemMeta meta = result.getItemMeta();
            if (meta != null) {
               meta.setDisplayName(color(this.config.getString("gui.search-result-name", "&aSuche: &f%query%").replace("%query%", query)));
               result.setItemMeta(meta);
            }

            event.setResult(result);
            view.setRepairCost(0);
         } else {
            event.setResult(null);
         }
      }
   }

   @EventHandler
   public void onMarketClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof MarketFeature.SearchHolder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory() && event.getRawSlot() == 2) {
            AnvilView view = (AnvilView)event.getView();
            String query = view.getRenameText() == null ? "" : view.getRenameText().trim();
            String placeholder = stripColors(this.config.getString("gui.search-placeholder", "Suchbegriff eingeben"));
            if (!query.isBlank() && !query.equalsIgnoreCase(placeholder)) {
               this.openSearchResults(player, query, 0);
            } else {
               this.openMainMenu(player);
            }
         }
      } else if (event.getView().getTopInventory().getHolder() instanceof MarketFeature.MarketHolder holder) {
         event.setCancelled(true);
         if (!this.isEnabled()) {
            if (event.getWhoClicked() instanceof Player player) {
               this.sendPath(player, "messages.disabled", "&cDer Markt ist derzeit geschlossen.");
               player.closeInventory();
            }
         } else if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            if (!this.hasUsePermission(player)) {
               player.closeInventory();
            } else {
               int slot = event.getRawSlot();
               if (event.getClickedInventory() == event.getView().getTopInventory()) {
                  if (slot == 8) {
                     this.sellFeature.openFromMarket(player);
                  } else if (slot == 47) {
                     this.openSearchMenu(player);
                  } else if (slot == 51 && holder.page > 0) {
                     if (holder.searchQuery.isBlank()) {
                        this.openCategory(player, holder.categoryId, holder.page - 1);
                     } else {
                        this.openSearchResults(player, holder.searchQuery, holder.page - 1);
                     }
                  } else if (slot == 52) {
                     if (holder.searchQuery.isBlank()) {
                        this.openCategory(player, holder.categoryId, holder.page + 1);
                     } else {
                        this.openSearchResults(player, holder.searchQuery, holder.page + 1);
                     }
                  } else {
                     MarketFeature.Category sidebarCategory = this.categorySlots(this.categories()).get(slot);
                     if (sidebarCategory != null) {
                        this.openCategory(player, sidebarCategory.id(), 0);
                     } else if (this.isProductSlot(slot)) {
                        MarketFeature.Category category = this.categories()
                           .stream()
                           .filter(value -> value.id().equalsIgnoreCase(holder.categoryId))
                           .findFirst()
                           .orElse(null);
                        if (category == null) {
                           this.openMainMenu(player);
                        } else {
                           List<MarketFeature.Offer> visibleOffers = this.offersFor(category, holder.searchQuery);
                           int offerIndex = holder.page * 35 + this.productIndex(slot);
                           if (offerIndex < visibleOffers.size()) {
                              MarketFeature.Offer offer = visibleOffers.get(offerIndex);
                              int requested = event.isShiftClick() ? (event.isLeftClick() ? offer.material().getMaxStackSize() : Integer.MAX_VALUE) : 1;
                              if (event.isLeftClick()) {
                                 this.buy(player, offer, requested);
                              } else if (event.isRightClick()) {
                                 this.sell(player, offer, requested);
                              }

                              if (holder.searchQuery.isBlank()) {
                                 this.openCategory(player, holder.categoryId, holder.page);
                              } else {
                                 this.openSearchResults(player, holder.searchQuery, holder.page);
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void openSearchResults(Player player, String query, int page) {
      List<MarketFeature.Category> available = this.categories();
      if (!available.isEmpty()) {
         String selected = available.stream()
            .map(MarketFeature.Category::id)
            .filter(id -> id.equalsIgnoreCase(this.config.getString("gui.default-category", "mining")))
            .findFirst()
            .orElse(available.getFirst().id());
         this.openCategory(player, selected, page, query);
      }
   }

   private boolean isProductSlot(int slot) {
      int row = slot / 9;
      int column = slot % 9;
      return row < 5 && column >= 1 && column <= 7;
   }

   private int productIndex(int slot) {
      return slot / 9 * 7 + slot % 9 - 1;
   }

   @EventHandler
   public void onMarketDrag(InventoryDragEvent event) {
      Object holder = event.getView().getTopInventory().getHolder();
      if (holder instanceof MarketFeature.MarketHolder || holder instanceof MarketFeature.SearchHolder) {
         event.setCancelled(true);
      }
   }

   private void buy(Player player, MarketFeature.Offer offer, int requested) {
      EconomyService economy = this.plugin.getEconomyService();
      if (economy == null) {
         this.sendPath(player, "messages.economy-unavailable", "&cDer Markt ist momentan nicht verfügbar.");
      } else {
         long currentStock = this.getStock(offer);
         if (currentStock <= 0L) {
            this.send(player, this.text("messages.out-of-stock", "&c%item% ist momentan ausverkauft.").replace("%item%", plainName(offer.material())));
         } else {
            int capacity = this.inventoryCapacity(player.getInventory(), offer.material());
            int amount = (int)Math.min(Math.min(requested, currentStock), capacity);
            if (amount <= 0) {
               this.sendPath(player, "messages.inventory-full", "&cIn deinem Inventar ist kein Platz für dieses Item.");
            } else {
               double unitPrice = this.unitBuyPrice(offer, currentStock);
               double total = this.roundMoney(unitPrice * amount);
               if (!economy.has(player, total)) {
                  this.send(
                     player,
                     this.text("messages.not-enough-money", "&cDafür reicht dein Guthaben nicht. Preis: &e%price%").replace("%price%", this.formatMoney(total))
                  );
               } else if (!economy.withdraw(player, total)) {
                  this.sendPath(player, "messages.transaction-failed", "&cDer Handel ist fehlgeschlagen. Bitte versuche es erneut.");
               } else {
                  Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack[]{new ItemStack(offer.material(), amount)});
                  int delivered = amount - overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
                  if (delivered < amount) {
                     double refund = this.roundMoney(unitPrice * (amount - delivered));
                     if (refund > 0.0 && !economy.deposit(player, refund)) {
                        this.plugin.getLogger().severe("Markt konnte einen Teilbetrag an " + player.getName() + " nicht erstatten.");
                     }
                  }

                  if (delivered <= 0) {
                     this.sendPath(player, "messages.inventory-full", "&cIn deinem Inventar ist kein Platz für dieses Item.");
                  } else {
                     this.setStock(offer, Math.max(0L, currentStock - delivered));
                     this.saveStock();
                     double paid = this.roundMoney(unitPrice * delivered);
                     this.send(
                        player,
                        this.text("messages.bought", "&aDu hast &e%amount%x %item% &afür &e%price% &agekauft.")
                           .replace("%amount%", Integer.toString(delivered))
                           .replace("%item%", plainName(offer.material()))
                           .replace("%price%", this.formatMoney(paid))
                     );
                  }
               }
            }
         }
      }
   }

   private void sell(Player player, MarketFeature.Offer offer, int requested) {
      EconomyService economy = this.plugin.getEconomyService();
      if (economy == null) {
         this.sendPath(player, "messages.economy-unavailable", "&cDer Markt ist momentan nicht verfügbar.");
      } else {
         ItemStack template = new ItemStack(offer.material());
         int available = this.countPlainItems(player.getInventory(), template);
         int amount = Math.min(requested, available);
         if (amount <= 0) {
            this.send(player, this.text("messages.no-items", "&cDu hast keine normalen %item% zum Verkaufen.").replace("%item%", plainName(offer.material())));
         } else {
            long currentStock = this.getStock(offer);
            double unitPrice = this.roundMoney(this.unitBuyPrice(offer, currentStock) * offer.sellRatio());
            double total = this.roundMoney(unitPrice * amount);
            if (!this.removePlainItems(player.getInventory(), template, amount)) {
               this.sendPath(player, "messages.transaction-failed", "&cDer Handel ist fehlgeschlagen. Bitte versuche es erneut.");
            } else if (!economy.deposit(player, total)) {
               this.restoreItems(player, template, amount);
               this.sendPath(player, "messages.transaction-failed", "&cDer Handel ist fehlgeschlagen. Deine Items wurden zurückgegeben.");
            } else {
               long updatedStock = currentStock > Long.MAX_VALUE - amount ? Long.MAX_VALUE : currentStock + amount;
               this.setStock(offer, updatedStock);
               this.saveStock();
               this.send(
                  player,
                  this.text("messages.sold", "&aDu hast &e%amount%x %item% &afür &e%price% &averkauft.")
                     .replace("%amount%", Integer.toString(amount))
                     .replace("%item%", plainName(offer.material()))
                     .replace("%price%", this.formatMoney(total))
               );
            }
         }
      }
   }

   private int inventoryCapacity(PlayerInventory inventory, Material material) {
      ItemStack template = new ItemStack(material);
      int capacity = 0;

      for (ItemStack item : inventory.getStorageContents()) {
         if (item == null || item.getType().isAir()) {
            capacity += material.getMaxStackSize();
         } else if (item.isSimilar(template)) {
            capacity += Math.max(0, material.getMaxStackSize() - item.getAmount());
         }
      }

      return capacity;
   }

   private int countPlainItems(PlayerInventory inventory, ItemStack template) {
      int amount = 0;

      for (ItemStack item : inventory.getStorageContents()) {
         if (item != null && item.isSimilar(template)) {
            amount += item.getAmount();
         }
      }

      return amount;
   }

   private boolean removePlainItems(PlayerInventory inventory, ItemStack template, int requested) {
      ItemStack[] contents = inventory.getStorageContents();
      int remaining = requested;

      for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
         ItemStack item = contents[slot];
         if (item != null && item.isSimilar(template)) {
            int removed = Math.min(remaining, item.getAmount());
            int left = item.getAmount() - removed;
            if (left <= 0) {
               contents[slot] = null;
            } else {
               item.setAmount(left);
            }

            remaining -= removed;
         }
      }

      if (remaining > 0) {
         return false;
      }

      inventory.setStorageContents(contents);
      return true;
   }

   private void restoreItems(Player player, ItemStack template, int amount) {
      Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack[]{new ItemStack(template.getType(), amount)});

      for (ItemStack item : overflow.values()) {
         player.getWorld().dropItemNaturally(player.getLocation(), item);
      }
   }

   private double unitBuyPrice(MarketFeature.Offer offer, long currentStock) {
      double target = Math.max(1.0, offer.initialStock());
      double multiplier = 1.0 + (target - Math.max(0L, currentStock)) / target;
      double min = Math.max(0.01, this.config.getDouble("pricing.minimum-multiplier", 0.5));
      double max = Math.max(min, this.config.getDouble("pricing.maximum-multiplier", 2.0));
      multiplier = Math.max(min, Math.min(max, multiplier));
      return this.roundMoney(offer.basePrice() * multiplier);
   }

   private double roundMoney(double amount) {
      int places = Math.max(0, Math.min(4, this.plugin.getConfig().getInt("economy.currency.decimal-places", 2)));
      return BigDecimal.valueOf(amount).setScale(places, RoundingMode.HALF_UP).doubleValue();
   }

   private String formatMoney(double amount) {
      EconomyService economy = this.plugin.getEconomyService();
      return economy == null ? String.format(Locale.GERMANY, "%.2f", amount) : economy.format(amount);
   }

   private long getStock(MarketFeature.Offer offer) {
      return Math.max(0L, this.stock.getLong(this.stockPath(offer), offer.initialStock()));
   }

   private void setStock(MarketFeature.Offer offer, long amount) {
      this.stock.set(this.stockPath(offer), Math.max(0L, amount));
   }

   private String stockPath(MarketFeature.Offer offer) {
      return "stock." + offer.categoryId() + "." + offer.material().name();
   }

   private void loadStock() {
      this.stock = this.stockFile.isFile() ? YamlConfiguration.loadConfiguration(this.stockFile) : new YamlConfiguration();
      boolean changed = false;
      ConfigurationSection oldStock = this.stock.getConfigurationSection("stock");
      if (oldStock != null) {
         for (String oldCategoryId : oldStock.getKeys(false)) {
            ConfigurationSection oldCategory = oldStock.getConfigurationSection(oldCategoryId);
            if (oldCategory != null) {
               for (String materialName : oldCategory.getKeys(false)) {
                  Material material = Material.matchMaterial(materialName);
                  if (material != null && material != Material.GOLD_BLOCK) {
                     MarketFeature.Offer target = this.categories()
                        .stream()
                        .flatMap(categoryx -> categoryx.offers().stream())
                        .filter(offer -> offer.material() == material)
                        .findFirst()
                        .orElse(null);
                     if (target != null) {
                        String newPath = this.stockPath(target);
                        String oldPath = "stock." + oldCategoryId + "." + materialName;
                        if (!this.stock.contains(newPath)) {
                           this.stock.set(newPath, this.stock.getLong(oldPath));
                           changed = true;
                        }
                     }
                  }
               }
            }
         }
      }

      for (MarketFeature.Category category : this.categories()) {
         for (MarketFeature.Offer offer : category.offers()) {
            if (!this.stock.contains(this.stockPath(offer))) {
               this.setStock(offer, offer.initialStock());
               changed = true;
            }
         }
      }

      if (changed || !this.stockFile.isFile()) {
         this.saveStock();
      }
   }

   private void saveStock() {
      try {
         File parent = this.stockFile.getParentFile();
         if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Marktordner konnte nicht erstellt werden.");
         }

         this.stock.save(this.stockFile);
      } catch (IOException exception) {
         this.plugin.getLogger().severe("Dynamischer Marktbestand konnte nicht gespeichert werden: " + exception.getMessage());
      }
   }

   private void resetStock(CommandSender sender, String categoryId) {
      List<MarketFeature.Category> available = this.categories();
      if (categoryId != null) {
         MarketFeature.Category category = available.stream().filter(value -> value.id().equalsIgnoreCase(categoryId)).findFirst().orElse(null);
         if (category == null) {
            this.send(sender, this.text("messages.category-not-found", "&cKategorie &e%category% &cwurde nicht gefunden.").replace("%category%", categoryId));
            return;
         }

         this.resetCategory(category);
         this.send(
            sender,
            this.text("messages.stock-reset-category", "&aDer Marktbestand der Kategorie &e%category% &awurde zurückgesetzt.")
               .replace("%category%", category.name())
         );
      } else {
         for (MarketFeature.Category category : available) {
            this.resetCategory(category);
         }

         this.sendPath(sender, "messages.stock-reset", "&aDer gesamte Marktbestand wurde zurückgesetzt.");
      }

      this.saveStock();
   }

   private void resetCategory(MarketFeature.Category category) {
      for (MarketFeature.Offer offer : category.offers()) {
         this.setStock(offer, offer.initialStock());
      }
   }

   private void migrateCategoryLayout() {
      if (this.config.getInt("category-layout-version", 0) < 2) {
         try (InputStream resource = this.plugin.getResource("features/market/config.yml")) {
            if (resource != null) {
               YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
               ConfigurationSection defaultCategories = defaults.getConfigurationSection("categories");
               ConfigurationSection oldCategories = this.config.getConfigurationSection("categories");
               if (defaultCategories != null) {
                  Map<String, Map<String, Object>> oldItems = new LinkedHashMap<>();
                  if (oldCategories != null) {
                     for (String oldCategoryId : oldCategories.getKeys(false)) {
                        ConfigurationSection oldCategory = oldCategories.getConfigurationSection(oldCategoryId);
                        ConfigurationSection items = oldCategory == null ? null : oldCategory.getConfigurationSection("items");
                        if (items != null) {
                           for (String materialName : items.getKeys(false)) {
                              if (!materialName.equalsIgnoreCase("GOLD_BLOCK")) {
                                 ConfigurationSection itemConfig = items.getConfigurationSection(materialName);
                                 Material material = Material.matchMaterial(materialName);
                                 if (itemConfig != null && material != null && material.isItem()) {
                                    String targetId = this.categoryFor(material);
                                    oldItems.put(targetId + "." + material.name(), new LinkedHashMap<>(itemConfig.getValues(false)));
                                 }
                              }
                           }
                        }
                     }
                  }

                  this.config.set("categories", this.copySection(defaultCategories));

                  for (Entry<String, Map<String, Object>> entry : oldItems.entrySet()) {
                     String[] parts = entry.getKey().split("\\.", 2);
                     ConfigurationSection defaultItem = this.config.getConfigurationSection("categories." + parts[0] + ".items." + parts[1]);
                     Map<String, Object> values = new LinkedHashMap<>();
                     if (defaultItem != null) {
                        values.putAll(defaultItem.getValues(false));
                     }

                     values.putAll(entry.getValue());
                     this.config.set("categories." + parts[0] + ".items." + parts[1], values);
                  }

                  this.config.set("category-layout-version", 2);
                  if (!this.save()) {
                     this.plugin.getLogger().warning("Die Markt-Kategorien konnten nicht migriert werden.");
                  }
               }
            }
         } catch (IOException exception) {
            this.plugin.getLogger().warning("Die Markt-Kategorien konnten nicht migriert werden: " + exception.getMessage());
         }
      }
   }

   private String categoryFor(Material material) {
      String name = material.name();
      if (material == Material.BEACON
         || name.contains("ELYTRA")
         || name.contains("TOTEM")
         || name.contains("SHULKER")
         || name.contains("NETHERITE")
         || name.contains("DRAGON_EGG")
         || name.contains("ENCHANTED_GOLDEN_APPLE")) {
         return "special";
      }

      if (!name.endsWith("_LOG")
         && !name.endsWith("_WOOD")
         && !name.endsWith("_PLANKS")
         && !name.endsWith("_LEAVES")
         && !name.endsWith("_SAPLING")
         && !name.contains("BAMBOO")
         && !name.equals("STICK")) {
         if (Set.of(
               "WHEAT",
               "CARROT",
               "POTATO",
               "SUGAR_CANE",
               "CACTUS",
               "PUMPKIN",
               "MELON_SLICE",
               "NETHER_WART",
               "BREAD",
               "COOKED_BEEF",
               "COOKED_PORKCHOP",
               "COOKED_CHICKEN",
               "BAKED_POTATO",
               "GOLDEN_CARROT",
               "APPLE",
               "COOKED_COD",
               "BEETROOT",
               "BEEF",
               "PORKCHOP",
               "CHICKEN",
               "COD",
               "SALMON",
               "SWEET_BERRIES",
               "GLOW_BERRIES"
            )
            .contains(name)) {
            return "food";
         } else if (Set.of(
               "ROTTEN_FLESH",
               "BONE",
               "STRING",
               "GUNPOWDER",
               "ENDER_PEARL",
               "SLIME_BALL",
               "LEATHER",
               "FEATHER",
               "BLAZE_ROD",
               "GHAST_TEAR",
               "SPIDER_EYE",
               "PHANTOM_MEMBRANE",
               "MAGMA_CREAM",
               "ARROW",
               "SPECTRAL_ARROW",
               "TIPPED_ARROW",
               "IRON_SWORD",
               "DIAMOND_SWORD",
               "NETHERITE_SWORD",
               "SHIELD",
               "BOW",
               "CROSSBOW",
               "TRIDENT",
               "MACE"
            )
            .contains(name)) {
            return "combat";
         } else {
            return Set.of(
                     "REDSTONE",
                     "REPEATER",
                     "COMPARATOR",
                     "PISTON",
                     "OBSERVER",
                     "HOPPER",
                     "REDSTONE_LAMP",
                     "NETHERRACK",
                     "QUARTZ",
                     "GLOWSTONE_DUST",
                     "SOUL_SAND",
                     "CHORUS_FRUIT",
                     "PRISMARINE_SHARD",
                     "PRISMARINE_CRYSTALS",
                     "ENDER_CHEST",
                     "CHEST",
                     "BARREL",
                     "HAY_BLOCK",
                     "BRICKS",
                     "GLASS"
                  )
                  .contains(name)
               ? "misc"
               : "mining";
         }
      } else {
         return "wood";
      }
   }

   private Map<String, Object> copySection(ConfigurationSection section) {
      Map<String, Object> copied = new LinkedHashMap<>();

      for (String key : section.getKeys(false)) {
         ConfigurationSection child = section.getConfigurationSection(key);
         copied.put(key, child == null ? section.get(key) : this.copySection(child));
      }

      return copied;
   }

   private static String titleCase(String input) {
      String[] words = input.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').split("\\s+");
      StringBuilder result = new StringBuilder();

      for (String word : words) {
         if (!word.isBlank()) {
            if (!result.isEmpty()) {
               result.append(' ');
            }

            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
         }
      }

      return result.toString();
   }

   private static String plainName(Material material) {
      return titleCase(material.name());
   }

   private record Category(String id, String name, Material icon, int order, List<MarketFeature.Offer> offers) {
   }

   private static final class MarketHolder implements InventoryHolder {
      private final String categoryId;
      private final int page;
      private final String searchQuery;
      private Inventory inventory;

      private MarketHolder(String categoryId, int page, String searchQuery) {
         this.categoryId = categoryId;
         this.page = page;
         this.searchQuery = searchQuery == null ? "" : searchQuery;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private record Offer(String categoryId, Material material, double basePrice, long initialStock, double sellRatio, String displayName) {
   }

   private static final class SearchHolder implements InventoryHolder {
      private Inventory inventory;

      public Inventory getInventory() {
         return this.inventory;
      }
   }
}

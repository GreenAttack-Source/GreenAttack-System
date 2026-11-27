package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import de.greenattack.system.economy.EconomyService;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

public final class SellFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String PERMISSION = "system.sell";
   private static final int INPUT_SLOTS = 45;
   private static final int MENU_SIZE = 54;
   private static final int BALANCE_SLOT = 45;
   private static final int SELL_SLOT = 49;
   private static final int CLOSE_SLOT = 53;

   public SellFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "sell");
      this.mergeMissingDefaultPrices();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public void openFromMarket(Player player) {
      if (!this.isEnabled()) {
         this.sendPath(player, "messages.disabled", "&cDas Verkaufs-Feature ist deaktiviert.");
      } else if (!this.hasFeaturePermission(player, "system.sell")) {
         this.sendPath(player, "messages.no-permission", "&cDazu hast du keine Rechte.");
      } else if (this.plugin.getEconomyService() == null) {
         this.sendPath(player, "messages.economy-unavailable", "&cDie Economy ist gerade nicht verfügbar.");
      } else {
         this.openMenu(player);
      }
   }

   private void mergeMissingDefaultPrices() {
      try (InputStream resource = this.plugin.getResource("features/sell/config.yml")) {
         if (resource != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
            ConfigurationSection defaultPrices = defaults.getConfigurationSection("prices");
            boolean changed = false;
            if (!this.config.contains("price-multiplier")) {
               this.config.set("price-multiplier", defaults.getDouble("price-multiplier", 1.5));
               changed = true;
            }
            if (defaultPrices != null) {

               for (String material : defaultPrices.getKeys(false)) {
                  String path = "prices." + material;
                  if (!this.config.contains(path)) {
                     this.config.set(path, defaultPrices.get(path));
                     changed = true;
                  }
               }

            }
            if (changed && !this.save()) {
               this.plugin.getLogger().warning("Neue Citybuild-Standardpreise konnten nicht in features/sell/config.yml übernommen werden.");
            }
         }
      } catch (IOException exception) {
         this.plugin.getLogger().warning("Sell-Standardpreise konnten nicht geladen werden: " + exception.getMessage());
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Verkaufs-Feature ist deaktiviert.");
         return true;
      }

      if (sender instanceof Player player) {
         if (!this.hasFeaturePermission(player, "system.sell")) {
            this.sendPath(player, "messages.no-permission", "&cDazu hast du keine Rechte.");
            return true;
         } else if (args.length != 0) {
            this.sendPath(player, "messages.usage", "&7Verwendung: &f/sell");
            return true;
         } else if (this.plugin.getEconomyService() == null) {
            this.sendPath(player, "messages.economy-unavailable", "&cDie Economy ist gerade nicht verfügbar.");
            return true;
         } else {
            this.openMenu(player);
            return true;
         }
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
   }

   private void openMenu(Player player) {
      SellFeature.SellMenuHolder holder = new SellFeature.SellMenuHolder(player.getUniqueId());
      Inventory inventory = Bukkit.createInventory(holder, 54, this.text("gui.title", "&8Items verkaufen"));
      holder.inventory = inventory;
      Material fillerMaterial = this.material("gui.filler-material", Material.GRAY_STAINED_GLASS_PANE);
      ItemStack filler = this.named(new ItemStack(fillerMaterial), this.config.getString("gui.filler-name", " "));

      for (int slot = 45; slot < 54; slot++) {
         inventory.setItem(slot, filler);
      }

      EconomyService economy = this.plugin.getEconomyService();
      ItemStack balanceItem = new ItemStack(this.material("gui.balance-material", Material.GOLD_INGOT));
      ItemMeta balanceMeta = balanceItem.getItemMeta();
      if (balanceMeta != null) {
         balanceMeta.setDisplayName(this.text("gui.balance-name", "&6Kontostand"));
         List<String> balanceLore = this.config.getStringList("gui.balance-lore");
         if (balanceLore.isEmpty()) {
            balanceLore = List.of("&7Guthaben: &e%balance%");
         }

         balanceMeta.setLore(balanceLore.stream().map(line -> color(line.replace("%balance%", economy.format(economy.balance(player))))).toList());
         balanceItem.setItemMeta(balanceMeta);
      }

      inventory.setItem(45, balanceItem);
      inventory.setItem(
         49,
         this.configuredButton(
            "gui.sell-material",
            Material.EMERALD,
            "gui.sell-name",
            "&aItems verkaufen",
            "gui.sell-lore",
            List.of("&7Verkauft nur die Items aus den oberen Feldern.", "&eKlicken zum Verkaufen")
         )
      );
      inventory.setItem(
         53,
         this.configuredButton(
            "gui.close-material", Material.BARRIER, "gui.close-name", "&cSchließen", "gui.close-lore", List.of("&7Nicht verkaufte Items bekommst du zurück.")
         )
      );
      player.openInventory(inventory);
   }

   @EventHandler
   public void onSellMenuClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof SellFeature.SellMenuHolder holder) {
         if (event.getWhoClicked() instanceof Player player && holder.owner.equals(player.getUniqueId())) {
            int rawSlot = event.getRawSlot();
            if (rawSlot < 0) {
               event.setCancelled(true);
            } else if (rawSlot < 45) {
               ItemStack cursor = event.getCursor();
               if (cursor != null && !cursor.getType().isAir() && this.isProtected(cursor.getType())) {
                  event.setCancelled(true);
                  this.sendPath(player, "messages.protected-item", "&cDieses Item kann nicht verkauft werden.");
               } else {
                  if (event.getClick() == ClickType.NUMBER_KEY) {
                     ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
                     if (hotbarItem != null && this.isProtected(hotbarItem.getType())) {
                        event.setCancelled(true);
                        this.sendPath(player, "messages.protected-item", "&cDieses Item kann nicht verkauft werden.");
                     }
                  } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                     ItemStack offhand = player.getInventory().getItemInOffHand();
                     if (offhand != null && this.isProtected(offhand.getType())) {
                        event.setCancelled(true);
                        this.sendPath(player, "messages.protected-item", "&cDieses Item kann nicht verkauft werden.");
                     }
                  }
               }
            } else if (rawSlot < 54) {
               event.setCancelled(true);
               if (rawSlot == 49) {
                  this.sellDepositedItems(player, event.getView().getTopInventory());
               } else if (rawSlot == 53) {
                  player.closeInventory();
               }
            } else {
               if (event.isShiftClick()) {
                  event.setCancelled(true);
                  ItemStack clicked = event.getCurrentItem();
                  if (clicked == null || clicked.getType().isAir()) {
                     return;
                  }

                  if (this.isProtected(clicked.getType())) {
                     this.sendPath(player, "messages.protected-item", "&cDieses Item kann nicht verkauft werden.");
                     return;
                  }

                  ItemStack moving = clicked.clone();
                  int remaining = this.insertIntoInput(event.getView().getTopInventory(), moving);
                  if (remaining == clicked.getAmount()) {
                     this.sendPath(player, "messages.menu-full", "&eIn den Einlagefeldern ist kein Platz mehr.");
                     return;
                  }

                  if (remaining <= 0) {
                     event.setCurrentItem(null);
                  } else {
                     clicked.setAmount(remaining);
                     event.setCurrentItem(clicked);
                  }
               }
            }
         } else {
            event.setCancelled(true);
         }
      }
   }

   @EventHandler
   public void onSellMenuDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof SellFeature.SellMenuHolder) {
         for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 45 && rawSlot < 54) {
               event.setCancelled(true);
               return;
            }

            if (rawSlot >= 0
               && rawSlot < 45
               && event.getOldCursor() != null
               && !event.getOldCursor().getType().isAir()
               && this.isProtected(event.getOldCursor().getType())) {
               event.setCancelled(true);
               if (event.getWhoClicked() instanceof Player player) {
                  this.sendPath(player, "messages.protected-item", "&cDieses Item kann nicht verkauft werden.");
               }

               return;
            }
         }
      }
   }

   @EventHandler
   public void onSellMenuClose(InventoryCloseEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof SellFeature.SellMenuHolder holder && event.getPlayer() instanceof Player player) {
         this.returnDepositedItems(player, holder);
      }
   }

   private void sellDepositedItems(Player player, Inventory inventory) {
      if (this.isEnabled() && this.hasFeaturePermission(player, "system.sell")) {
         EconomyService economy = this.plugin.getEconomyService();
         if (economy == null) {
            this.sendPath(player, "messages.economy-unavailable", "&cDie Economy ist gerade nicht verfügbar.");
         } else {
            Set<Material> protectedMaterials = this.protectedMaterials();
            Map<Integer, ItemStack> sellItems = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            int itemCount = 0;

            for (int slot = 0; slot < 45; slot++) {
               ItemStack item = inventory.getItem(slot);
               if (item != null && !item.getType().isAir() && !protectedMaterials.contains(item.getType())) {
                  BigDecimal value = this.stackValue(item);
                  if (value.signum() > 0) {
                     sellItems.put(slot, item);
                     total = total.add(value);
                     itemCount += item.getAmount();
                  }
               }
            }

            int decimalPlaces = Math.max(0, Math.min(4, this.plugin.getConfig().getInt("economy.currency.decimal-places", 2)));
            total = total.setScale(decimalPlaces, RoundingMode.HALF_UP);
            if (sellItems.isEmpty() || total.signum() <= 0) {
               this.sendPath(player, "messages.nothing-to-sell", "&eLege zuerst verkaufbare Items in die oberen Felder.");
            } else if (!economy.deposit(player, total.doubleValue())) {
               this.sendPath(player, "messages.deposit-failed", "&cDer Betrag konnte nicht gutgeschrieben werden. Deine Items bleiben im Menü.");
            } else {
               for (int slot : sellItems.keySet()) {
                  inventory.setItem(slot, null);
               }

               this.send(
                  player,
                  this.config
                     .getString("messages.sold", "&aDu hast &e%items% Items &afür &e%amount% &averkauft.")
                     .replace("%items%", Integer.toString(itemCount))
                     .replace("%amount%", economy.format(total.doubleValue()))
               );
               player.closeInventory();
            }
         }
      } else {
         this.sendPath(player, "messages.no-permission", "&cDazu hast du keine Rechte.");
         player.closeInventory();
      }
   }

   private int insertIntoInput(Inventory inventory, ItemStack moving) {
      int remaining = moving.getAmount();

      for (int slot = 0; slot < 45 && remaining > 0; slot++) {
         ItemStack existing = inventory.getItem(slot);
         if (existing != null && !existing.getType().isAir() && existing.isSimilar(moving)) {
            int limit = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize());
            int add = Math.min(remaining, Math.max(0, limit - existing.getAmount()));
            if (add > 0) {
               existing.setAmount(existing.getAmount() + add);
               remaining -= add;
            }
         }
      }

      for (int slot = 0; slot < 45 && remaining > 0; slot++) {
         ItemStack existing = inventory.getItem(slot);
         if (existing == null || existing.getType().isAir()) {
            int add = Math.min(remaining, moving.getMaxStackSize());
            ItemStack stack = moving.clone();
            stack.setAmount(add);
            inventory.setItem(slot, stack);
            remaining -= add;
         }
      }

      return remaining;
   }

   private void returnDepositedItems(Player player, SellFeature.SellMenuHolder holder) {
      if (!holder.itemsReturned) {
         holder.itemsReturned = true;
         Inventory inventory = holder.inventory;
         if (inventory != null) {
            for (int slot = 0; slot < 45; slot++) {
               ItemStack item = inventory.getItem(slot);
               if (item != null && !item.getType().isAir()) {
                  inventory.setItem(slot, null);
                  Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack[]{item.clone()});
                  leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
               }
            }
         }
      }
   }

   public void shutdownFeature() {
      for (Player player : Bukkit.getOnlinePlayers()) {
         if (player.getOpenInventory().getTopInventory().getHolder() instanceof SellFeature.SellMenuHolder holder) {
            this.returnDepositedItems(player, holder);
            player.closeInventory();
         }
      }
   }

   private BigDecimal stackValue(ItemStack item) {
      BigDecimal defaultPrice = this.safePrice(this.config.getDouble("default-price", 0.05));
      BigDecimal unitPrice = this.safePrice(this.config.getDouble("prices." + item.getType().name(), defaultPrice.doubleValue()));
      if (unitPrice.signum() <= 0) {
         return BigDecimal.ZERO;
      }

      BigDecimal itemValue = unitPrice.multiply(BigDecimal.valueOf(item.getAmount()));
      if (item.getItemMeta() instanceof Damageable damageable && item.getType().getMaxDurability() > 0) {
         int maximumDamage = item.getType().getMaxDurability();
         int remainingDurability = Math.max(0, maximumDamage - damageable.getDamage());
         itemValue = itemValue.multiply(BigDecimal.valueOf(remainingDurability)).divide(BigDecimal.valueOf(maximumDamage), 8, RoundingMode.HALF_UP);
      }

      BigDecimal multiplier = this.safePrice(this.config.getDouble("price-multiplier", 1.5));
      itemValue = itemValue.multiply(multiplier);
      return itemValue.signum() > 0 ? itemValue : BigDecimal.ZERO;
   }

   private Set<Material> protectedMaterials() {
      Set<Material> materials = new HashSet<>();

      for (String name : this.config.getStringList("protected-materials")) {
         Material material = Material.matchMaterial(name.trim());
         if (material != null) {
            materials.add(material);
         } else {
            this.plugin.getLogger().warning("Unbekanntes geschütztes Sell-Material in features/sell/config.yml: " + name);
         }
      }

      return materials;
   }

   private boolean isProtected(Material material) {
      return this.protectedMaterials().contains(material);
   }

   private Material material(String path, Material fallback) {
      Material parsed = Material.matchMaterial(this.config.getString(path, fallback.name()));
      return parsed == null ? fallback : parsed;
   }

   private ItemStack configuredButton(
      String materialPath, Material fallbackMaterial, String namePath, String fallbackName, String lorePath, List<String> fallbackLore
   ) {
      ItemStack item = new ItemStack(this.material(materialPath, fallbackMaterial));
      ItemMeta meta = item.getItemMeta();
      if (meta == null) {
         return item;
      }

      meta.setDisplayName(this.text(namePath, fallbackName));
      List<String> lore = this.config.getStringList(lorePath);
      if (lore.isEmpty()) {
         lore = fallbackLore;
      }

      meta.setLore(lore.stream().map(FeatureModule::color).toList());
      item.setItemMeta(meta);
      return item;
   }

   private ItemStack named(ItemStack item, String name) {
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(color(name));
         item.setItemMeta(meta);
      }

      return item;
   }

   private BigDecimal safePrice(double price) {
      return Double.isFinite(price) && !(price < 0.0) ? BigDecimal.valueOf(price) : BigDecimal.ZERO;
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      return List.of();
   }

   private static final class SellMenuHolder implements InventoryHolder {
      private final UUID owner;
      private Inventory inventory;
      private boolean itemsReturned;

      private SellMenuHolder(UUID owner) {
         this.owner = owner;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }
}

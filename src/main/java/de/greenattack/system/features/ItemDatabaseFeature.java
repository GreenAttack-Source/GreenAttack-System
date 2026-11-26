package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemDatabaseFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   public ItemDatabaseFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "db");
      Bukkit.getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
         return true;
      } else if (!(sender instanceof Player player)) {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      } else {
         if (!this.hasPermission(player, "permissions.use", "system.db.use")) {
            return true;
         }

         if (args.length == 0) {
            this.openPage(player, 0);
            return true;
         }

         if (args.length == 1 && (args[0].equalsIgnoreCase("hand") || args[0].equalsIgnoreCase("add"))) {
            if (!this.hasPermission(player, "permissions.add", "system.db.add")) {
               return true;
            } else {
               ItemStack hand = player.getInventory().getItemInMainHand();
               if (hand.getType().isAir()) {
                  this.sendPath(player, "messages.hold-item", "&cDu musst ein Item in der Hand halten.");
                  return true;
               } else {
                  ItemStack item = hand.clone();
                  item.setAmount(1);
                  List<ItemStack> items = this.items();
                  if (items.stream().anyMatch(existing -> existing.isSimilar(item))) {
                     this.sendPath(player, "messages.duplicate", "&cDieses Item existiert bereits in der Datenbank.");
                     return true;
                  } else {
                     items.add(item);
                     this.config.set("items", items);
                     this.save();
                     this.send(player, this.config.getString("messages.added", "&aItem &e%item% &awurde hinzugefügt.").replace("%item%", item.getType().name()));
                     return true;
                  }
               }
            }
         } else {
            this.sendPath(player, "messages.usage", "&7Verwendung: &f/db &7oder &f/db hand");
            return true;
         }
      }
   }

   private boolean hasPermission(Player player, String path, String fallback) {
      if (this.hasFeaturePermission(player, this.config.getString(path, fallback))) {
         return true;
      }

      this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
      return false;
   }

   private List<ItemStack> items() {
      List<ItemStack> result = new ArrayList<>();

      for (ItemStack item : this.config.getList("items", List.of()).stream().filter(ItemStack.class::isInstance).map(ItemStack.class::cast).toList()) {
         if (item != null && !item.getType().isAir()) {
            result.add(item.clone());
         }
      }

      return result;
   }

   private void openPage(Player player, int page) {
      List<ItemStack> items = this.items();
      int pages = Math.max(1, (items.size() + 44) / 45);
      int safePage = Math.max(0, Math.min(page, pages - 1));
      String title = this.config.getString("gui-title", "&8» &bItem-Datenbank");
      ItemDatabaseFeature.DatabaseHolder holder = new ItemDatabaseFeature.DatabaseHolder(safePage);
      Inventory inventory = Bukkit.createInventory(holder, 54, color(title + " &8(" + (safePage + 1) + "/" + pages + ")"));
      holder.inventory = inventory;
      ItemStack glass = this.named(Material.GRAY_STAINED_GLASS_PANE, " ");

      for (int slot = 45; slot < 54; slot++) {
         inventory.setItem(slot, glass);
      }

      for (int slot = 0; slot < 45; slot++) {
         int index = safePage * 45 + slot;
         if (index >= items.size()) {
            break;
         }

         inventory.setItem(slot, items.get(index));
      }

      if (safePage > 0) {
         inventory.setItem(48, this.named(Material.ARROW, "&e« Vorherige Seite"));
      }

      if (safePage + 1 < pages) {
         inventory.setItem(50, this.named(Material.ARROW, "&eNächste Seite »"));
      }

      player.openInventory(inventory);
   }

   private ItemStack named(Material material, String name) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      meta.setDisplayName(color(name));
      item.setItemMeta(meta);
      return item;
   }

   @EventHandler
   public void onDatabaseClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof ItemDatabaseFeature.DatabaseHolder holder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            int slot = event.getRawSlot();
            if (slot == 48 && holder.page > 0) {
               this.openPage(player, holder.page - 1);
            } else if (slot == 50) {
               this.openPage(player, holder.page + 1);
            } else if (slot >= 0 && slot < 45) {
               ItemStack clicked = event.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  if (player.isSneaking()) {
                     if (this.hasPermission(player, "permissions.admin", "system.db.admin")) {
                        List<ItemStack> items = this.items();
                        int absoluteIndex = holder.page * 45 + slot;
                        if (absoluteIndex < items.size() && items.get(absoluteIndex).isSimilar(clicked)) {
                           items.remove(absoluteIndex);
                           this.config.set("items", items);
                           this.save();
                           this.sendPath(player, "messages.deleted", "&cItem aus der Datenbank gelöscht.");
                           this.openPage(player, holder.page);
                        }
                     }
                  } else {
                     if (event.isRightClick()) {
                        ItemStack copy = clicked.clone();
                        copy.setAmount(1);
                        HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack[]{copy});
                        if (!overflow.isEmpty()) {
                           player.getWorld().dropItemNaturally(player.getLocation(), overflow.values().iterator().next());
                        }

                        this.send(
                           player, this.config.getString("messages.taken", "&aDu hast dir &e%item% &aherausgenommen.").replace("%item%", copy.getType().name())
                        );
                     }
                  }
               }
            }
         }
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (this.isEnabled() && this.hasFeaturePermission(sender, this.config.getString("permissions.use", "system.db.use"))) {
         if (args.length == 1 && this.hasFeaturePermission(sender, this.config.getString("permissions.add", "system.db.add"))) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return List.of("hand", "add").stream().filter(value -> value.startsWith(partial)).toList();
         } else {
            return List.of();
         }
      } else {
         return List.of();
      }
   }

   private static final class DatabaseHolder implements InventoryHolder {
      private final int page;
      private Inventory inventory;

      private DatabaseHolder(int page) {
         this.page = page;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }
}

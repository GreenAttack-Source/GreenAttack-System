package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

public final class KitFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String ADMIN_PERMISSION = "system.kit.admin";
   private static final int STORAGE_SIZE = 36;
   private static final int ARMOR_SIZE = 4;

   public KitFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "kit");
      this.config.options().copyDefaults(true);
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Starterkit ist derzeit deaktiviert.");
         return true;
      }
      if (args.length == 1 && args[0].equalsIgnoreCase("set")) {
         this.setKit(sender);
         return true;
      }
      this.sendPath(sender, "messages.usage", "&7Verwendung: &f/kit set");
      return true;
   }

   private void setKit(CommandSender sender) {
      if (!this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      if (!(sender instanceof Player player)) {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl kann nur ein Spieler verwenden.");
         return;
      }

      // Ersetze den kompletten gespeicherten Kit-Abschnitt, damit keine alten Slots bleiben.
      this.config.set("kit", null);
      ItemStack[] storage = player.getInventory().getStorageContents();
      for (int slot = 0; slot < STORAGE_SIZE; slot++) {
         this.config.set("kit.inventory." + slot, cloneIfPresent(storage[slot]));
      }
      ItemStack[] armor = player.getInventory().getArmorContents();
      for (int slot = 0; slot < ARMOR_SIZE; slot++) {
         this.config.set("kit.armor." + slot, cloneIfPresent(armor[slot]));
      }
      this.config.set("kit.offhand", cloneIfPresent(player.getInventory().getItemInOffHand()));
      this.config.set("kit.ready", true);
      this.config.set("kit.updated-by", player.getName());
      this.config.set("kit.updated-at", System.currentTimeMillis());

      if (!this.save()) {
         this.sendPath(sender, "messages.save-failed", "&cDas Starterkit konnte nicht gespeichert werden.");
         return;
      }
      this.sendPath(sender, "messages.saved", "&aDein Inventar inklusive Rüstung und Nebenhand wurde als Starterkit gespeichert.");
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      if (!this.isEnabled() || !this.config.getBoolean("kit.ready", false) || player.hasPlayedBefore()) {
         return;
      }

      String claimPath = "claimed." + player.getUniqueId();
      if (this.config.getBoolean(claimPath, false)) {
         return;
      }

      // A short delay allows join-time data restoration to finish before the first-join kit is applied.
      Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
         if (!player.isOnline() || !this.isEnabled() || !this.config.getBoolean("kit.ready", false)
            || this.config.getBoolean(claimPath, false) || !isInventoryEmpty(player)) {
            return;
         }

         ItemStack[] storage = new ItemStack[STORAGE_SIZE];
         for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            storage[slot] = cloneIfPresent(this.config.getItemStack("kit.inventory." + slot));
         }
         ItemStack[] armor = new ItemStack[ARMOR_SIZE];
         for (int slot = 0; slot < ARMOR_SIZE; slot++) {
            armor[slot] = cloneIfPresent(this.config.getItemStack("kit.armor." + slot));
         }
         ItemStack offhand = cloneIfPresent(this.config.getItemStack("kit.offhand"));

         // Persist the one-time marker first. Every kit slot is then assigned to exactly one inventory slot.
         this.config.set(claimPath, true);
         if (!this.save()) {
            this.config.set(claimPath, null);
            this.plugin.getLogger().warning("Starterkit konnte für " + player.getName() + " nicht als ausgegeben gespeichert werden.");
            return;
         }

         player.getInventory().setStorageContents(storage);
         player.getInventory().setArmorContents(armor);
         player.getInventory().setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand);
         player.updateInventory();
         this.sendPath(player, "messages.joined", "&aDu hast dein Starterkit erhalten.");
      }, 20L);
   }

   private static boolean isInventoryEmpty(Player player) {
      for (ItemStack item : player.getInventory().getStorageContents()) {
         if (!isEmpty(item)) {
            return false;
         }
      }
      for (ItemStack item : player.getInventory().getArmorContents()) {
         if (!isEmpty(item)) {
            return false;
         }
      }
      return isEmpty(player.getInventory().getItemInOffHand());
   }

   private static boolean isEmpty(ItemStack item) {
      return item == null || item.getType().isAir();
   }

   private static ItemStack cloneIfPresent(ItemStack item) {
      return isEmpty(item) ? null : item.clone();
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length != 1 || !this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         return List.of();
      }
      String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
      List<String> suggestions = new ArrayList<>();
      if ("set".startsWith(prefix)) {
         suggestions.add("set");
      }
      return suggestions;
   }
}

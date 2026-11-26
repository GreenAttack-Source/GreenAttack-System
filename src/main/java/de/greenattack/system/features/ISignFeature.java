package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ISignFeature extends FeatureModule implements CommandExecutor {
   public ISignFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "isign");
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
         return true;
      }

      if (sender instanceof Player player) {
         if (!this.hasFeaturePermission(player, this.config.getString("permissions.use", "system.isign"))) {
            this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
            return true;
         }

         if (args.length > 1) {
            this.sendPath(player, "messages.usage", "&7Verwendung: &f/isign [Spieler]");
            return true;
         }

         String target = player.getName();
         if (args.length == 1) {
            String adminPermission = this.config.getString("permissions.admin", "system.isign.admin");
            String legacyPermission = this.config.getString("permissions.legacy-admin", "system.isgn.admin");
            if (!this.hasFeaturePermission(player, adminPermission) && !this.hasFeaturePermission(player, legacyPermission)) {
               this.sendPath(player, "messages.admin-permission", "&cDu darfst nicht für andere Spieler signieren.");
               return true;
            }

            target = args[0];
         }

         ItemStack item = player.getInventory().getItemInMainHand();
         if (item.getType().isAir()) {
            this.sendPath(player, "messages.hold-item", "&cDu musst ein Item in der Hand halten.");
            return true;
         } else {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
               this.sendPath(player, "messages.hold-item", "&cDieses Item kann nicht signiert werden.");
               return true;
            } else {
               List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
               String signedName = target;
               boolean protectedRank = this.config.getStringList("protected-rank-permissions").stream().anyMatch(player::hasPermission);
               if (protectedRank && lore.stream().anyMatch(line -> line.contains(signedName))) {
                  this.sendPath(player, "messages.already-signed", "&cDer Name dieses Spielers steht bereits in der Item-Lore.");
                  return true;
               } else {
                  lore.add(color("&7Signiert von &b" + target));
                  meta.setLore(lore);
                  item.setItemMeta(meta);
                  String path = args.length == 0 ? "messages.success-self" : "messages.success-other";
                  String fallback = args.length == 0 ? "&aDu hast das Item für dich signiert." : "&aDu hast das Item für &e%player% &asigniert.";
                  this.send(player, this.config.getString(path, fallback).replace("%player%", target));
                  return true;
               }
            }
         }
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
   }
}

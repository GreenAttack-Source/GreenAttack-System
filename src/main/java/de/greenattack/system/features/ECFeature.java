package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ECFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   public ECFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "enderchest");
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
         return true;
      }

      if (!this.hasFeaturePermission(sender, this.config.getString("permissions.ec", "system.ec"))) {
         this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return true;
      }

      if (sender instanceof Player player) {
         if (args.length != 0) {
            this.sendPath(player, "messages.usage", "&7Verwendung: &f/ec");
            return true;
         } else {
            player.openInventory(player.getEnderChest());
            this.sendPath(player, "messages.opened", "&aDu hast deine Endertruhe geöffnet.");
            return true;
         }
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      return List.of();
   }

   public void reloadFeature() {
      this.reload();
   }
}

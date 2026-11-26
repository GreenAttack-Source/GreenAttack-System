package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;

/**
 * Macht /invsee und /ecsee für Offline-Spieler nutzbar, ohne selbst Spielerdaten anzufassen:
 * Ist der Spieler online, läuft alles unverändert über die bisherige Logik. Ist er offline und der
 * Absender hat system.invsee bzw. system.ecsee, wird stattdessen OpenInv aufgerufen (eigenes Plugin,
 * das Offline-Inventare sicher lädt und speichert). Muss NACH PlayerToolsFeature erzeugt werden.
 */
public final class OfflineInvSeeBridge extends FeatureModule {
   public OfflineInvSeeBridge(GreenAttackSystemPlugin plugin) {
      super(plugin, "invsee-bridge");
      try {
         if (ConfigDefaults.merge(plugin, this.config, "features/invsee-bridge/config.yml")) {
            this.save();
         }
         this.wrap("invsee", false);
         this.wrap("ecsee", true);
      } catch (Throwable throwable) {
         plugin.getLogger().log(Level.WARNING, "Offline-/invsee-Brücke konnte nicht eingerichtet werden", throwable);
      }
   }

   private void wrap(String commandName, boolean enderChest) {
      PluginCommand command = this.plugin.getCommand(commandName);
      if (command == null) {
         return;
      }
      CommandExecutor original = command.getExecutor();
      if (original == null) {
         return;
      }
      command.setExecutor((CommandExecutor) (sender, cmd, label, args) -> this.execute(original, enderChest, sender, cmd, label, args));
   }

   private boolean execute(CommandExecutor original, boolean enderChest, CommandSender sender, Command cmd, String label, String[] args) {
      if (args.length != 1 || !(sender instanceof Player) || Bukkit.getPlayerExact(args[0]) != null) {
         return original.onCommand(sender, cmd, label, args);
      }
      this.reload();
      if (!this.isEnabled() || !this.hasFeaturePermission(sender, enderChest ? "system.ecsee" : "system.invsee")) {
         return original.onCommand(sender, cmd, label, args);
      }

      String target = args[0];
      OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
      if (offline == null || !offline.hasPlayedBefore()) {
         this.send(sender, this.text("messages.player-unknown", "&cDen Spieler &e%player% &chat es nie gegeben.").replace("%player%", target));
         return true;
      }
      if (Bukkit.getPluginManager().getPlugin("OpenInv") == null) {
         this.send(sender, this.text("messages.openinv-missing", "&cOpenInv ist nicht installiert.").replace("%player%", target));
         return true;
      }

      String template = enderChest
         ? this.text("openinv-enderchest-command", "openinv:openender %player%")
         : this.text("openinv-inventory-command", "openinv:openinv %player%");
      this.send(sender, this.text("messages.delegating", "&7%player% ist offline, öffne über OpenInv ...").replace("%player%", target));
      Bukkit.dispatchCommand(sender, template.replace("%player%", target));
      return true;
   }
}

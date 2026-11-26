package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

/**
 * Ergänzt die Tab-Vervollständigung bestehender Befehle (/ka, /invsee, /ecsee) um Offline-Spieler,
 * ohne deren eigentliche Klassen zu verändern: Die bisherige TabCompleter-Instanz jedes Befehls wird
 * geholt, hinter einem eigenen TabCompleter "versteckt" und für die Spieler-Position weiterhin zuerst
 * gefragt (ihre eigene Rechteprüfung bleibt also bestehen); nur wenn der Absender das jeweilige Recht
 * hat, werden zusätzlich Offline-Namen angeboten. Muss NACH den betroffenen Features erzeugt werden.
 */
public final class OfflineTabCompleteBridge {
   public OfflineTabCompleteBridge(GreenAttackSystemPlugin plugin) {
      try {
         this.wrap(plugin, "ka", 2, "system.kristall.admin");
         this.wrap(plugin, "invsee", 1, "system.invsee");
         this.wrap(plugin, "ecsee", 1, "system.ecsee");
      } catch (Throwable throwable) {
         plugin.getLogger().log(Level.WARNING, "Offline-Autocomplete für /ka, /invsee, /ecsee konnte nicht eingerichtet werden", throwable);
      }
   }

   private void wrap(GreenAttackSystemPlugin plugin, String commandName, int playerArgIndex, String permission) {
      PluginCommand command = plugin.getCommand(commandName);
      if (command == null) {
         return;
      }
      TabCompleter original = command.getTabCompleter();
      command.setTabCompleter((TabCompleter) (sender, cmd, alias, args) -> this.complete(plugin, original, sender, cmd, alias, args, playerArgIndex, permission));
   }

   private List<String> complete(GreenAttackSystemPlugin plugin, TabCompleter original, CommandSender sender, org.bukkit.command.Command cmd,
      String alias, String[] args, int playerArgIndex, String permission) {
      List<String> base = original == null ? List.of() : original.onTabComplete(sender, cmd, alias, args);
      if (args.length != playerArgIndex || !plugin.hasSystemPermission(sender, permission)) {
         return base == null ? List.of() : base;
      }
      Set<String> merged = new LinkedHashSet<>(base == null ? List.of() : base);
      merged.addAll(OfflinePlayerNames.suggest(args[args.length - 1]));
      return new ArrayList<>(merged);
   }
}

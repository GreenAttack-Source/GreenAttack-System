package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Berechtigungsprüfung für alle system.*-Rechte. Ersetzt die ursprüngliche Logik 1:1
 * (eigenes Recht ODER system.admin), ergänzt um zwei Sicherheitsnetze:
 *  - Server-Operatoren dürfen jedes system.*-Recht nutzen, auch wenn LuckPerms/eine andere
 *    Rechte-Verwaltung das Recht (noch) nicht zugewiesen hat. Das verhindert, dass ein falsch
 *    gesetztes oder fehlendes Recht Admins komplett aussperrt (zum Beispiel bei /codereset).
 *  - Optional (debug.log-permission-denials in config.yml) wird jede Ablehnung mit dem genauen
 *    geprüften Rechtenamen und dem passenden /lp-Befehl ins Log geschrieben.
 */
public final class PermissionUtil {
   private PermissionUtil() {
   }

   public static boolean hasSystemPermission(GreenAttackSystemPlugin plugin, CommandSender sender, String permission) {
      if (permission == null || permission.isBlank()) {
         return false;
      }
      if (sender.hasPermission(permission)) {
         return true;
      }
      if (permission.toLowerCase(Locale.ROOT).startsWith("system.") && !permission.equalsIgnoreCase("system.admin") && sender.hasPermission("system.admin")) {
         return true;
      }
      if (sender instanceof Player player && player.isOp()) {
         return true;
      }
      if (sender instanceof Player diagnosticPlayer && plugin.getConfig().getBoolean("debug.log-permission-denials", false)) {
         plugin.getLogger().info("Berechtigung verweigert: " + diagnosticPlayer.getName() + " hat nicht '" + permission
            + "' (auch nicht ueber system.admin). Pruefe das mit /lp user " + diagnosticPlayer.getName() + " permission check " + permission + ".");
      }
      return false;
   }
}

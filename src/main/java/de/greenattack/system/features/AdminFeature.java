package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

/** Zentraler Einstiegspunkt für serverweite und dedizierte Admin-Befehle. */
public final class AdminFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   private static final String ADMIN_PERMISSION = "system.admin";
   private final Map<String, DelegatedCommand> delegatedCommands = new ConcurrentHashMap<>();

   public AdminFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "admin");
      this.config.addDefault("permissions.admin", ADMIN_PERMISSION);
      this.config.options().copyDefaults(true);
      if (ConfigDefaults.merge(plugin, this.config, "features/admin/config.yml")) {
         this.save();
      }
   }

   public void registerDelegatedCommand(String name, CommandExecutor executor, TabCompleter completer) {
      if (name == null || executor == null) {
         return;
      }
      String key = name.toLowerCase(Locale.ROOT);
      PluginCommand command = this.plugin.getCommand(key);
      if (command == null) {
         this.plugin.getLogger().warning("Admin-Befehl /" + key + " fehlt in der plugin.yml.");
         return;
      }
      this.delegatedCommands.put(key, new DelegatedCommand(executor, completer));
      command.setExecutor(this);
      command.setTabCompleter(this);
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      DelegatedCommand delegated = this.delegatedCommands.get(command.getName().toLowerCase(Locale.ROOT));
      if (delegated != null) {
         return delegated.executor.onCommand(sender, command, label, args);
      }
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Admin-Feature ist derzeit deaktiviert.");
         return true;
      }
      if (!this.hasFeaturePermission(sender, this.config.getString("permissions.admin", ADMIN_PERMISSION))) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return true;
      }

      if (command.getName().equalsIgnoreCase("slots")) {
         if (args.length == 1) {
            this.plugin.setSlotLimit(sender, args[0]);
         } else {
            this.sendPath(sender, "messages.usage-slots", "&7Verwendung: &f/slots <anzahl>");
         }
         return true;
      }

      if (command.getName().equalsIgnoreCase("greenattack")) {
         if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            this.plugin.reloadFromAdmin(sender);
         } else if (args.length == 2 && args[0].equalsIgnoreCase("slots")) {
            this.plugin.setSlotLimit(sender, args[1]);
         } else {
            this.sendPath(sender, "messages.usage-greenattack", "&7Verwendung: &f/greenattack reload &7oder &f/greenattack slots <anzahl>");
         }
         return true;
      }
      return false;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      DelegatedCommand delegated = this.delegatedCommands.get(command.getName().toLowerCase(Locale.ROOT));
      if (delegated != null) {
         return delegated.completer == null ? List.of() : delegated.completer.onTabComplete(sender, command, alias, args);
      }
      if (!this.isEnabled() || !this.hasFeaturePermission(sender, this.config.getString("permissions.admin", ADMIN_PERMISSION))) {
         return List.of();
      }

      if (command.getName().equalsIgnoreCase("slots") && args.length == 1) {
         return this.numberSuggestions(args[0]);
      }
      if (!command.getName().equalsIgnoreCase("greenattack")) {
         return List.of();
      }
      if (args.length == 1) {
         String partial = args[0].toLowerCase(Locale.ROOT);
         return List.of("reload", "slots").stream().filter(value -> value.startsWith(partial)).toList();
      }
      return args.length == 2 && args[0].equalsIgnoreCase("slots") ? this.numberSuggestions(args[1]) : List.of();
   }

   private List<String> numberSuggestions(String partial) {
      List<String> suggestions = new ArrayList<>(List.of(Integer.toString(this.plugin.getServer().getMaxPlayers()), "50", "100", "150", "200", "500"));
      return suggestions.stream().distinct().filter(value -> value.startsWith(partial)).toList();
   }

   private record DelegatedCommand(CommandExecutor executor, TabCompleter completer) {
   }
}

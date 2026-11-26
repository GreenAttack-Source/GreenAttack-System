package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * "create-cmd's": eigene Befehle, die komplett in features/create-cmds/config.yml definiert werden
 * (Name, Aliase, Berechtigung, Beschreibung, einer oder mehrere Konsolenbefehle pro Aufruf). Jeder
 * Eintrag wird beim Start und bei /customcmd reload als echter Spielbefehl angemeldet, über dieselbe
 * CommandMap-Reflektion, die GreenAttack-System schon für /pay, /eco und /invsee verwendet.
 *
 * Kollidiert ein eigener Befehl mit einem schon vorhandenen Plugin-Befehl, wird er NICHT angemeldet
 * und eine Warnung ins Log geschrieben, damit kein wichtiger Befehl (zum Beispiel /home) überschrieben wird.
 */
public final class CustomCommandsFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   private static final String MANAGE_PERMISSION = "system.customcmd.admin";
   private final List<String> registeredNames = new ArrayList<>();

   public CustomCommandsFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "create-cmds");
      try {
         if (ConfigDefaults.merge(plugin, this.config, "features/create-cmds/config.yml")) {
            this.save();
         }
         PluginCommand manage = plugin.getCommand("customcmd");
         if (manage != null) {
            manage.setExecutor(this);
            manage.setTabCompleter(this);
         }
         this.registerConfiguredCommands();
      } catch (Throwable throwable) {
         plugin.getLogger().log(Level.SEVERE, "create-cmd's konnte nicht gestartet werden", throwable);
      }
   }

   // ------------------------------------------------------------------ Verwaltung (/customcmd)

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      this.reload();
      if (!this.hasFeaturePermission(sender, MANAGE_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return true;
      }
      if (args.length == 0) {
         this.sendUsage(sender);
         return true;
      }

      switch (args[0].toLowerCase(Locale.ROOT)) {
         case "reload":
            this.registerConfiguredCommands();
            this.sendPath(sender, "messages.reloaded", "&aEigene Befehle wurden neu geladen und angemeldet.");
            break;
         case "list":
            this.handleList(sender);
            break;
         case "create":
            this.handleCreate(sender, args);
            break;
         case "delete":
            this.handleDelete(sender, args);
            break;
         default:
            this.sendUsage(sender);
            break;
      }
      return true;
   }

   private void sendUsage(CommandSender sender) {
      this.sendPath(sender, "messages.usage", "&7Verwendung: &f/customcmd <list|reload|create <Name> <Befehl...>|delete <Name>>");
      this.sendPath(sender, "messages.usage-hint", "&7Aliase, Berechtigung und mehrere Befehle pro Eintrag stellst du direkt in &ffeatures/create-cmds/config.yml &7ein.");
   }

   private void handleList(CommandSender sender) {
      if (this.registeredNames.isEmpty()) {
         this.sendPath(sender, "messages.list-empty", "&7Es sind noch keine eigenen Befehle angemeldet.");
         return;
      }
      this.send(sender, this.text("messages.list-header", "&aEigene Befehle:"));
      for (String name : this.registeredNames) {
         this.send(sender, this.text("messages.list-line", "&8- &f/%command%").replace("%command%", name));
      }
   }

   /** Schnelles Anlegen ohne die Config zu öffnen: ein Befehl, ein Konsolenbefehl. Für mehr (Aliase, Rechte,
    * mehrere Befehle) editiert man weiterhin die config.yml und lädt mit /customcmd reload neu. */
   private void handleCreate(CommandSender sender, String[] args) {
      if (args.length < 3) {
         this.sendPath(sender, "messages.usage-create", "&7Verwendung: &f/customcmd create <Name> <Konsolenbefehl mit %player%>");
         return;
      }
      String name = args[1].toLowerCase(Locale.ROOT);
      if (!name.matches("[a-z0-9_-]{2,32}")) {
         this.sendPath(sender, "messages.invalid-name", "&cDer Befehlsname darf nur Kleinbuchstaben, Zahlen, _ und - enthalten (2 bis 32 Zeichen).");
         return;
      }
      if (this.config.contains("commands." + name)) {
         this.send(sender, this.text("messages.already-exists", "&cDen Befehl &e/%command% &cgibt es schon.").replace("%command%", name));
         return;
      }
      String action = String.join(" ", List.of(args).subList(2, args.length));
      if (action.startsWith("/")) {
         action = action.substring(1);
      }
      this.config.set("commands." + name + ".aliases", new ArrayList<String>());
      this.config.set("commands." + name + ".permission", "");
      this.config.set("commands." + name + ".description", "Eigener Befehl.");
      this.config.set("commands." + name + ".actions", List.of(action));
      this.save();
      this.registerConfiguredCommands();
      this.send(sender, this.text("messages.created", "&aBefehl &e/%command% &awurde erstellt und angemeldet.").replace("%command%", name));
   }

   private void handleDelete(CommandSender sender, String[] args) {
      if (args.length < 2) {
         this.sendPath(sender, "messages.usage-delete", "&7Verwendung: &f/customcmd delete <Name>");
         return;
      }
      String name = args[1].toLowerCase(Locale.ROOT);
      if (!this.config.contains("commands." + name)) {
         this.send(sender, this.text("messages.unknown-command", "&cDen Befehl &e/%command% &cgibt es nicht.").replace("%command%", name));
         return;
      }
      this.config.set("commands." + name, null);
      this.save();
      this.registerConfiguredCommands();
      this.send(sender, this.text("messages.deleted", "&aBefehl &e/%command% &awurde gelöscht.").replace("%command%", name));
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.hasFeaturePermission(sender, MANAGE_PERMISSION)) {
         return List.of();
      }
      if (args.length == 1) {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         return List.of("list", "reload", "create", "delete").stream().filter(option -> option.startsWith(prefix)).toList();
      }
      if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
         String prefix = args[1].toLowerCase(Locale.ROOT);
         return this.registeredNames.stream().filter(name -> name.startsWith(prefix)).toList();
      }
      return List.of();
   }

   // ------------------------------------------------------------------ Dynamische Anmeldung

   private void registerConfiguredCommands() {
      this.unregisterPrevious();
      this.registeredNames.clear();
      if (!this.isEnabled()) {
         return;
      }

      ConfigurationSection root = this.config.getConfigurationSection("commands");
      if (root == null) {
         return;
      }

      Map<String, Command> knownCommands = this.knownCommandsMap();
      if (knownCommands == null) {
         this.plugin.getLogger().warning("create-cmd's: Die Befehle konnten nicht angemeldet werden (CommandMap nicht erreichbar).");
         return;
      }

      for (String name : root.getKeys(false)) {
         String key = name.toLowerCase(Locale.ROOT);
         if (!key.matches("[a-z0-9_-]{2,32}")) {
            this.plugin.getLogger().warning("create-cmd's: Ungültiger Befehlsname übersprungen: " + name);
            continue;
         }
         ConfigurationSection section = root.getConfigurationSection(name);
         if (section == null) {
            continue;
         }
         List<String> actions = section.getStringList("actions");
         if (actions.isEmpty()) {
            this.plugin.getLogger().warning("create-cmd's: Befehl /" + key + " hat keine actions und wird übersprungen.");
            continue;
         }
         List<String> aliases = new ArrayList<>();
         for (String rawAlias : section.getStringList("aliases")) {
            String aliasKey = rawAlias == null ? "" : rawAlias.toLowerCase(Locale.ROOT);
            if (aliasKey.matches("[a-z0-9_-]{2,32}")) {
               aliases.add(aliasKey);
            }
         }
         String permission = section.getString("permission", "");
         String description = section.getString("description", "Eigener Befehl.");

         if (this.collides(knownCommands, key)) {
            this.plugin.getLogger().warning("create-cmd's: /" + key + " kollidiert mit einem vorhandenen Befehl und wird nicht angemeldet.");
            continue;
         }
         List<String> freeAliases = new ArrayList<>();
         for (String aliasKey : aliases) {
            if (this.collides(knownCommands, aliasKey)) {
               this.plugin.getLogger().warning("create-cmd's: Alias /" + aliasKey + " von /" + key + " kollidiert mit einem vorhandenen Befehl und wird ausgelassen.");
            } else {
               freeAliases.add(aliasKey);
            }
         }

         DynamicCommand dynamic = new DynamicCommand(key, description, permission, freeAliases, actions);
         knownCommands.put("greenattack-create-cmds:" + key, dynamic);
         knownCommands.put(key, dynamic);
         for (String aliasKey : freeAliases) {
            knownCommands.put(aliasKey, dynamic);
         }
         this.registeredNames.add(key);
      }
   }

   private boolean collides(Map<String, Command> knownCommands, String key) {
      Command existing = knownCommands.get(key);
      return existing != null && !(existing instanceof DynamicCommand);
   }

   private void unregisterPrevious() {
      Map<String, Command> knownCommands = this.knownCommandsMap();
      if (knownCommands == null) {
         return;
      }
      knownCommands.entrySet().removeIf(entry -> entry.getValue() instanceof DynamicCommand || entry.getKey().startsWith("greenattack-create-cmds:"));
   }

   @SuppressWarnings("unchecked")
   private Map<String, Command> knownCommandsMap() {
      try {
         Object commandMap = this.plugin.getServer().getClass().getMethod("getCommandMap").invoke(this.plugin.getServer());
         Field knownCommandsField = null;
         for (Class<?> type = commandMap.getClass(); type != null && knownCommandsField == null; type = type.getSuperclass()) {
            try {
               knownCommandsField = type.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException ignored) {
               // in der Oberklasse weitersuchen
            }
         }
         if (knownCommandsField == null) {
            throw new NoSuchFieldException("knownCommands");
         }
         knownCommandsField.setAccessible(true);
         Object knownCommandsObject = knownCommandsField.get(commandMap);
         return knownCommandsObject instanceof Map<?, ?> ? (Map<String, Command>) knownCommandsObject : null;
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.plugin.getLogger().log(Level.WARNING, "create-cmd's: CommandMap nicht erreichbar", exception);
         return null;
      }
   }

   /** Ein per Config erstellter Befehl: führt beim Aufruf eine oder mehrere Konsolenbefehle aus. */
   private final class DynamicCommand extends Command {
      private final String permission;
      private final List<String> actions;

      DynamicCommand(String name, String description, String permission, List<String> aliases, List<String> actions) {
         super(name);
         this.setDescription(description);
         this.setUsage("/" + name);
         this.setAliases(aliases);
         this.permission = permission == null ? "" : permission.trim();
         this.actions = actions;
      }

      @Override
      public boolean execute(CommandSender sender, String label, String[] args) {
         if (!this.permission.isEmpty() && !CustomCommandsFeature.this.hasFeaturePermission(sender, this.permission) && !sender.hasPermission(this.permission)) {
            CustomCommandsFeature.this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
            return true;
         }
         String playerName = sender instanceof Player player ? player.getName() : "";
         String uuid = sender instanceof Player player ? player.getUniqueId().toString() : "";
         String joinedArgs = String.join(" ", args);
         for (String action : this.actions) {
            String resolved = action.replace("%player%", playerName).replace("%uuid%", uuid).replace("%args%", joinedArgs);
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)) {
               CustomCommandsFeature.this.plugin.getLogger().warning("create-cmd's: Befehl fehlgeschlagen (/" + this.getName() + "): " + resolved);
            }
         }
         return true;
      }

      @Override
      public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
         if (args.length == 0) {
            return List.of();
         }
         return OfflinePlayerNames.suggest(args[args.length - 1]);
      }
   }
}

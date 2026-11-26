package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

public final class SpawnFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String SPAWN_PERMISSION = "system.spawn";
   private static final String OTHER_PERMISSION = "system.spawn.others";
   private static final String SET_PERMISSION = "system.spawn.set";
   private static final String SET_GROUP_PERMISSION = "system.spawn.set.group";
   private final File spawnsFile = this.featureFile("spawns.yml");
   private FileConfiguration spawns;
   private boolean luckPermsWarningLogged;

   public SpawnFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "spawn");
      this.loadSpawns();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public void reloadFeature() {
      this.reload();
      this.loadSpawns();
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Spawn-Feature ist deaktiviert.");
         return true;
      } else if (command.getName().equalsIgnoreCase("setspawn")) {
         this.setSpawn(sender, args);
         return true;
      } else {
         this.goToSpawn(sender, args);
         return true;
      }
   }

   private void goToSpawn(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, "system.spawn")) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
      } else if (args.length > 1) {
         this.sendPath(sender, "messages.spawn-usage", "&7Verwendung: &f/spawn [Spieler]");
      } else {
         boolean other = args.length == 1;
         Player target;
         if (other) {
            if (!this.hasFeaturePermission(sender, "system.spawn.others")) {
               this.sendPath(sender, "messages.no-other-permission", "&cDu darfst andere Spieler nicht teleportieren.");
               return;
            }

            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
               this.send(
                  sender, this.config.getString("messages.player-not-found", "&cSpieler &e%player% &cwurde nicht gefunden.").replace("%player%", args[0])
               );
               return;
            }
         } else {
            if (!(sender instanceof Player player)) {
               this.sendPath(sender, "messages.player-only", "&cDie Konsole muss einen Spieler angeben.");
               return;
            }

            target = player;
         }

         Location spawn = this.findSpawn(target);
         if (spawn == null) {
            this.sendPath(sender, "messages.not-set", "&cDer Spawn wurde noch nicht gesetzt.");
         } else if (!target.teleport(spawn, TeleportCause.COMMAND)) {
            this.sendPath(sender, "messages.teleport-failed", "&cDer Teleport zum Spawn wurde abgebrochen.");
         } else {
            if (other) {
               this.send(
                  sender, this.config.getString("messages.sent-other", "&aDu hast &e%player% &azum Spawn teleportiert.").replace("%player%", target.getName())
               );
               if (this.config.getBoolean("notify-target", true)) {
                  this.send(target, this.config.getString("messages.target-teleported", "&aDu wurdest zum Spawn teleportiert."));
               }
            } else {
               this.send(sender, this.config.getString("messages.teleported", "&aDu wurdest zum Spawn teleportiert."));
            }
         }
      }
   }

   private void setSpawn(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, "system.spawn.set")) {
         this.sendPath(sender, "messages.no-set-permission", "&cDu darfst den Spawn nicht setzen.");
      } else if (sender instanceof Player player) {
         if (args.length > 1) {
            this.sendPath(sender, "messages.setspawn-usage", "&7Verwendung: &f/setspawn [Gruppe]");
         } else {
            String group = "default";
            if (args.length == 1) {
               group = this.normalizeGroup(args[0]);
               if (group == null) {
                  this.sendPath(sender, "messages.invalid-group", "&cDer Gruppenname darf nur Buchstaben, Zahlen, _ und - enthalten.");
                  return;
               }

               if (!group.equals("default") && !this.hasFeaturePermission(sender, "system.spawn.set.group")) {
                  this.sendPath(sender, "messages.no-group-set-permission", "&cDu darfst keine Gruppenspawns setzen.");
                  return;
               }
            }

            Location location = player.getLocation();
            String path = "points." + group;
            this.spawns.set(path + ".world-uuid", location.getWorld().getUID().toString());
            this.spawns.set(path + ".world-name", location.getWorld().getName());
            this.spawns.set(path + ".x", location.getX());
            this.spawns.set(path + ".y", location.getY());
            this.spawns.set(path + ".z", location.getZ());
            this.spawns.set(path + ".yaw", location.getYaw());
            this.spawns.set(path + ".pitch", location.getPitch());

            try {
               File parent = this.spawnsFile.getParentFile();
               if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                  throw new IOException("Spawn-Feature-Ordner konnte nicht erstellt werden.");
               }

               this.spawns.save(this.spawnsFile);
            } catch (IOException exception) {
               this.loadSpawns();
               this.plugin.getLogger().severe("Spawnpunkt konnte nicht gespeichert werden: " + exception.getMessage());
               this.sendPath(sender, "messages.save-failed", "&cDer Spawnpunkt konnte nicht gespeichert werden.");
               return;
            }

            String message = this.config
               .getString("messages.set", "&aSpawn für &e%group% &agesetzt.")
               .replace("%group%", group.equals("default") ? "Standard" : group);
            this.send(sender, message);
         }
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
      }
   }

   private Location findSpawn(Player player) {
      for (String configuredPlayer : this.config.getStringList("player-priority")) {
         String playerName = configuredPlayer == null ? "" : configuredPlayer.trim();
         if (!playerName.isEmpty() && playerName.equalsIgnoreCase(player.getName())) {
            String spawnKey = this.normalizeGroup(playerName);
            if (spawnKey != null) {
               Location playerSpawn = this.readSpawn(spawnKey);
               if (playerSpawn != null) {
                  return playerSpawn;
               }
            }
         }
      }

      List<String> priorities = this.config.getStringList("group-priority");
      Set<String> luckPermsGroups = priorities.isEmpty() ? Set.of() : this.getLuckPermsGroups(player);

      for (String configuredGroup : priorities) {
         String group = this.normalizeGroup(configuredGroup);
         if (group != null
            && !group.equals("default")
            && (
               luckPermsGroups.contains(group)
                  || this.hasFeaturePermission(player, "system.spawn.group." + group)
                  || this.hasFeaturePermission(player, "system.spawn.group.*")
            )) {
            Location groupSpawn = this.readSpawn(group);
            if (groupSpawn != null) {
               return groupSpawn;
            }
         }
      }

      return this.readSpawn("default");
   }

   private Set<String> getLuckPermsGroups(Player player) {
      Plugin luckPerms = this.plugin.getServer().getPluginManager().getPlugin("LuckPerms");
      if (luckPerms != null && luckPerms.isEnabled()) {
         try {
            ClassLoader loader = luckPerms.getClass().getClassLoader();
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader);
            Object api = provider.getMethod("get").invoke(null);
            Object userManager = invokeApi(api, "getUserManager");
            Object user = invokeApi(userManager, "getUser", player.getUniqueId());
            if (user == null) {
               return Set.of();
            }

            Object contextManager = invokeApi(api, "getContextManager");
            Class<?> contextManagerApi = Class.forName("net.luckperms.api.context.ContextManager", true, loader);
            Object queryOptions = contextManagerApi.getMethod("getQueryOptions", Object.class).invoke(contextManager, player);
            if (invokeApi(user, "getInheritedGroups", queryOptions) instanceof Iterable<?> groups) {
               Set<String> result = new HashSet<>();

               for (Object group : groups) {
                  if (invokeApi(group, "getName") instanceof String groupName) {
                     result.add(groupName.toLowerCase(Locale.ROOT));
                  }
               }

               return result;
            } else {
               return Set.of();
            }
         } catch (ReflectiveOperationException | RuntimeException exception) {
            this.warnLuckPermsUnavailable("LuckPerms-Gruppen konnten nicht gelesen werden: " + exception.getMessage());
            return Set.of();
         }
      } else {
         this.warnLuckPermsUnavailable("LuckPerms ist nicht aktiv");
         return Set.of();
      }
   }

   private void warnLuckPermsUnavailable(String reason) {
      if (!this.luckPermsWarningLogged) {
         this.luckPermsWarningLogged = true;
         this.plugin.getLogger().warning(reason + ". group-priority wird zusätzlich über system.spawn.group.<gruppe> geprüft.");
      }
   }

   private static Object invokeApi(Object receiver, String name, Object... args) throws ReflectiveOperationException {
      if (receiver == null) {
         throw new NoSuchMethodException("LuckPerms API receiver fehlt: " + name);
      } else {
         Method method = findApiMethod(receiver.getClass(), name, args);
         if (method == null) {
            throw new NoSuchMethodException("LuckPerms API-Methode fehlt: " + name);
         } else {
            return method.invoke(receiver, args);
         }
      }
   }

   private static Method findApiMethod(Class<?> type, String name, Object[] args) {
      if (type.isInterface()) {
         Method method = matchingApiMethod(type.getMethods(), name, args);
         if (method != null) {
            return method;
         }

         for (Class<?> parent : type.getInterfaces()) {
            method = findApiMethod(parent, name, args);
            if (method != null) {
               return method;
            }
         }

         return null;
      } else {
         for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> apiInterface : current.getInterfaces()) {
               Method method = findApiMethod(apiInterface, name, args);
               if (method != null) {
                  return method;
               }
            }
         }

         for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method method = matchingApiMethod(current.getMethods(), name, args);
            if (method != null) {
               return method;
            }
         }

         return null;
      }
   }

   private static Method matchingApiMethod(Method[] methods, String name, Object[] args) {
      for (Method method : methods) {
         if (method.getName().equals(name) && method.getParameterCount() == args.length) {
            Class<?>[] parameters = method.getParameterTypes();
            boolean compatible = true;

            for (int index = 0; index < args.length; index++) {
               if (args[index] != null && !parameters[index].isAssignableFrom(args[index].getClass())) {
                  compatible = false;
                  break;
               }
            }

            if (compatible) {
               return method;
            }
         }
      }

      return null;
   }

   private Location readSpawn(String group) {
      String path = "points." + group;
      String worldUuid = this.spawns.getString(path + ".world-uuid");
      World world = null;
      if (worldUuid != null) {
         try {
            world = Bukkit.getWorld(UUID.fromString(worldUuid));
         } catch (IllegalArgumentException var6) {
         }
      }

      if (world == null) {
         String worldName = this.spawns.getString(path + ".world-name");
         if (worldName != null) {
            world = Bukkit.getWorld(worldName);
         }
      }

      return world != null && this.spawns.contains(path + ".x") && this.spawns.contains(path + ".y") && this.spawns.contains(path + ".z")
         ? new Location(
            world,
            this.spawns.getDouble(path + ".x"),
            this.spawns.getDouble(path + ".y"),
            this.spawns.getDouble(path + ".z"),
            (float)this.spawns.getDouble(path + ".yaw"),
            (float)this.spawns.getDouble(path + ".pitch")
         )
         : null;
   }

   private String normalizeGroup(String input) {
      String group = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
      return group.matches("[a-z0-9_-]+") ? group : null;
   }

   private void loadSpawns() {
      this.spawns = YamlConfiguration.loadConfiguration(this.spawnsFile);
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      if (this.isEnabled() && !this.hasFeaturePermission(player, "system.spawn.admin")) {
         Bukkit.getScheduler().runTask(this.plugin, () -> this.teleportQuietly(player, TeleportCause.PLUGIN));
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onPlayerRespawn(PlayerRespawnEvent event) {
      if (this.isEnabled() && this.config.getBoolean("respawn-at-spawn", false)) {
         Location spawn = this.findSpawn(event.getPlayer());
         if (spawn != null) {
            event.setRespawnLocation(spawn);
         }
      }
   }

   private void teleportQuietly(Player player, TeleportCause cause) {
      if (player.isOnline() && this.isEnabled()) {
         Location spawn = this.findSpawn(player);
         if (spawn != null) {
            player.teleport(spawn, cause);
         }
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.isEnabled()) {
         return List.of();
      } else {
         String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
         if (command.getName().equalsIgnoreCase("spawn") && args.length == 1 && this.hasFeaturePermission(sender, "system.spawn.others")) {
            return this.plugin
               .getServer()
               .getOnlinePlayers()
               .stream()
               .<String>map(Player::getName)
               .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
               .toList();
         } else if (command.getName().equalsIgnoreCase("setspawn") && args.length == 1 && this.hasFeaturePermission(sender, "system.spawn.set.group")) {
            List<String> groups = new ArrayList<>(this.config.getStringList("group-priority"));
            groups.addAll(this.config.getStringList("player-priority"));
            groups.add("default");
            return groups.stream().distinct().filter(group -> group.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
         } else {
            return List.of();
         }
      }
   }
}

package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.persistence.PersistentDataType;

public final class FlyFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String USE_PERMISSION = "system.fly.use";
   private static final String LEGACY_USE_PERMISSION = "system.fly";
   private static final String OTHER_PERMISSION = "system.fly.other";
   private static final String ADMIN_PERMISSION = "system.fly.admin";
   private final NamespacedKey personalFlightKey;

   public FlyFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "fly");
      this.personalFlightKey = new NamespacedKey(plugin, "personal_fly");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Fly-Feature ist deaktiviert.");
         return true;
      }

      if (args.length == 0) {
         if (!(sender instanceof Player player)) {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         }
         if (!this.hasSelfPermission(sender)) {
            this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
            return true;
         }
         this.toggleFly(sender, player, false);
         return true;
      }

      if (args.length != 1) {
         this.sendPath(sender, "messages.usage-other", "&7Verwendung: &f/fly &7oder &f/fly <Spieler>");
         return true;
      }
      if (!this.hasAnyPermission(sender, OTHER_PERMISSION, ADMIN_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return true;
      }

      Player target = Bukkit.getPlayerExact(args[0]);
      if (target == null) {
         this.sendPath(sender, "messages.player-not-found", "&cDieser Spieler ist auf diesem Server nicht online.");
         return true;
      }
      this.toggleFly(sender, target, true);
      return true;
   }

   private boolean hasAnyPermission(CommandSender sender, String first, String second) {
      return this.hasFeaturePermission(sender, first) || this.hasFeaturePermission(sender, second);
   }

   private boolean hasSelfPermission(CommandSender sender) {
      return this.hasAnyPermission(sender, USE_PERMISSION, ADMIN_PERMISSION)
         || this.hasFeaturePermission(sender, LEGACY_USE_PERMISSION);
   }

   private void toggleFly(CommandSender sender, Player target, boolean otherPlayer) {
      if (this.isWorldBlocked(target) && !this.isPersonalFlyEnabled(target)) {
         this.sendPath(sender, "messages.world-blocked", "&cFliegen ist in dieser Welt deaktiviert.");
         if (otherPlayer && !sender.equals(target)) {
            this.sendPath(target, "messages.world-blocked", "&cFliegen ist in dieser Welt deaktiviert.");
         }
         return;
      }

      if (!this.isAllowedGameMode(target.getGameMode())) {
         this.sendPath(sender, "messages.blocked-mode", "&c/fly ist nur im Überlebens- oder Abenteuermodus verfügbar.");
         return;
      }

      if (this.isPersonalFlyEnabled(target)) {
         target.getPersistentDataContainer().remove(this.personalFlightKey);
         if (this.plugin.isFlyBoosterActive()) {
            this.sendPath(target, "messages.disabled-booster-active", "&eDein persönliches /fly ist ausgeschaltet; der Fly Booster gewährt aber weiterhin Flug.");
         } else {
            target.setFlying(false);
            target.setAllowFlight(false);
            this.sendPath(target, "messages.disabled-fly", "&cFliegen wurde ausgeschaltet.");
         }
         if (otherPlayer) {
            this.send(sender, this.config.getString("messages.disabled-other", "&cFly wurde für &e%player% &causgeschaltet.").replace("%player%", target.getName()));
         }
      } else {
         target.getPersistentDataContainer().set(this.personalFlightKey, PersistentDataType.BYTE, (byte)1);
         target.setAllowFlight(true);
         this.sendPath(target, "messages.enabled-fly", "&aFliegen wurde eingeschaltet.");
         if (otherPlayer) {
            this.send(sender, this.config.getString("messages.enabled-other", "&aFly wurde für &e%player% &aeingeschaltet.").replace("%player%", target.getName()));
         }
      }
   }

   public boolean isPersonalFlyEnabled(Player player) {
      Byte value = player.getPersistentDataContainer().get(this.personalFlightKey, PersistentDataType.BYTE);
      return this.isEnabled() && value != null && value == 1;
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerJoin(PlayerJoinEvent event) {
      this.restoreFlightAfterJoin(event.getPlayer());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onGameModeChange(PlayerGameModeChangeEvent event) {
      if (this.isAllowedGameMode(event.getNewGameMode())) {
         this.restoreFlightAfterJoin(event.getPlayer());
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
      this.restoreFlightAfterJoin(event.getPlayer());
   }

   private void restoreFlightAfterJoin(Player player) {
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
         if (player.isOnline() && this.isEnabled() && this.isAllowedGameMode(player.getGameMode())) {
            if (this.isWorldBlocked(player)) {
               boolean hadFlight = player.getAllowFlight() || player.isFlying();
               if (player.isFlying()) {
                  player.setFlying(false);
               }
               player.setAllowFlight(false);
               if (hadFlight) {
                  this.sendPath(player, "messages.world-blocked", "&cFliegen ist in dieser Welt deaktiviert.");
               }
            } else if (this.isPersonalFlyEnabled(player) || this.plugin.isFlyBoosterActive()) {
               player.setAllowFlight(true);
            }
         }
      });
   }

   private boolean isWorldBlocked(Player player) {
      if (this.hasFeaturePermission(player, ADMIN_PERMISSION)) {
         return false;
      }

      String worldName = player.getWorld().getName();
      for (String blockedWorld : this.config.getStringList("blocked-worlds")) {
         if (blockedWorld != null && worldName.equalsIgnoreCase(blockedWorld.trim())) {
            return true;
         }
      }
      return false;
   }

   private boolean isAllowedGameMode(GameMode gameMode) {
      return gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length != 1 || !this.hasAnyPermission(sender, OTHER_PERMISSION, ADMIN_PERMISSION)) {
         return List.of();
      }
      String prefix = args[0].toLowerCase(Locale.ROOT);
      return Bukkit.getOnlinePlayers().stream()
         .map(Player::getName)
         .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
         .sorted(String.CASE_INSENSITIVE_ORDER)
         .toList();
   }
}

package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class GamemodeFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   private static final String BASE_PERMISSION = "system.gamemode";
   private static final String OTHER_PERMISSION = "system.gamemode.other";
   private static final Map<String, GameMode> SHORTCUTS = Map.of(
      "gms",
      GameMode.SURVIVAL,
      "gm0",
      GameMode.SURVIVAL,
      "gmc",
      GameMode.CREATIVE,
      "gm1",
      GameMode.CREATIVE,
      "gma",
      GameMode.ADVENTURE,
      "gm2",
      GameMode.ADVENTURE,
      "gmsp",
      GameMode.SPECTATOR,
      "gm3",
      GameMode.SPECTATOR
   );

   public GamemodeFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "gamemode");
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Gamemode-Feature ist deaktiviert.");
         return true;
      }

      GameMode mode = SHORTCUTS.get(label.toLowerCase(Locale.ROOT));
      int targetIndex;
      if (mode == null) {
         if (args.length < 1 || args.length > 2) {
            this.sendPath(sender, "messages.usage", "&7Verwendung: &f/gamemode <survival|creative|adventure|spectator> [Spieler]");
            return true;
         }

         mode = this.parseMode(args[0]);
         targetIndex = 1;
         if (mode == null) {
            this.sendPath(sender, "messages.invalid-mode", "&cUnbekannter Spielmodus. Nutze survival, creative, adventure oder spectator.");
            return true;
         }
      } else {
         if (args.length > 1) {
            this.send(sender, this.config.getString("messages.shortcut-usage", "&7Verwendung: &f/%label% [Spieler]").replace("%label%", label));
            return true;
         }

         targetIndex = 0;
      }

      boolean otherTarget = args.length > targetIndex;
      Player target;
      if (otherTarget) {
         if (!this.hasFeaturePermission(sender, "system.gamemode.other")) {
            this.sendPath(sender, "messages.no-other-permission", "&cDu darfst den Spielmodus anderer Spieler nicht ändern.");
            return true;
         }

         target = this.plugin.getServer().getPlayer(args[targetIndex]);
         if (target == null) {
            this.send(
               sender,
               this.config.getString("messages.player-not-found", "&cDer Spieler &e%player% &cist nicht online.").replace("%player%", args[targetIndex])
            );
            return true;
         }
      } else {
         if (!(sender instanceof Player player)) {
            this.sendPath(sender, "messages.player-only", "&cDie Konsole muss einen Spieler angeben.");
            return true;
         }

         target = player;
      }

      if (!this.canUseMode(sender, mode)) {
         this.sendPath(sender, "messages.no-permission", "&cDu darfst diesen Spielmodus nicht setzen.");
         return true;
      }

      target.setGameMode(mode);
      String modeName = this.modeName(mode);
      if (otherTarget) {
         this.send(
            sender,
            this.config
               .getString("messages.changed-other", "&aSpielmodus von &e%player% &aauf &e%mode% &agesetzt.")
               .replace("%player%", target.getName())
               .replace("%mode%", modeName)
         );
         if (this.config.getBoolean("notify-target", true)) {
            this.send(target, this.config.getString("messages.target-changed", "&aDein Spielmodus wurde auf &e%mode% &agesetzt.").replace("%mode%", modeName));
         }
      } else {
         this.send(sender, this.config.getString("messages.changed-self", "&aDein Spielmodus wurde auf &e%mode% &agesetzt.").replace("%mode%", modeName));
      }

      return true;
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.isEnabled()) {
         return List.of();
      }

      GameMode shortcutMode = SHORTCUTS.get(alias.toLowerCase(Locale.ROOT));
      if (shortcutMode != null) {
         return args.length == 1 && this.hasFeaturePermission(sender, "system.gamemode.other") && this.canUseMode(sender, shortcutMode)
            ? this.onlinePlayerNames(args[0])
            : List.of();
      }

      if (args.length == 1) {
         String partial = args[0].toLowerCase(Locale.ROOT);
         return Arrays.stream(GameMode.values())
            .filter(this::isSupportedMode)
            .filter(modex -> this.canUseMode(sender, modex))
            .map(modex -> modex.name().toLowerCase(Locale.ROOT))
            .filter(name -> name.startsWith(partial))
            .collect(Collectors.toList());
      }

      if (args.length == 2) {
         GameMode mode = this.parseMode(args[0]);
         if (mode != null && this.hasFeaturePermission(sender, "system.gamemode.other") && this.canUseMode(sender, mode)) {
            return this.onlinePlayerNames(args[1]);
         }
      }

      return List.of();
   }

   private List<String> onlinePlayerNames(String partial) {
      String lower = partial.toLowerCase(Locale.ROOT);
      return this.plugin
         .getServer()
         .getOnlinePlayers()
         .stream()
         .<String>map(Player::getName)
         .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
         .sorted(String.CASE_INSENSITIVE_ORDER)
         .collect(Collectors.toList());
   }

   private boolean canUseMode(CommandSender sender, GameMode mode) {
      return this.hasFeaturePermission(sender, "system.gamemode")
         || this.hasFeaturePermission(sender, "system.gamemode." + mode.name().toLowerCase(Locale.ROOT));
   }

   private boolean isSupportedMode(GameMode mode) {
      return mode == GameMode.SURVIVAL || mode == GameMode.CREATIVE || mode == GameMode.ADVENTURE || mode == GameMode.SPECTATOR;
   }

   private GameMode parseMode(String raw) {
      return switch (raw.toLowerCase(Locale.ROOT)) {
         case "survival", "s", "0" -> GameMode.SURVIVAL;
         case "creative", "c", "1" -> GameMode.CREATIVE;
         case "adventure", "a", "2" -> GameMode.ADVENTURE;
         case "spectator", "sp", "3" -> GameMode.SPECTATOR;
         default -> null;
      };
   }

   private String modeName(GameMode mode) {
      return this.config.getString("mode-names." + mode.name().toLowerCase(Locale.ROOT), mode.name());
   }
}

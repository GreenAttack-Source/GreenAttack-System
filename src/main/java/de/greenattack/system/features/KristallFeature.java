package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class KristallFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   private static final List<String> ACTIONS = List.of("give", "set", "take", "reset");
   private static final List<String> AMOUNT_SUGGESTIONS = List.of("1", "10", "64", "100", "1000", "1k", "10k", "1m");
   private static final List<String> SET_AMOUNT_SUGGESTIONS = List.of("0", "1", "10", "64", "100", "1000", "1k", "10k", "1m");
   private static final String LEGACY_PERMISSION = "kristallsystem.admin";
   private final File balanceFile = this.featureFile("balances.yml");
   private final YamlConfiguration balances = YamlConfiguration.loadConfiguration(this.balanceFile);

   public KristallFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "kristall");
      this.migrateLegacyBalances();
   }

   public synchronized long getBalance(UUID playerId) {
      return Math.max(0L, this.balances.getLong(playerId.toString(), 0L));
   }

   public synchronized void applySyncedBalance(UUID playerId, long amount) throws IOException {
      if (amount < 0L) throw new IllegalArgumentException("Kristallbetrag darf nicht negativ sein.");
      this.setBalance(playerId, amount);
   }

   private synchronized void setBalance(UUID playerId, long amount) throws IOException {
      String key = playerId.toString();
      Object previous = this.balances.get(key);
      this.balances.set(key, amount);

      try {
         File parent = this.balanceFile.getParentFile();
         if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Plugin-Datenordner konnte nicht erstellt werden.");
         }

         this.balances.save(this.balanceFile);
      } catch (IOException exception) {
         this.balances.set(key, previous);
         throw exception;
      }
   }

   public synchronized boolean withdrawBalance(UUID playerId, long amount) throws IOException {
      if (amount < 0L) {
         throw new IllegalArgumentException("Kristallbetrag darf nicht negativ sein.");
      }

      long previous = this.getBalance(playerId);
      if (previous < amount) {
         return false;
      }

      this.setBalance(playerId, previous - amount);
      return true;
   }

   public synchronized void depositBalance(UUID playerId, long amount) throws IOException {
      if (amount < 0L) {
         throw new IllegalArgumentException("Kristallbetrag darf nicht negativ sein.");
      }

      this.setBalance(playerId, Math.addExact(this.getBalance(playerId), amount));
   }

   private void migrateLegacyBalances() {
      this.importBalances(new File(this.plugin.getDataFolder(), "kristall-balances.yml"), "alten GreenAttack-System-Datei");
      File pluginFolder = this.plugin.getDataFolder().getParentFile();
      if (pluginFolder != null) {
         File legacyFile = new File(new File(pluginFolder, "KristallSystem"), "balances.yml");
         this.importBalances(legacyFile, "alten KristallSystem-Plugin");
      }
   }

   private void importBalances(File legacyFile, String sourceName) {
      if (legacyFile.isFile() && !legacyFile.equals(this.balanceFile)) {
         YamlConfiguration legacy = YamlConfiguration.loadConfiguration(legacyFile);
         int imported = 0;

         for (String key : legacy.getKeys(false)) {
            try {
               UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
               continue;
            }

            if (!this.balances.contains(key)) {
               long amount = legacy.getLong(key, 0L);
               if (amount >= 0L) {
                  this.balances.set(key, amount);
                  imported++;
               }
            }
         }

         if (imported != 0) {
            try {
               File parent = this.balanceFile.getParentFile();
               if (parent != null && !parent.exists() && !parent.mkdirs()) {
                  throw new IOException("Kristall-Feature-Ordner konnte nicht erstellt werden.");
               }

               this.balances.save(this.balanceFile);
               this.plugin
                  .getLogger()
                  .info(imported + " Kristallkontostände aus der " + sourceName + " übernommen. Die Quelldatei wurde zur Sicherheit beibehalten.");
            } catch (IOException exception) {
               this.plugin.getLogger().severe("Kristallkontostände aus der " + sourceName + " konnten nicht gespeichert werden: " + exception.getMessage());
            }
         }
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Kristall-Feature ist deaktiviert.");
         return true;
      }

      if (!this.hasAdminPermission(sender)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Berechtigung.");
         return true;
      }

      String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      if (!ACTIONS.contains(action)) {
         this.sendUsage(sender);
         return true;
      }

      if (action.equals("reset")) {
         if (args.length != 2) {
            this.sendUsage(sender);
            return true;
         } else {
            this.resetBalance(sender, args[1]);
            return true;
         }
      } else {
         if (args.length != 3) {
            this.sendUsage(sender);
            return true;
         }

         String targetName = args[1];

         long amount;
         try {
            amount = CompactAmountParser.parse(args[2]).longValueExact();
         } catch (NumberFormatException | ArithmeticException exception) {
            this.sendPath(sender, "messages.invalid-amount", "&cBitte gib eine positive ganze Zahl an, z.B. 1000, 1k oder 1m.");
            return true;
         }

         if (amount < 0L || amount == 0L && !action.equals("set")) {
            if (action.equals("set")) {
               this.sendPath(sender, "messages.invalid-set-amount", "&cFür set muss die Anzahl mindestens 0 sein.");
            } else {
               this.sendPath(sender, "messages.invalid-amount", "&cBitte gib eine positive ganze Zahl an.");
            }

            return true;
         } else {
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
               this.send(sender, this.formattedText("messages.player-not-found", "&cSpieler &e%player% &cwurde nicht gefunden.", "%player%", targetName));
               return true;
            }

            long previous = this.getBalance(target.getUniqueId());
            long updated;
            switch (action) {
               case "give":
                  try {
                     updated = Math.addExact(previous, amount);
                     break;
                  } catch (ArithmeticException exception) {
                     this.sendPath(sender, "messages.balance-overflow", "&cDer Kristallkontostand wäre zu hoch.");
                     return true;
                  }
               case "set":
                  updated = amount;
                  break;
               case "take":
                  if (amount > previous) {
                     this.send(
                        sender,
                        this.formattedText("messages.not-enough", "&cDer Spieler besitzt nur &e%balance% &cKristalle.", "%balance%", Long.toString(previous))
                     );
                     return true;
                  }

                  updated = previous - amount;
                  break;
               default:
                  this.sendPath(sender, "messages.usage", "&7Verwendung: &f/ka <give|set|take> <Spieler> <Anzahl>");
                  return true;
            }

            try {
               this.setBalance(target.getUniqueId(), updated);
            } catch (IOException exception) {
               this.plugin.getLogger().severe("Kristallkontostand für " + target.getName() + " konnte nicht gespeichert werden: " + exception.getMessage());
               this.sendPath(sender, "messages.save-failed", "&cDer Kristallkontostand konnte nicht gespeichert werden.");
               return true;
            }

            this.send(
               sender,
               this.formattedText(
                  "messages.updated",
                  "&aKristallkonto von &e%player% &aauf &e%balance% &aKristalle gesetzt.",
                  "%player%",
                  target.getName(),
                  "%balance%",
                  Long.toString(updated)
               )
            );
            return true;
         }
      }
   }

   private void resetBalance(CommandSender sender, String targetName) {
      OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
      if (!target.hasPlayedBefore() && !target.isOnline()) {
         this.send(sender, this.formattedText("messages.player-not-found", "&cSpieler &e%player% &cwurde nicht gefunden.", "%player%", targetName));
      } else {
         String displayName = target.getName() == null ? targetName : target.getName();

         try {
            this.setBalance(target.getUniqueId(), 0L);
         } catch (IOException exception) {
            this.plugin.getLogger().severe("Kristallkontostand für " + displayName + " konnte nicht zurückgesetzt werden: " + exception.getMessage());
            this.sendPath(sender, "messages.save-failed", "&cDer Kristallkontostand konnte nicht gespeichert werden.");
            return;
         }

         this.send(
            sender, this.formattedText("messages.reset", "&aKristallkonto von &e%player% &awurde auf &e0 &aKristalle zurückgesetzt.", "%player%", displayName)
         );
      }
   }

   private void sendUsage(CommandSender sender) {
      String fallback = "&7Verwendung: &f/ka|/kristall|/kristalle <give|set|take> <Spieler> <Anzahl> &7oder &f/ka|/kristall|/kristalle reset <Spieler>";
      String configured = this.config.getString("messages.usage", fallback);
      if (configured == null || !configured.toLowerCase(Locale.ROOT).contains("reset")) {
         configured = fallback;
      }

      this.send(sender, configured);
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (this.isEnabled() && this.hasAdminPermission(sender)) {
         String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
         if (args.length == 1) {
            return this.matching(ACTIONS, partial);
         }

         if (args.length == 2) {
            return this.matching(OfflinePlayerNames.suggest(partial), partial);
         } else {
            if (args.length == 3) {
               String action = args[0].toLowerCase(Locale.ROOT);
               if (action.equals("set")) {
                  return this.matching(SET_AMOUNT_SUGGESTIONS, partial);
               }

               if (action.equals("give") || action.equals("take")) {
                  return this.matching(AMOUNT_SUGGESTIONS, partial);
               }
            }

            return List.of();
         }
      } else {
         return List.of();
      }
   }

   private boolean hasAdminPermission(CommandSender sender) {
      String configured = this.config.getString("permissions.admin", "system.kristall.admin");
      return this.hasFeaturePermission(sender, configured) || this.hasFeaturePermission(sender, "kristallsystem.admin");
   }

   private List<String> matching(List<String> candidates, String partial) {
      return candidates.stream().filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
   }

   private String formattedText(String path, String fallback, String... replacements) {
      String value = this.config.getString(path, fallback);

      for (int index = 0; index + 1 < replacements.length; index += 2) {
         value = value.replace(replacements[index], replacements[index + 1]);
      }

      return color(value);
   }
}

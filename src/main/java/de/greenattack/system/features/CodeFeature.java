package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Code-System: Spieler lösen mit /code <Name> genau EINEN Code ein (egal welchen).
 *
 * Befehle: /code, /codecreate, /codeconfig, /codedelete, /codereset, /codetop.
 * Speicher: lokale Dateien (Standard) oder MySQL/MariaDB (database.enabled in features/codes/config.yml).
 * Alle Speicherzugriffe laufen außerhalb des Server-Threads.
 */
public final class CodeFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   private static final String USE_PERMISSION = "system.code.use";
   private static final String ADMIN_PERMISSION = "system.code.admin";
   private static final String TOP_PERMISSION = "system.code.top";
   private static final String CODE_PATTERN = "[A-Za-z0-9_-]{2,32}";
   private static final String REWARD_SEPARATOR = " ;; ";

   private final ThreadPoolExecutor worker;
   private final Object storageLock = new Object();
   private Storage storage;
   private String storageSignature = "";
   private volatile List<String> knownCodes = List.of();

   public CodeFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "codes");
      this.worker = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
         Thread thread = new Thread(runnable, "GreenAttack-Codes");
         thread.setDaemon(true);
         return thread;
      });
      this.worker.allowCoreThreadTimeOut(true);

      try {
         if (ConfigDefaults.merge(plugin, this.config, "features/codes/config.yml")) {
            this.save();
            plugin.getLogger().info("Code-System: fehlende Einträge (z. B. database) wurden in features/codes/config.yml ergänzt.");
         }
         for (String name : new String[] {"code", "codecreate", "codeconfig", "codedelete", "codereset", "codetop"}) {
            PluginCommand command = plugin.getCommand(name);
            if (command == null) {
               plugin.getLogger().warning("Befehl /" + name + " fehlt in der plugin.yml, Code-System ist unvollständig.");
            } else {
               command.setExecutor(this);
               command.setTabCompleter(this);
            }
         }
         this.refreshKnownCodes();
      } catch (Throwable throwable) {
         plugin.getLogger().log(Level.SEVERE, "Code-System konnte nicht vollständig gestartet werden", throwable);
      }
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      this.reload();
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Code-System ist derzeit deaktiviert.");
         return true;
      }

      switch (command.getName().toLowerCase(Locale.ROOT)) {
         case "code":
            this.handleRedeem(sender, args);
            break;
         case "codecreate":
            this.handleCreate(sender, args);
            break;
         case "codeconfig":
            this.handleConfig(sender, args);
            break;
         case "codedelete":
            this.handleDelete(sender, args);
            break;
         case "codereset":
            this.handleReset(sender, args);
            break;
         case "codetop":
            this.handleTop(sender);
            break;
         default:
            return false;
      }
      return true;
   }

   // ------------------------------------------------------------------ /code

   private void handleRedeem(CommandSender sender, String[] args) {
      if (!(sender instanceof Player player)) {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl kann nur ein Spieler verwenden.");
         return;
      }
      if (!this.hasFeaturePermission(sender, USE_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      if (args.length != 1) {
         this.sendPath(sender, "messages.usage", "&7Verwendung: &f/code <Code>");
         return;
      }

      final UUID id = player.getUniqueId();
      final String playerName = player.getName();
      final String typed = args[0];
      final String key = typed.toLowerCase(Locale.ROOT);
      this.async(sender, storage -> {
         Redemption previous = storage.getRedemption(id);
         if (previous != null) {
            return new RedeemResult(RedeemStatus.ALREADY, previous.displayName, null);
         }
         CodeRecord code = storage.getCode(key);
         if (code == null) {
            return new RedeemResult(RedeemStatus.UNKNOWN, typed, null);
         }
         if (!storage.redeem(id, playerName, code.key, code.displayName)) {
            Redemption winner = storage.getRedemption(id);
            return new RedeemResult(RedeemStatus.ALREADY, winner == null ? code.displayName : winner.displayName, null);
         }
         return new RedeemResult(RedeemStatus.OK, code.displayName, code);
      }, result -> {
         switch (result.status) {
            case ALREADY:
               this.send(sender, this.message("messages.already-used", "&cDu hast bereits einen Code eingelöst. Es kann nur ein Code pro Spieler genutzt werden.")
                  .replace("%code%", result.display));
               break;
            case UNKNOWN:
               this.send(sender, this.message("messages.unknown-code", "&cDen Code &e%code% &cgibt es nicht.").replace("%code%", result.display));
               break;
            default:
               for (String reward : result.code.rewardCommands()) {
                  String commandLine = reward.replace("%player%", playerName).replace("%uuid%", id.toString());
                  if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine)) {
                     this.plugin.getLogger().warning("Code-Belohnung fehlgeschlagen (Code " + result.display + ", Spieler " + playerName + "): " + commandLine);
                  }
               }
               this.send(sender, this.message("messages.redeemed", "&aDu hast den Code &e%code% &aeingelöst!").replace("%code%", result.display));
               break;
         }
      });
   }

   // ------------------------------------------------------------------ /codecreate

   private void handleCreate(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      if (args.length < 1) {
         this.sendPath(sender, "messages.usage-create", "&7Verwendung: &f/codecreate <Code> [/Befehl mit %player%, mehrere mit ;; trennen]");
         return;
      }
      if (!args[0].matches(CODE_PATTERN)) {
         this.sendPath(sender, "messages.invalid-code", "&cCodes dürfen nur aus Buchstaben, Zahlen, _ und - bestehen (2 bis 32 Zeichen).");
         return;
      }

      String rewardText = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
      if (hasIllegalChars(rewardText)) {
         this.sendPath(sender, "messages.invalid-reward", "&cDie Belohnung enthält ungültige Zeichen.");
         return;
      }

      final String typed = args[0];
      final CodeRecord record = new CodeRecord(typed.toLowerCase(Locale.ROOT), typed, String.join(REWARD_SEPARATOR, parseCommands(rewardText)),
         sender.getName(), System.currentTimeMillis(), 0);
      this.async(sender, storage -> storage.createCode(record), created -> {
         if (created) {
            this.send(sender, this.message("messages.created", "&aCode &e%code% &awurde erstellt.").replace("%code%", typed));
            this.refreshKnownCodes();
         } else {
            this.send(sender, this.message("messages.already-exists", "&cDen Code &e%code% &cgibt es bereits.").replace("%code%", typed));
         }
      });
   }

   // ------------------------------------------------------------------ /codeconfig

   private void handleConfig(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      if (args.length < 1) {
         this.sendPath(sender, "messages.usage-config", "&7Verwendung: &f/codeconfig <Code> [add <Befehl> | remove <Nummer oder Befehl> | set <Nummer> <Befehl>]");
         return;
      }

      final String typed = args[0];
      final String key = typed.toLowerCase(Locale.ROOT);

      if (args.length == 1) {
         this.async(sender, storage -> storage.getCode(key), code -> {
            if (code == null) {
               this.send(sender, this.message("messages.unknown-code", "&cDen Code &e%code% &cgibt es nicht.").replace("%code%", typed));
            } else {
               this.sendRewardList(sender, code.displayName, code.rewardCommands());
            }
         });
         return;
      }

      final String action = args[1].toLowerCase(Locale.ROOT);
      final List<String> newCommands;
      final String removeTarget;
      final int setIndex;
      switch (action) {
         case "add":
            if (args.length < 3) {
               this.sendPath(sender, "messages.usage-config-add", "&7Verwendung: &f/codeconfig <Code> add <Befehl mit %player%>");
               return;
            }
            newCommands = parseCommands(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            removeTarget = null;
            setIndex = -1;
            break;
         case "remove":
            if (args.length < 3) {
               this.sendPath(sender, "messages.usage-config-remove", "&7Verwendung: &f/codeconfig <Code> remove <Nummer oder Befehl>");
               return;
            }
            newCommands = List.of();
            removeTarget = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            setIndex = -1;
            break;
         case "set":
            if (args.length < 4 || parseIndex(args[2]) == null) {
               this.sendPath(sender, "messages.usage-config-set", "&7Verwendung: &f/codeconfig <Code> set <Nummer> <Befehl mit %player%>");
               return;
            }
            newCommands = parseCommands(String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
            removeTarget = null;
            setIndex = parseIndex(args[2]);
            break;
         default:
            this.sendPath(sender, "messages.usage-config", "&7Verwendung: &f/codeconfig <Code> [add <Befehl> | remove <Nummer oder Befehl> | set <Nummer> <Befehl>]");
            return;
      }

      if (!action.equals("remove") && (newCommands.isEmpty() || hasIllegalChars(String.join(" ", newCommands)))) {
         this.sendPath(sender, "messages.invalid-reward", "&cDie Belohnung enthält ungültige Zeichen.");
         return;
      }

      this.async(sender, storage -> {
         CodeRecord code = storage.getCode(key);
         if (code == null) {
            return new ConfigResult(ConfigStatus.UNKNOWN_CODE, typed, List.of(), "");
         }

         List<String> rewards = new ArrayList<>(code.rewardCommands());
         String detail;
         switch (action) {
            case "add":
               rewards.addAll(newCommands);
               detail = String.join(" ;; ", newCommands);
               break;
            case "remove": {
               Integer number = parseIndex(removeTarget);
               int position = -1;
               if (number != null) {
                  if (number < 1 || number > rewards.size()) {
                     return new ConfigResult(ConfigStatus.BAD_NUMBER, code.displayName, rewards, Integer.toString(number));
                  }
                  position = number - 1;
               } else {
                  String wanted = parseCommands(removeTarget).isEmpty() ? "" : parseCommands(removeTarget).get(0);
                  for (int index = 0; index < rewards.size(); index++) {
                     if (rewards.get(index).equalsIgnoreCase(wanted)) {
                        position = index;
                        break;
                     }
                  }
                  if (position < 0) {
                     return new ConfigResult(ConfigStatus.NOT_FOUND, code.displayName, rewards, removeTarget);
                  }
               }
               detail = rewards.remove(position);
               break;
            }
            default: {
               if (setIndex < 1 || setIndex > rewards.size()) {
                  return new ConfigResult(ConfigStatus.BAD_NUMBER, code.displayName, rewards, Integer.toString(setIndex));
               }
               rewards.remove(setIndex - 1);
               rewards.addAll(setIndex - 1, newCommands);
               detail = String.join(" ;; ", newCommands);
               break;
            }
         }

         storage.setReward(code.key, String.join(REWARD_SEPARATOR, rewards));
         return new ConfigResult(ConfigStatus.OK, code.displayName, rewards, detail);
      }, result -> {
         switch (result.status) {
            case UNKNOWN_CODE:
               this.send(sender, this.message("messages.unknown-code", "&cDen Code &e%code% &cgibt es nicht.").replace("%code%", result.display));
               break;
            case BAD_NUMBER:
               this.send(sender, this.message("messages.config-bad-number", "&cEs gibt keine Belohnung mit der Nummer &e%number%&c. Mit &f/codeconfig %code% &cwerden alle angezeigt.")
                  .replace("%number%", result.detail).replace("%code%", result.display));
               break;
            case NOT_FOUND:
               this.send(sender, this.message("messages.config-not-found", "&cDiesen Befehl hat der Code nicht: &f%command%").replace("%command%", result.detail));
               break;
            default: {
               String path = action.equals("add") ? "messages.config-added" : action.equals("remove") ? "messages.config-removed" : "messages.config-set";
               String fallback = action.equals("add") ? "&aBelohnung zu &e%code% &ahinzugefügt: &f%command%"
                  : action.equals("remove") ? "&aBelohnung von &e%code% &aentfernt: &f%command%" : "&aBelohnung von &e%code% &ageändert: &f%command%";
               this.send(sender, this.message(path, fallback).replace("%code%", result.display).replace("%command%", result.detail));
               this.sendRewardList(sender, result.display, result.rewards);
               break;
            }
         }
      });
   }

   private void sendRewardList(CommandSender sender, String displayName, List<String> rewards) {
      if (rewards.isEmpty()) {
         this.send(sender, this.message("messages.config-none", "&7Der Code &e%code% &7hat keine Belohnung.").replace("%code%", displayName));
         return;
      }
      this.send(sender, this.message("messages.config-header", "&7Belohnungen von &e%code%&7:").replace("%code%", displayName));
      String line = this.message("messages.config-line", "&8%number%. &f%command%");
      for (int index = 0; index < rewards.size(); index++) {
         this.send(sender, line.replace("%number%", Integer.toString(index + 1)).replace("%command%", rewards.get(index)));
      }
   }

   // ------------------------------------------------------------------ /codedelete

   private void handleDelete(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      if (args.length != 1) {
         this.sendPath(sender, "messages.usage-delete", "&7Verwendung: &f/codedelete <Code>");
         return;
      }

      final String typed = args[0];
      final String key = typed.toLowerCase(Locale.ROOT);
      this.async(sender, storage -> storage.deleteCode(key), deleted -> {
         if (deleted) {
            this.send(sender, this.message("messages.deleted", "&aCode &e%code% &awurde gelöscht. Spieler, die ihn schon genutzt haben, können trotzdem keinen weiteren Code einlösen.").replace("%code%", typed));
            this.refreshKnownCodes();
         } else {
            this.send(sender, this.message("messages.unknown-code", "&cDen Code &e%code% &cgibt es nicht.").replace("%code%", typed));
         }
      });
   }

   // ------------------------------------------------------------------ /codereset

   private void handleReset(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }
      if (args.length != 1) {
         this.sendPath(sender, "messages.usage-reset", "&7Verwendung: &f/codereset <Spieler>");
         return;
      }

      final String typed = args[0];
      this.async(sender, storage -> storage.resetPlayer(typed), removed -> {
         if (removed == null) {
            this.send(sender, this.message("messages.reset-none", "&e%player% &chat noch keinen Code eingelöst.").replace("%player%", typed));
         } else {
            this.send(sender, this.message("messages.reset-done", "&aDer Code &e%code% &avon &e%player% &awurde zurückgesetzt. Der Spieler kann wieder einen Code einlösen.")
               .replace("%code%", removed.displayName).replace("%player%", removed.playerName));
         }
      });
   }

   // ------------------------------------------------------------------ /codetop

   private void handleTop(CommandSender sender) {
      if (!this.hasFeaturePermission(sender, TOP_PERMISSION) && !this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return;
      }

      final int limit = Math.max(1, Math.min(50, this.config.getInt("top-size", 10)));
      this.async(sender, storage -> {
         List<CodeRecord> ranking = new ArrayList<>(storage.listCodes());
         ranking.sort(Comparator.<CodeRecord>comparingInt(entry -> -entry.uses).thenComparing(entry -> entry.key));
         return ranking;
      }, ranking -> {
         if (ranking.isEmpty()) {
            this.sendPath(sender, "messages.top-empty", "&7Es gibt noch keine Codes.");
            return;
         }
         this.send(sender, this.message("messages.top-header", "&aMeistgenutzte Codes:"));
         String line = this.message("messages.top-line", "&8#%rank% &e%code% &8- &f%uses% &7Nutzungen");
         for (int index = 0; index < ranking.size() && index < limit; index++) {
            CodeRecord entry = ranking.get(index);
            this.send(sender, line.replace("%rank%", Integer.toString(index + 1)).replace("%code%", entry.displayName).replace("%uses%", Integer.toString(entry.uses)));
         }
      });
   }

   // ------------------------------------------------------------------ Tab-Completion

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!this.hasFeaturePermission(sender, ADMIN_PERMISSION)) {
         return List.of();
      }

      String name = command.getName().toLowerCase(Locale.ROOT);
      List<String> result = new ArrayList<>();
      if (args.length == 1 && (name.equals("codedelete") || name.equals("codeconfig"))) {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         for (String code : this.knownCodes) {
            if (code.toLowerCase(Locale.ROOT).startsWith(prefix)) {
               result.add(code);
            }
         }
      } else if (args.length == 2 && name.equals("codeconfig")) {
         String prefix = args[1].toLowerCase(Locale.ROOT);
         for (String option : new String[] {"add", "remove", "set"}) {
            if (option.startsWith(prefix)) {
               result.add(option);
            }
         }
      } else if (args.length == 1 && name.equals("codereset")) {
         result.addAll(OfflinePlayerNames.suggest(args[0]));
      }
      return result;
   }

   private void refreshKnownCodes() {
      Storage current = this.currentStorage();
      this.worker.execute(() -> {
         try {
            List<String> names = new ArrayList<>();
            for (CodeRecord record : current.listCodes()) {
               names.add(record.displayName);
            }
            this.knownCodes = names;
         } catch (Exception exception) {
            this.plugin.getLogger().log(Level.WARNING, "Code-System: Codes konnten nicht geladen werden", exception);
         }
      });
   }

   // ------------------------------------------------------------------ Hilfsmethoden

   private String message(String path, String fallback) {
      return color(this.config.getString(path, fallback));
   }

   private <T> void async(CommandSender sender, StorageTask<T> task, Consumer<T> done) {
      final Storage current = this.currentStorage();
      this.worker.execute(() -> {
         final T result;
         try {
            result = task.run(current);
         } catch (Exception exception) {
            this.plugin.getLogger().log(Level.WARNING, "Code-System: Speicherzugriff fehlgeschlagen", exception);
            this.sync(() -> this.sendPath(sender, "messages.db-error", "&cDer Speicher (Datenbank/Datei) ist gerade nicht erreichbar. Bitte melde das einem Admin."));
            return;
         }
         this.sync(() -> done.accept(result));
      });
   }

   private void sync(Runnable runnable) {
      try {
         Bukkit.getScheduler().runTask(this.plugin, runnable);
      } catch (RuntimeException exception) {
         // Plugin wird gerade beendet
      }
   }

   private Storage currentStorage() {
      DbSettings db = DbSettings.fromConfig(this.config.getBoolean("database.enabled", false),
         this.config.getString("database.type", "mysql"),
         this.config.getString("database.jdbc-url", ""),
         this.config.getString("database.host", "localhost"),
         this.config.getInt("database.port", 3306),
         this.config.getString("database.name", ""),
         this.config.getString("database.user", ""),
         this.config.getString("database.password", ""),
         this.config.getString("database.table-prefix", "gc_"));
      String signature = db == null ? "file" : db.signature();
      synchronized (this.storageLock) {
         if (this.storage == null || !signature.equals(this.storageSignature)) {
            this.storage = db == null
               ? new FileStorage(this.featureFile("codes.tsv"), this.featureFile("redeemed.tsv"), this.plugin.getLogger())
               : new SqlStorage(db);
            this.storageSignature = signature;
            if (db != null) {
               this.plugin.getLogger().info("Code-System nutzt die Datenbank " + db.describe() + ".");
            }
         }
         return this.storage;
      }
   }

   private static boolean hasIllegalChars(String text) {
      return text.indexOf('\t') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
   }

   private static List<String> parseCommands(String text) {
      List<String> commands = new ArrayList<>();
      if (text == null) {
         return commands;
      }
      for (String part : text.split(";;")) {
         String trimmed = part.trim();
         if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
         }
         if (!trimmed.isEmpty()) {
            commands.add(trimmed);
         }
      }
      return commands;
   }

   private static Integer parseIndex(String text) {
      if (text == null || !text.matches("[0-9]{1,6}")) {
         return null;
      }
      return Integer.parseInt(text);
   }

   // ------------------------------------------------------------------ Datentypen

   private interface StorageTask<T> {
      T run(Storage storage) throws Exception;
   }

   private enum RedeemStatus {
      OK,
      ALREADY,
      UNKNOWN
   }

   private static final class RedeemResult {
      final RedeemStatus status;
      final String display;
      final CodeRecord code;

      RedeemResult(RedeemStatus status, String display, CodeRecord code) {
         this.status = status;
         this.display = display;
         this.code = code;
      }
   }

   private enum ConfigStatus {
      OK,
      UNKNOWN_CODE,
      BAD_NUMBER,
      NOT_FOUND
   }

   private static final class ConfigResult {
      final ConfigStatus status;
      final String display;
      final List<String> rewards;
      final String detail;

      ConfigResult(ConfigStatus status, String display, List<String> rewards, String detail) {
         this.status = status;
         this.display = display;
         this.rewards = rewards;
         this.detail = detail;
      }
   }

   private static final class CodeRecord {
      final String key;
      final String displayName;
      final String reward;
      final String creator;
      final long createdAt;
      final int uses;

      CodeRecord(String key, String displayName, String reward, String creator, long createdAt, int uses) {
         this.key = key;
         this.displayName = displayName;
         this.reward = reward == null ? "" : reward;
         this.creator = creator == null ? "" : creator;
         this.createdAt = createdAt;
         this.uses = uses;
      }

      CodeRecord withUses(int newUses) {
         return new CodeRecord(this.key, this.displayName, this.reward, this.creator, this.createdAt, newUses);
      }

      CodeRecord withReward(String newReward) {
         return new CodeRecord(this.key, this.displayName, newReward, this.creator, this.createdAt, this.uses);
      }

      List<String> rewardCommands() {
         return parseCommands(this.reward);
      }
   }

   private static final class Redemption {
      final UUID uuid;
      final String playerName;
      final String codeKey;
      final String displayName;
      final long redeemedAt;

      Redemption(UUID uuid, String playerName, String codeKey, String displayName, long redeemedAt) {
         this.uuid = uuid;
         this.playerName = playerName;
         this.codeKey = codeKey;
         this.displayName = displayName;
         this.redeemedAt = redeemedAt;
      }
   }

   // ------------------------------------------------------------------ Speicher

   private interface Storage {
      List<CodeRecord> listCodes() throws Exception;

      CodeRecord getCode(String key) throws Exception;

      boolean createCode(CodeRecord record) throws Exception;

      boolean deleteCode(String key) throws Exception;

      void setReward(String key, String reward) throws Exception;

      Redemption getRedemption(UUID uuid) throws Exception;

      boolean redeem(UUID uuid, String playerName, String codeKey, String displayName) throws Exception;

      Redemption resetPlayer(String nameOrUuid) throws Exception;
   }

   /** Lokale Dateien: codes.tsv und redeemed.tsv (die Nutzungen werden immer aus redeemed.tsv gezählt). */
   private static final class FileStorage implements Storage {
      private final File codesFile;
      private final File redeemedFile;
      private final Logger logger;
      private final Map<String, CodeRecord> codes = new LinkedHashMap<>();
      private final Map<UUID, Redemption> redeemed = new HashMap<>();

      FileStorage(File codesFile, File redeemedFile, Logger logger) {
         this.codesFile = codesFile;
         this.redeemedFile = redeemedFile;
         this.logger = logger;
         this.load();
      }

      private void load() {
         try {
            if (this.codesFile.isFile()) {
               for (String line : Files.readAllLines(this.codesFile.toPath(), StandardCharsets.UTF_8)) {
                  String[] parts = line.split("\t", -1);
                  if (parts.length >= 6 && parts[0].matches(CODE_PATTERN)) {
                     this.codes.put(parts[0], new CodeRecord(parts[0], parts[1], parts[2], parts[4], parseLong(parts[5]), 0));
                  }
               }
            }
            if (this.redeemedFile.isFile()) {
               for (String line : Files.readAllLines(this.redeemedFile.toPath(), StandardCharsets.UTF_8)) {
                  String[] parts = line.split("\t", -1);
                  if (parts.length >= 5) {
                     try {
                        UUID id = UUID.fromString(parts[0]);
                        this.redeemed.put(id, new Redemption(id, parts[1], parts[2], parts[3], parseLong(parts[4])));
                     } catch (IllegalArgumentException ignored) {
                        // kaputte Zeile überspringen
                     }
                  }
               }
            }
         } catch (IOException exception) {
            this.logger.severe("Code-Daten konnten nicht geladen werden: " + exception.getMessage());
         }
      }

      private void save() throws IOException {
         Map<String, Integer> counts = this.counts();
         List<String> codeLines = new ArrayList<>();
         for (CodeRecord record : this.codes.values()) {
            codeLines.add(String.join("\t", record.key, record.displayName, record.reward,
               Integer.toString(counts.getOrDefault(record.key, 0)), record.creator, Long.toString(record.createdAt)));
         }
         List<String> redeemLines = new ArrayList<>();
         for (Redemption value : this.redeemed.values()) {
            redeemLines.add(String.join("\t", value.uuid.toString(), value.playerName, value.codeKey, value.displayName, Long.toString(value.redeemedAt)));
         }
         this.writeAtomic(this.codesFile, codeLines);
         this.writeAtomic(this.redeemedFile, redeemLines);
      }

      private void writeAtomic(File target, List<String> lines) throws IOException {
         File parent = target.getParentFile();
         if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Ordner konnte nicht erstellt werden: " + parent);
         }
         File temp = new File(parent, target.getName() + ".tmp");
         Files.write(temp.toPath(), lines, StandardCharsets.UTF_8);
         Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }

      private Map<String, Integer> counts() {
         Map<String, Integer> counts = new HashMap<>();
         for (Redemption value : this.redeemed.values()) {
            counts.merge(value.codeKey, 1, Integer::sum);
         }
         return counts;
      }

      @Override
      public synchronized List<CodeRecord> listCodes() {
         Map<String, Integer> counts = this.counts();
         List<CodeRecord> result = new ArrayList<>();
         for (CodeRecord record : this.codes.values()) {
            result.add(record.withUses(counts.getOrDefault(record.key, 0)));
         }
         return result;
      }

      @Override
      public synchronized CodeRecord getCode(String key) {
         CodeRecord record = this.codes.get(key);
         return record == null ? null : record.withUses(this.counts().getOrDefault(key, 0));
      }

      @Override
      public synchronized boolean createCode(CodeRecord record) throws IOException {
         if (this.codes.containsKey(record.key)) {
            return false;
         }
         this.codes.put(record.key, record);
         try {
            this.save();
         } catch (IOException exception) {
            this.codes.remove(record.key);
            throw exception;
         }
         return true;
      }

      @Override
      public synchronized boolean deleteCode(String key) throws IOException {
         CodeRecord removed = this.codes.remove(key);
         if (removed == null) {
            return false;
         }
         try {
            this.save();
         } catch (IOException exception) {
            this.codes.put(key, removed);
            throw exception;
         }
         return true;
      }

      @Override
      public synchronized void setReward(String key, String reward) throws IOException {
         CodeRecord previous = this.codes.get(key);
         if (previous == null) {
            return;
         }
         this.codes.put(key, previous.withReward(reward));
         try {
            this.save();
         } catch (IOException exception) {
            this.codes.put(key, previous);
            throw exception;
         }
      }

      @Override
      public synchronized Redemption getRedemption(UUID uuid) {
         return this.redeemed.get(uuid);
      }

      @Override
      public synchronized boolean redeem(UUID uuid, String playerName, String codeKey, String displayName) throws IOException {
         if (this.redeemed.containsKey(uuid)) {
            return false;
         }
         this.redeemed.put(uuid, new Redemption(uuid, playerName, codeKey, displayName, System.currentTimeMillis()));
         try {
            this.save();
         } catch (IOException exception) {
            this.redeemed.remove(uuid);
            throw exception;
         }
         return true;
      }

      @Override
      public synchronized Redemption resetPlayer(String nameOrUuid) throws IOException {
         Redemption found = null;
         UUID wanted = parseUuid(nameOrUuid);
         for (Redemption value : this.redeemed.values()) {
            boolean matches = wanted != null ? value.uuid.equals(wanted) : value.playerName.equalsIgnoreCase(nameOrUuid);
            if (matches && (found == null || value.redeemedAt > found.redeemedAt)) {
               found = value;
            }
         }
         if (found == null) {
            return null;
         }
         this.redeemed.remove(found.uuid);
         try {
            this.save();
         } catch (IOException exception) {
            this.redeemed.put(found.uuid, found);
            throw exception;
         }
         return found;
      }
   }

   /** Datenbank-Einstellungen aus features/codes/config.yml (Abschnitt database). */
   private static final class DbSettings {
      final String driverClass;
      final String url;
      final String user;
      final String password;
      final String prefix;
      final String shown;

      private DbSettings(String driverClass, String url, String user, String password, String prefix, String shown) {
         this.driverClass = driverClass;
         this.url = url;
         this.user = user;
         this.password = password;
         this.prefix = prefix;
         this.shown = shown;
      }

      static DbSettings fromConfig(boolean enabled, String type, String jdbcUrl, String host, int port, String name, String user, String password, String prefix) {
         if (!enabled) {
            return null;
         }
         boolean mariadb = type != null && type.trim().equalsIgnoreCase("mariadb");
         String cleanPrefix = prefix == null ? "" : prefix.trim();
         if (!cleanPrefix.matches("[A-Za-z0-9_]{0,32}")) {
            cleanPrefix = "gc_";
         }

         String url = jdbcUrl == null ? "" : jdbcUrl.trim();
         String shown = url;
         if (url.isEmpty()) {
            String cleanHost = host == null ? "" : host.trim();
            String cleanName = name == null ? "" : name.trim();
            int cleanPort = Math.max(1, Math.min(65535, port));
            if (!cleanHost.isEmpty() && !cleanName.isEmpty()) {
               String base = (mariadb ? "jdbc:mariadb://" : "jdbc:mysql://") + cleanHost + ":" + cleanPort + "/" + cleanName;
               url = base + (mariadb ? "?connectTimeout=5000&socketTimeout=10000" : "?connectTimeout=5000&socketTimeout=10000&characterEncoding=UTF-8");
               shown = cleanHost + ":" + cleanPort + "/" + cleanName;
            }
         } else {
            mariadb = url.startsWith("jdbc:mariadb:");
            shown = "(jdbc-url)";
         }

         String driver = mariadb ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
         return new DbSettings(driver, url, user == null ? "" : user.trim(), password == null ? "" : password, cleanPrefix, shown);
      }

      String signature() {
         return this.url + "|" + this.user + "|" + this.password.hashCode() + "|" + this.prefix;
      }

      String describe() {
         return this.shown + " (Tabellen " + this.prefix + "codes und " + this.prefix + "code_redemptions)";
      }
   }

   /** MySQL/MariaDB. Jede Operation öffnet eine eigene Verbindung; die Tabellen werden automatisch angelegt. */
   private static final class SqlStorage implements Storage {
      private final DbSettings settings;
      private final String codesTable;
      private final String redeemTable;
      private boolean tablesReady;

      SqlStorage(DbSettings settings) {
         this.settings = settings;
         this.codesTable = "`" + settings.prefix + "codes`";
         this.redeemTable = "`" + settings.prefix + "code_redemptions`";
      }

      private Connection connect() throws SQLException {
         if (this.settings.url.isEmpty() || this.settings.user.isEmpty()) {
            throw new SQLException("database.host/name/user sind in features/codes/config.yml noch nicht gesetzt.");
         }
         try {
            Class.forName(this.settings.driverClass);
         } catch (ClassNotFoundException exception) {
            throw new SQLException("JDBC-Treiber nicht gefunden: " + this.settings.driverClass, exception);
         }
         Connection connection = DriverManager.getConnection(this.settings.url, this.settings.user, this.settings.password);
         if (!this.tablesReady) {
            try (Statement statement = connection.createStatement()) {
               statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + this.codesTable + " ("
                  + "code_key VARCHAR(32) NOT NULL, display_name VARCHAR(32) NOT NULL, reward TEXT NOT NULL, "
                  + "creator VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY (code_key)) DEFAULT CHARSET=utf8mb4");
               statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + this.redeemTable + " ("
                  + "player_uuid CHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, code_key VARCHAR(32) NOT NULL, "
                  + "display_name VARCHAR(32) NOT NULL, redeemed_at BIGINT NOT NULL, PRIMARY KEY (player_uuid), "
                  + "KEY idx_code_key (code_key), KEY idx_player_name (player_name)) DEFAULT CHARSET=utf8mb4");
            } catch (SQLException exception) {
               connection.close();
               throw exception;
            }
            this.tablesReady = true;
         }
         return connection;
      }

      private String selectCodes() {
         return "SELECT c.code_key, c.display_name, c.reward, c.creator, c.created_at, "
            + "(SELECT COUNT(*) FROM " + this.redeemTable + " r WHERE r.code_key = c.code_key) AS uses FROM " + this.codesTable + " c";
      }

      private static CodeRecord readCode(ResultSet rows) throws SQLException {
         return new CodeRecord(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getInt(6));
      }

      private static boolean isDuplicate(SQLException exception) {
         String state = exception.getSQLState();
         return exception.getErrorCode() == 1062 || (state != null && state.startsWith("23"));
      }

      @Override
      public List<CodeRecord> listCodes() throws SQLException {
         try (Connection connection = this.connect();
            PreparedStatement statement = connection.prepareStatement(this.selectCodes() + " ORDER BY c.code_key");
            ResultSet rows = statement.executeQuery()) {
            List<CodeRecord> result = new ArrayList<>();
            while (rows.next()) {
               result.add(readCode(rows));
            }
            return result;
         }
      }

      @Override
      public CodeRecord getCode(String key) throws SQLException {
         try (Connection connection = this.connect(); PreparedStatement statement = connection.prepareStatement(this.selectCodes() + " WHERE c.code_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
               return rows.next() ? readCode(rows) : null;
            }
         }
      }

      @Override
      public boolean createCode(CodeRecord record) throws SQLException {
         try (Connection connection = this.connect();
            PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + this.codesTable + " (code_key, display_name, reward, creator, created_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, record.key);
            statement.setString(2, record.displayName);
            statement.setString(3, record.reward);
            statement.setString(4, record.creator);
            statement.setLong(5, record.createdAt);
            statement.executeUpdate();
            return true;
         } catch (SQLException exception) {
            if (isDuplicate(exception)) {
               return false;
            }
            throw exception;
         }
      }

      @Override
      public boolean deleteCode(String key) throws SQLException {
         try (Connection connection = this.connect(); PreparedStatement statement = connection.prepareStatement("DELETE FROM " + this.codesTable + " WHERE code_key = ?")) {
            statement.setString(1, key);
            return statement.executeUpdate() > 0;
         }
      }

      @Override
      public void setReward(String key, String reward) throws SQLException {
         try (Connection connection = this.connect(); PreparedStatement statement = connection.prepareStatement("UPDATE " + this.codesTable + " SET reward = ? WHERE code_key = ?")) {
            statement.setString(1, reward);
            statement.setString(2, key);
            statement.executeUpdate();
         }
      }

      @Override
      public Redemption getRedemption(UUID uuid) throws SQLException {
         try (Connection connection = this.connect();
            PreparedStatement statement = connection.prepareStatement(
               "SELECT player_uuid, player_name, code_key, display_name, redeemed_at FROM " + this.redeemTable + " WHERE player_uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
               return rows.next() ? readRedemption(rows) : null;
            }
         }
      }

      private static Redemption readRedemption(ResultSet rows) throws SQLException {
         return new Redemption(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5));
      }

      @Override
      public boolean redeem(UUID uuid, String playerName, String codeKey, String displayName) throws SQLException {
         try (Connection connection = this.connect();
            PreparedStatement statement = connection.prepareStatement(
               "INSERT INTO " + this.redeemTable + " (player_uuid, player_name, code_key, display_name, redeemed_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, codeKey);
            statement.setString(4, displayName);
            statement.setLong(5, System.currentTimeMillis());
            statement.executeUpdate();
            return true;
         } catch (SQLException exception) {
            if (isDuplicate(exception)) {
               return false;
            }
            throw exception;
         }
      }

      @Override
      public Redemption resetPlayer(String nameOrUuid) throws SQLException {
         UUID wanted = parseUuid(nameOrUuid);
         try (Connection connection = this.connect()) {
            Redemption found;
            String base = "SELECT player_uuid, player_name, code_key, display_name, redeemed_at FROM " + this.redeemTable;
            try (PreparedStatement statement = connection.prepareStatement(
               wanted != null ? base + " WHERE player_uuid = ?" : base + " WHERE LOWER(player_name) = ? ORDER BY redeemed_at DESC")) {
               statement.setString(1, wanted != null ? wanted.toString() : nameOrUuid.toLowerCase(Locale.ROOT));
               try (ResultSet rows = statement.executeQuery()) {
                  found = rows.next() ? readRedemption(rows) : null;
               }
            }
            if (found == null) {
               return null;
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + this.redeemTable + " WHERE player_uuid = ?")) {
               delete.setString(1, found.uuid.toString());
               delete.executeUpdate();
            }
            return found;
         }
      }
   }

   private static UUID parseUuid(String text) {
      if (text == null || text.length() != 36) {
         return null;
      }
      try {
         return UUID.fromString(text);
      } catch (IllegalArgumentException exception) {
         return null;
      }
   }

   private static long parseLong(String value) {
      try {
         return Long.parseLong(value.trim());
      } catch (NumberFormatException exception) {
         return 0L;
      }
   }
}

package de.greenattack.system.economy;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class GreenAttackEconomyService implements EconomyService {
   private final GreenAttackSystemPlugin plugin;
   private final File balancesFile;
   private final YamlConfiguration data;
   private final Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
   private final Map<UUID, String> playerNames = new LinkedHashMap<>();
   private final int fractionalDigits;

   public GreenAttackEconomyService(GreenAttackSystemPlugin plugin) {
      this.plugin = plugin;
      this.balancesFile = new File(new File(plugin.getDataFolder(), "features/economy"), "balances.yml");
      File parent = this.balancesFile.getParentFile();
      if (!parent.isDirectory() && !parent.mkdirs()) {
         plugin.getLogger().severe("Economy-Ordner konnte nicht erstellt werden: " + parent);
      }

      this.data = YamlConfiguration.loadConfiguration(this.balancesFile);
      this.fractionalDigits = Math.max(0, Math.min(4, plugin.getConfig().getInt("economy.currency.decimal-places", 2)));
      this.loadBalances();
      this.importEssentialsBalancesIfAvailable();
   }

   @Override
   public synchronized double balance(OfflinePlayer player) {
      return this.getOrCreate(player).doubleValue();
   }

   @Override
   public synchronized boolean has(OfflinePlayer player, double amount) {
      BigDecimal value = this.amount(amount);
      return value != null && this.getOrCreate(player).compareTo(value) >= 0;
   }

   @Override
   public synchronized boolean withdraw(OfflinePlayer player, double amount) {
      BigDecimal value = this.amount(amount);
      if (value != null && value.signum() >= 0) {
         BigDecimal current = this.getOrCreate(player);
         BigDecimal next = current.subtract(value);
         return !this.plugin.getConfig().getBoolean("economy.allow-negative", false) && next.compareTo(this.minimumBalance()) < 0
            ? false
            : this.set(player, next);
      } else {
         return false;
      }
   }

   @Override
   public synchronized boolean deposit(OfflinePlayer player, double amount) {
      BigDecimal value = this.amount(amount);
      if (value != null && value.signum() >= 0) {
         BigDecimal next = this.getOrCreate(player).add(value);
         return next.compareTo(this.maximumBalance()) > 0 ? false : this.set(player, next);
      } else {
         return false;
      }
   }

   public synchronized boolean setBalance(OfflinePlayer player, double amount) {
      BigDecimal value = this.amount(amount);
      return value != null && value.compareTo(this.minimumBalance()) >= 0 && value.compareTo(this.maximumBalance()) <= 0 ? this.set(player, value) : false;
   }

   public synchronized boolean reset(OfflinePlayer player) {
      return this.set(player, BigDecimal.ZERO.setScale(this.fractionalDigits, RoundingMode.HALF_UP));
   }

   public synchronized boolean hasAccount(OfflinePlayer player) {
      return player != null && this.balances.containsKey(player.getUniqueId());
   }

   public synchronized Map<UUID, Double> balancesSnapshot() {
      Map<UUID, Double> result = new LinkedHashMap<>();
      this.balances.forEach((uuid, amount) -> result.put(uuid, amount.doubleValue()));
      return result;
   }

   public synchronized String knownPlayerName(UUID uuid) {
      String name = this.playerNames.get(uuid);
      if (name == null || name.isBlank()) {
         name = this.data.getString("players." + uuid + ".name");
      }

      boolean fromEssentials = name == null || name.isBlank();
      if (fromEssentials) {
         name = this.essentialsPlayerName(uuid);
      }

      if (name != null && !name.isBlank()) {
         this.playerNames.put(uuid, name);
         this.data.set("players." + uuid + ".name", name);
         if (fromEssentials) {
            this.save();
         }
      }

      return name != null && !name.isBlank() ? name : null;
   }

   public synchronized void rememberPlayerName(UUID uuid, String name) {
      if (uuid != null && name != null && !name.isBlank()) {
         String previous = this.knownPlayerName(uuid);
         if (!name.equals(previous)) {
            this.playerNames.put(uuid, name);
            this.data.set("players." + uuid + ".name", name);
            this.save();
         }
      }
   }

   @Override
   public String format(double amount) {
      NumberFormat format = NumberFormat.getNumberInstance(Locale.GERMANY);
      format.setMinimumFractionDigits(this.fractionalDigits);
      format.setMaximumFractionDigits(this.fractionalDigits);
      String number = format.format(amount);
      String symbol = this.plugin.getConfig().getString("economy.currency.symbol", "$");
      return this.plugin.getConfig().getString("economy.currency.position", "suffix").equalsIgnoreCase("prefix")
         ? symbol + number
         : number + (symbol.isEmpty() ? "" : " " + symbol);
   }

   @Override
   public String providerName() {
      return "GreenAttack-System";
   }

   public int fractionalDigits() {
      return this.fractionalDigits;
   }

   public String currencySingular() {
      return this.plugin.getConfig().getString("economy.currency.singular", "Dollar");
   }

   public String currencyPlural() {
      return this.plugin.getConfig().getString("economy.currency.plural", "Dollar");
   }

   private void loadBalances() {
      ConfigurationSection players = this.data.getConfigurationSection("players");
      if (players != null) {
         for (String key : players.getKeys(false)) {
            try {
               UUID uuid = UUID.fromString(key);
               String playerName = players.getString(key + ".name");
               if (playerName != null && !playerName.isBlank()) {
                  this.playerNames.put(uuid, playerName);
               }

               BigDecimal amount = this.amount(players.getString(key + ".balance"));
               if (amount != null) {
                  this.balances.put(uuid, this.clamp(amount));
               }
            } catch (IllegalArgumentException ignored) {
               this.plugin.getLogger().warning("Ungültige UUID in features/economy/balances.yml: " + key);
            }
         }
      }
   }

   private void importEssentialsBalancesIfAvailable() {
      if (this.plugin.getConfig().getBoolean("economy.import-essentials-userdata", true) && !this.data.getBoolean("meta.essentials-import-complete", false)) {
         File essentialsUsers = new File(this.plugin.getDataFolder().getParentFile(), "Essentials/userdata");
         File[] files = essentialsUsers.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
         if (files != null && files.length != 0) {
            int imported = 0;

            for (File file : files) {
               String filename = file.getName();

               try {
                  UUID uuid = UUID.fromString(filename.substring(0, filename.length() - 4));
                  YamlConfiguration legacy = YamlConfiguration.loadConfiguration(file);
                  String playerName = legacy.getString("last-account-name");
                  if (playerName == null || playerName.isBlank()) {
                     playerName = legacy.getString("name");
                  }

                  if (playerName != null && !playerName.isBlank()) {
                     this.playerNames.put(uuid, playerName);
                     this.data.set("players." + uuid + ".name", playerName);
                  }

                  if (!this.balances.containsKey(uuid) && legacy.contains("money")) {
                     BigDecimal value = this.amount(legacy.getString("money"));
                     if (value != null) {
                        this.balances.put(uuid, this.clamp(value));
                        this.data.set("players." + uuid + ".balance", this.balances.get(uuid).toPlainString());
                        imported++;
                     }
                  }
               } catch (IllegalArgumentException ignored) {
                  this.plugin.getLogger().warning("Ein EssentialsX-Guthaben konnte wegen einer ungültigen UUID nicht importiert werden.");
               }
            }

            this.data.set("meta.essentials-import-complete", true);
            this.save();
            this.plugin.getLogger().info("EssentialsX-Guthabenimport abgeschlossen: " + imported + " Konten übernommen.");
         }
      }
   }

   private BigDecimal getOrCreate(OfflinePlayer player) {
      if (player == null) {
         return BigDecimal.ZERO.setScale(this.fractionalDigits, RoundingMode.HALF_UP);
      }

      UUID uuid = player.getUniqueId();
      BigDecimal value = this.balances.get(uuid);
      if (value == null) {
         value = this.amount(this.plugin.getConfig().getDouble("economy.starting-balance", 0.0));
         if (value == null) {
            value = BigDecimal.ZERO;
         }

         value = this.clamp(value);
         this.balances.put(uuid, value);
         this.data.set("players." + uuid + ".balance", value.toPlainString());
         this.rememberPlayerNameInMemory(uuid, player.getName());
         this.save();
      } else if (player.getName() != null) {
         String previousName = this.knownPlayerName(uuid);
         if (!player.getName().equals(previousName)) {
            this.rememberPlayerNameInMemory(uuid, player.getName());
            this.save();
         }
      }

      return value;
   }

   private boolean set(OfflinePlayer player, BigDecimal value) {
      if (player == null) {
         return false;
      }

      BigDecimal rounded = value.setScale(this.fractionalDigits, RoundingMode.HALF_UP);
      UUID uuid = player.getUniqueId();
      BigDecimal previous = this.balances.get(uuid);
      this.balances.put(uuid, rounded);
      this.data.set("players." + uuid + ".balance", rounded.toPlainString());
      this.rememberPlayerNameInMemory(uuid, player.getName());
      if (this.save()) {
         return true;
      }

      if (previous == null) {
         this.balances.remove(uuid);
         this.data.set("players." + uuid + ".balance", null);
      } else {
         this.balances.put(uuid, previous);
         this.data.set("players." + uuid + ".balance", previous.toPlainString());
      }

      return false;
   }

   private void rememberPlayerNameInMemory(UUID uuid, String name) {
      if (name != null && !name.isBlank()) {
         this.playerNames.put(uuid, name);
         this.data.set("players." + uuid + ".name", name);
      }
   }

   private String essentialsPlayerName(UUID uuid) {
      File userFile = new File(new File(this.plugin.getDataFolder().getParentFile(), "Essentials/userdata"), uuid + ".yml");
      if (!userFile.isFile()) {
         return null;
      }

      YamlConfiguration userData = YamlConfiguration.loadConfiguration(userFile);
      String name = userData.getString("last-account-name");
      if (name == null || name.isBlank()) {
         name = userData.getString("name");
      }

      return name != null && !name.isBlank() ? name : null;
   }

   private BigDecimal amount(double value) {
      return Double.isFinite(value) ? this.amount(BigDecimal.valueOf(value)) : null;
   }

   private BigDecimal amount(String value) {
      if (value == null) {
         return null;
      }

      try {
         return this.amount(new BigDecimal(value.replace(',', '.')));
      } catch (NumberFormatException exception) {
         return null;
      }
   }

   private BigDecimal amount(BigDecimal value) {
      return value.setScale(this.fractionalDigits, RoundingMode.HALF_UP);
   }

   private BigDecimal clamp(BigDecimal amount) {
      if (amount.compareTo(this.maximumBalance()) > 0) {
         return this.maximumBalance();
      } else {
         return !this.plugin.getConfig().getBoolean("economy.allow-negative", false) && amount.compareTo(this.minimumBalance()) < 0
            ? this.minimumBalance()
            : amount;
      }
   }

   private BigDecimal minimumBalance() {
      return this.amount(this.plugin.getConfig().getDouble("economy.minimum-balance", 0.0));
   }

   private BigDecimal maximumBalance() {
      return this.amount(this.plugin.getConfig().getDouble("economy.maximum-balance", 1.0E12));
   }

   private boolean save() {
      try {
         this.data.save(this.balancesFile);
         return true;
      } catch (IOException exception) {
         this.plugin.getLogger().severe("Economy-Guthaben konnten nicht gespeichert werden: " + exception.getMessage());
         return false;
      }
   }
}

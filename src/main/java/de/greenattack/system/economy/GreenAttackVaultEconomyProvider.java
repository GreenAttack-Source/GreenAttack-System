package de.greenattack.system.economy;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.Collections;
import java.util.List;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

public final class GreenAttackVaultEconomyProvider extends AbstractEconomy {
   private final GreenAttackSystemPlugin plugin;
   private final GreenAttackEconomyService economy;

   private GreenAttackVaultEconomyProvider(GreenAttackSystemPlugin plugin, GreenAttackEconomyService economy) {
      this.plugin = plugin;
      this.economy = economy;
   }

   public static GreenAttackVaultEconomyProvider register(GreenAttackSystemPlugin plugin, GreenAttackEconomyService economy) {
      GreenAttackVaultEconomyProvider provider = new GreenAttackVaultEconomyProvider(plugin, economy);
      plugin.getServer().getServicesManager().register(Economy.class, provider, plugin, ServicePriority.Highest);
      return provider;
   }

   public void unregister() {
      this.plugin.getServer().getServicesManager().unregister(Economy.class, this);
   }

   public boolean isEnabled() {
      return this.plugin.isEnabled() && this.plugin.getConfig().getBoolean("economy.enabled", true);
   }

   public String getName() {
      return this.economy.providerName();
   }

   public boolean hasBankSupport() {
      return false;
   }

   public int fractionalDigits() {
      return this.economy.fractionalDigits();
   }

   public String format(double amount) {
      return this.economy.format(amount);
   }

   public String currencyNamePlural() {
      return this.economy.currencyPlural();
   }

   public String currencyNameSingular() {
      return this.economy.currencySingular();
   }

   public boolean hasAccount(OfflinePlayer player) {
      return this.economy.hasAccount(player);
   }

   public boolean hasAccount(OfflinePlayer player, String worldName) {
      return this.hasAccount(player);
   }

   public boolean hasAccount(String playerName) {
      return this.economy.hasAccount(Bukkit.getOfflinePlayer(playerName));
   }

   public boolean hasAccount(String playerName, String worldName) {
      return this.hasAccount(playerName);
   }

   public double getBalance(OfflinePlayer player) {
      return this.economy.balance(player);
   }

   public double getBalance(OfflinePlayer player, String worldName) {
      return this.getBalance(player);
   }

   public double getBalance(String playerName) {
      return this.getBalance(Bukkit.getOfflinePlayer(playerName));
   }

   public double getBalance(String playerName, String worldName) {
      return this.getBalance(playerName);
   }

   public boolean has(OfflinePlayer player, double amount) {
      return this.economy.has(player, amount);
   }

   public boolean has(OfflinePlayer player, String worldName, double amount) {
      return this.has(player, amount);
   }

   public boolean has(String playerName, double amount) {
      return this.has(Bukkit.getOfflinePlayer(playerName), amount);
   }

   public boolean has(String playerName, String worldName, double amount) {
      return this.has(playerName, amount);
   }

   public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
      boolean success = this.economy.withdraw(player, amount);
      return this.response(amount, player, success, success ? null : "Nicht genug Guthaben oder ungültiger Betrag.");
   }

   public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
      return this.withdrawPlayer(player, amount);
   }

   public EconomyResponse withdrawPlayer(String playerName, double amount) {
      return this.withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
   }

   public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
      return this.withdrawPlayer(playerName, amount);
   }

   public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
      boolean success = this.economy.deposit(player, amount);
      return this.response(amount, player, success, success ? null : "Ungültiger Betrag oder maximales Guthaben erreicht.");
   }

   public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
      return this.depositPlayer(player, amount);
   }

   public EconomyResponse depositPlayer(String playerName, double amount) {
      return this.depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
   }

   public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
      return this.depositPlayer(playerName, amount);
   }

   public EconomyResponse createBank(String name, String playerName) {
      return this.notImplemented("GreenAttack-Economy unterstützt keine Konten für Gruppen.");
   }

   public EconomyResponse deleteBank(String name) {
      return this.notImplemented("GreenAttack-Economy unterstützt keine Konten für Gruppen.");
   }

   public EconomyResponse bankBalance(String name) {
      return this.notImplemented("GreenAttack-Economy unterstützt keine Konten für Gruppen.");
   }

   public EconomyResponse bankHas(String name, double amount) {
      return this.notImplemented("GreenAttack-Economy unterstützt keine Konten für Gruppen.");
   }

   public EconomyResponse bankWithdraw(String name, double amount) {
      return this.notImplemented("GreenAttack-Economy unterstützt keine Konten für Gruppen.");
   }

   public EconomyResponse bankDeposit(String name, double amount) {
      return this.notImplemented("GreenAttack-Economy unterstützt keine Konten für Gruppen.");
   }

   public EconomyResponse isBankOwner(String name, String playerName) {
      return this.notImplemented("GreenAttack-Economy unterstützt keine Konten für Gruppen.");
   }

   public EconomyResponse isBankMember(String name, String playerName) {
      return this.notImplemented("GreenAttack-Economy unterstützt keine Konten für Gruppen.");
   }

   public List<String> getBanks() {
      return Collections.emptyList();
   }

   public boolean createPlayerAccount(OfflinePlayer player) {
      this.economy.balance(player);
      return true;
   }

   public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
      return this.createPlayerAccount(player);
   }

   public boolean createPlayerAccount(String playerName) {
      return this.createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
   }

   public boolean createPlayerAccount(String playerName, String worldName) {
      return this.createPlayerAccount(playerName);
   }

   private EconomyResponse response(double amount, OfflinePlayer player, boolean success, String error) {
      double balance = player == null ? 0.0 : this.economy.balance(player);
      return new EconomyResponse(amount, balance, success ? ResponseType.SUCCESS : ResponseType.FAILURE, error);
   }

   private EconomyResponse notImplemented(String error) {
      return new EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, error);
   }
}

package de.greenattack.system.economy;

import de.greenattack.system.GreenAttackSystemPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class VaultEconomyService implements EconomyService {
   private final Economy economy;

   private VaultEconomyService(Economy economy) {
      this.economy = economy;
   }

   public static EconomyService create(GreenAttackSystemPlugin plugin) {
      RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
      return registration == null ? null : new VaultEconomyService((Economy)registration.getProvider());
   }

   @Override
   public double balance(OfflinePlayer player) {
      return this.economy.getBalance(player);
   }

   @Override
   public boolean has(OfflinePlayer player, double amount) {
      return Double.isFinite(amount) && amount >= 0.0 && this.economy.has(player, amount);
   }

   @Override
   public boolean withdraw(OfflinePlayer player, double amount) {
      return transact(this.economy.withdrawPlayer(player, amount));
   }

   @Override
   public boolean deposit(OfflinePlayer player, double amount) {
      return transact(this.economy.depositPlayer(player, amount));
   }

   @Override
   public String format(double amount) {
      return this.economy.format(amount);
   }

   @Override
   public String providerName() {
      return this.economy.getName();
   }

   private static boolean transact(EconomyResponse response) {
      return response != null && response.transactionSuccess();
   }
}

package de.greenattack.system.economy;

import org.bukkit.OfflinePlayer;

public interface EconomyService {
   double balance(OfflinePlayer var1);

   boolean has(OfflinePlayer var1, double var2);

   boolean withdraw(OfflinePlayer var1, double var2);

   boolean deposit(OfflinePlayer var1, double var2);

   String format(double var1);

   String providerName();
}

package de.greenattack.system.economy;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class EconomyPlaceholderExpansion extends PlaceholderExpansion {
   private static final Pattern DECIMAL_PLACES = Pattern.compile("(?:eco_)?balance_(\\d+)dp");
   private final GreenAttackSystemPlugin plugin;

   public EconomyPlaceholderExpansion(GreenAttackSystemPlugin plugin) {
      this.plugin = plugin;
   }

   public String getAuthor() {
      return "GreenAttack";
   }

   public String getIdentifier() {
      return "greenattack";
   }

   public String getVersion() {
      return this.plugin.getDescription().getVersion();
   }

   public boolean persist() {
      return true;
   }

   public String onRequest(OfflinePlayer player, String parameter) {
      if (player != null && parameter != null) {
         EconomyService economy = this.plugin.getEconomyService();
         if (economy == null) {
            return "";
         }

         double balance = economy.balance(player);
         if (!Double.isFinite(balance)) {
            return "0";
         }

         String key = parameter.toLowerCase(Locale.ROOT);
         int decimalPlaces = Math.max(0, Math.min(4, this.plugin.getConfig().getInt("economy.currency.decimal-places", 2)));

         return switch (key) {
            case "balance", "eco_balance", "economy_balance", "money_balance" -> formatBalance(balance, decimalPlaces, false);
            case "balance_commas", "eco_balance_commas", "economy_balance_commas", "money_balance_commas" -> formatBalance(balance, decimalPlaces, true);
            case "balance_fixed", "eco_balance_fixed", "economy_balance_fixed", "money_balance_fixed" -> formatBalance(balance, 0, false);
            case "balance_formatted", "eco_balance_formatted", "economy_balance_formatted", "money_balance_formatted" -> economy.format(balance);
            default -> formatRequestedDecimalPlaces(key, balance);
         };
      } else {
         return "";
      }
   }

   private static String formatRequestedDecimalPlaces(String key, double balance) {
      Matcher matcher = DECIMAL_PLACES.matcher(key);
      if (!matcher.matches()) {
         return null;
      }

      int decimalPlaces;
      try {
         decimalPlaces = Integer.parseInt(matcher.group(1));
      } catch (NumberFormatException exception) {
         return null;
      }

      return decimalPlaces >= 0 && decimalPlaces <= 10 ? formatBalance(balance, decimalPlaces, false) : null;
   }

   private static String formatBalance(double balance, int decimalPlaces, boolean grouping) {
      DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
      DecimalFormat format = new DecimalFormat(grouping ? "#,##0" : "0", symbols);
      format.setGroupingUsed(grouping);
      format.setMinimumFractionDigits(decimalPlaces);
      format.setMaximumFractionDigits(decimalPlaces);
      return format.format(balance);
   }
}

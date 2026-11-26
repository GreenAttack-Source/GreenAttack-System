package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class KristallPlaceholderExpansion extends PlaceholderExpansion {
   private static final long[] COMPACT_UNITS = new long[]{1L, 1000L, 1000000L, 1000000000L, 1000000000000L};
   private static final String[] COMPACT_SUFFIXES = new String[]{"", "k", "m", "b", "t"};
   private final GreenAttackSystemPlugin plugin;
   private final KristallFeature feature;

   public KristallPlaceholderExpansion(GreenAttackSystemPlugin plugin, KristallFeature feature) {
      this.plugin = plugin;
      this.feature = feature;
   }

   public String getAuthor() {
      return "T0byte2004";
   }

   public String getIdentifier() {
      return "kristall";
   }

   public String getVersion() {
      return this.plugin.getDescription().getVersion();
   }

   public boolean persist() {
      return true;
   }

   public String onRequest(OfflinePlayer player, String parameter) {
      if (!this.feature.isEnabled()) {
         return null;
      }

      long balance = player == null ? 0L : this.feature.getBalance(player.getUniqueId());
      if (parameter == null) {
         return null;
      }

      return switch (parameter.toLowerCase(Locale.ROOT)) {
         case "balance", "kristalle", "anzahl" -> Long.toString(balance);
         case "balance_formatted" -> NumberFormat.getIntegerInstance(Locale.US).format(balance);
         case "balance_formatted_k" -> formatCompact(balance);
         default -> null;
      };
   }

   private static String formatCompact(long amount) {
      if (amount < COMPACT_UNITS[1]) {
         return Long.toString(amount);
      }

      int unitIndex = COMPACT_UNITS.length - 1;

      while (unitIndex > 1 && amount < COMPACT_UNITS[unitIndex]) {
         unitIndex--;
      }

      BigDecimal unit = BigDecimal.valueOf(COMPACT_UNITS[unitIndex]);
      BigDecimal value = BigDecimal.valueOf(amount).divide(unit, 2, RoundingMode.HALF_UP).stripTrailingZeros();
      if (value.compareTo(BigDecimal.valueOf(1000L)) >= 0 && unitIndex < COMPACT_UNITS.length - 1) {
         unit = BigDecimal.valueOf(COMPACT_UNITS[++unitIndex]);
         value = BigDecimal.valueOf(amount).divide(unit, 2, RoundingMode.HALF_UP).stripTrailingZeros();
      }

      return value.toPlainString() + COMPACT_SUFFIXES[unitIndex];
   }
}

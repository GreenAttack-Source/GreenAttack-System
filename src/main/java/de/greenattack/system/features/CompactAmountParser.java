package de.greenattack.system.features;

import java.math.BigDecimal;

public final class CompactAmountParser {
   private CompactAmountParser() {
   }

   public static BigDecimal parse(String input) {
      String normalized = input == null ? "" : input.trim().replace(',', '.');
      if (normalized.isEmpty()) {
         throw new NumberFormatException("Betrag fehlt");
      }

      BigDecimal multiplier = BigDecimal.ONE;
      char suffix = Character.toLowerCase(normalized.charAt(normalized.length() - 1));
      if (suffix == 'k' || suffix == 'm') {
         multiplier = suffix == 'k' ? BigDecimal.valueOf(1000L) : BigDecimal.valueOf(1000000L);
         normalized = normalized.substring(0, normalized.length() - 1);
      }

      if (normalized.isEmpty()) {
         throw new NumberFormatException("Betrag fehlt");
      } else {
         return new BigDecimal(normalized).multiply(multiplier);
      }
   }
}

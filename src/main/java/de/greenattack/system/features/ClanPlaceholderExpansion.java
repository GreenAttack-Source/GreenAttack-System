package de.greenattack.system.features;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI-Erweiterung "clan": %clan_tag%, %clan_tag_plain%, %clan_name%,
 * %clan_role%, %clan_members%. Liest nur aus dem Zwischenspeicher.
 */
public final class ClanPlaceholderExpansion extends PlaceholderExpansion {
   private final ClanFeature feature;

   public ClanPlaceholderExpansion(ClanFeature feature) {
      this.feature = feature;
   }

   public String getAuthor() {
      return "T0byte2004";
   }

   public String getIdentifier() {
      return "clan";
   }

   public String getVersion() {
      return "1.0";
   }

   public boolean persist() {
      return true;
   }

   public String onRequest(OfflinePlayer player, String parameter) {
      if (player == null) {
         return "";
      }
      return this.feature.placeholder(player.getUniqueId(), parameter);
   }
}

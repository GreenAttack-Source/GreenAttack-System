package de.greenattack.system.features;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Gemeinsame Autocomplete-Hilfe: schlägt Spielernamen vor, auch wenn der Spieler offline ist
 * (zum Beispiel für /eco, /ka, /invsee, /ecsee, /codereset). Bukkit.getOfflinePlayers() kann auf
 * großen Servern viele Einträge liefern (jeder, der je gespielt hat); die Namensliste wird deshalb
 * nur alle 60 Sekunden neu eingelesen statt bei jedem Tastendruck, Online-Spieler sind immer aktuell.
 */
final class OfflinePlayerNames {
   private static final long REFRESH_MILLIS = 60_000L;
   private static final int MAX_SUGGESTIONS = 50;
   private static final AtomicReference<String[]> CACHE = new AtomicReference<>(new String[0]);
   private static final AtomicLong CACHE_TIME = new AtomicLong(0L);

   private OfflinePlayerNames() {
   }

   /** Namen aller Online- und (nach kurzer Verzögerung) aller Offline-Spieler, die mit prefix beginnen. */
   static List<String> suggest(String prefix) {
      String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
      List<String> result = new ArrayList<>();
      for (Player online : Bukkit.getOnlinePlayers()) {
         if (online.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
            result.add(online.getName());
         }
      }
      for (String offline : offlineNames()) {
         if (result.size() >= MAX_SUGGESTIONS) {
            break;
         }
         if (offline.toLowerCase(Locale.ROOT).startsWith(lower) && !result.contains(offline)) {
            result.add(offline);
         }
      }
      result.sort(String.CASE_INSENSITIVE_ORDER);
      return result.size() > MAX_SUGGESTIONS ? result.subList(0, MAX_SUGGESTIONS) : result;
   }

   private static String[] offlineNames() {
      long now = System.currentTimeMillis();
      long last = CACHE_TIME.get();
      if (now - last < REFRESH_MILLIS) {
         return CACHE.get();
      }
      if (!CACHE_TIME.compareAndSet(last, now)) {
         return CACHE.get();
      }
      try {
         OfflinePlayer[] offlinePlayers = Bukkit.getOfflinePlayers();
         List<String> names = new ArrayList<>(offlinePlayers.length);
         for (OfflinePlayer offline : offlinePlayers) {
            String name = offline.getName();
            if (name != null && offline.hasPlayedBefore()) {
               names.add(name);
            }
         }
         names.sort(Comparator.naturalOrder());
         String[] array = names.toArray(new String[0]);
         CACHE.set(array);
         return array;
      } catch (RuntimeException exception) {
         return CACHE.get();
      }
   }
}

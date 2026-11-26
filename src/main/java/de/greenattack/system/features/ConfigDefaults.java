package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Ergänzt eine bereits vorhandene Feature-Config um Einträge, die in der mitgelieferten Standard-Config
 * stehen, in der Datei aber fehlen (zum Beispiel nach einem Update). Vorhandene Werte werden nie verändert.
 */
final class ConfigDefaults {
   private ConfigDefaults() {
   }

   /** Liefert true, wenn etwas ergänzt wurde; der Aufrufer speichert dann die Config. */
   static boolean merge(GreenAttackSystemPlugin plugin, YamlConfiguration target, String resourcePath) {
      try (InputStream input = plugin.getResource(resourcePath)) {
         if (input == null) {
            return false;
         }
         YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
         boolean changed = false;
         for (String key : defaults.getKeys(true)) {
            if (target.contains(key)) {
               continue;
            }
            if (defaults.isConfigurationSection(key)) {
               target.createSection(key);
            } else {
               target.set(key, defaults.get(key));
            }
            copyComments(defaults, target, key);
            changed = true;
         }
         return changed;
      } catch (Throwable throwable) {
         plugin.getLogger().log(Level.WARNING, "Standardwerte für " + resourcePath + " konnten nicht ergänzt werden", throwable);
         return false;
      }
   }

   private static void copyComments(YamlConfiguration from, YamlConfiguration to, String key) {
      try {
         List<String> block = from.getComments(key);
         if (block != null && !block.isEmpty()) {
            to.setComments(key, block);
         }
         List<String> inline = from.getInlineComments(key);
         if (inline != null && !inline.isEmpty()) {
            to.setInlineComments(key, inline);
         }
      } catch (Throwable ignored) {
         // Kommentare sind nur Zierde (ältere Server-API)
      }
   }
}

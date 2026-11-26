package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public abstract class FeatureModule {
   public static final String DEFAULT_PREFIX = "&2GreenAttack-Plugin &8» &7";
   protected final GreenAttackSystemPlugin plugin;
   private final String featureName;
   private final String resourcePath;
   private final File featureFolder;
   private final File configFile;
   protected YamlConfiguration config;

   protected FeatureModule(GreenAttackSystemPlugin plugin, String featureName) {
      if (featureName != null && featureName.matches("[A-Za-z0-9_-]+")) {
         this.plugin = plugin;
         this.featureName = featureName;
         this.resourcePath = "features/" + featureName + "/config.yml";
         this.featureFolder = new File(new File(plugin.getDataFolder(), "features"), featureName);
         this.configFile = new File(this.featureFolder, "config.yml");
         this.reload();
      } else {
         throw new IllegalArgumentException("Ungültiger Feature-Ordnername: " + featureName);
      }
   }

   public final void reload() {
      if (!this.featureFolder.isDirectory() && !this.featureFolder.mkdirs()) {
         this.plugin.getLogger().severe("Feature-Ordner konnte nicht erstellt werden: " + this.featureFolder);
      }

      File legacyConfig = new File(new File(this.plugin.getDataFolder(), "features"), this.featureName + ".yml");
      if (!this.configFile.exists() && legacyConfig.isFile()) {
         try {
            Files.move(legacyConfig.toPath(), this.configFile.toPath());
            this.plugin
               .getLogger()
               .info("Alte Konfiguration für Feature '" + this.featureName + "' nach features/" + this.featureName + "/config.yml verschoben.");
         } catch (IOException exception) {
            this.plugin
               .getLogger()
               .severe("Alte Konfiguration für Feature '" + this.featureName + "' konnte nicht verschoben werden: " + exception.getMessage());
         }
      }

      if (!this.configFile.exists()) {
         if (legacyConfig.isFile()) {
            this.plugin
               .getLogger()
               .warning(
                  "Die alte Konfiguration für Feature '" + this.featureName + "' wurde nicht verschoben. Die Standardkonfiguration wird separat angelegt."
               );
         }

         try {
            this.plugin.saveResource(this.resourcePath, false);
         } catch (IllegalArgumentException exception) {
            this.plugin.getLogger().severe("Standardkonfiguration fehlt im Plugin-JAR: " + this.resourcePath);
         }
      }

      if (!this.configFile.isFile()) {
         this.config = new YamlConfiguration();
      } else {
         this.config = YamlConfiguration.loadConfiguration(this.configFile);
         this.removeFeaturePrefixSettings();
      }
   }

   private void removeFeaturePrefixSettings() {
      if (this.config.contains("prefix") || this.config.contains("messages.prefix")) {
         this.config.set("prefix", null);
         this.config.set("messages.prefix", null);
         this.save();
      }
   }

   protected final File featureFile(String relativePath) {
      Path root = this.featureFolder.toPath().toAbsolutePath().normalize();
      Path resolved = root.resolve(relativePath).normalize();
      if (!resolved.startsWith(root)) {
         throw new IllegalArgumentException("Dateipfad liegt außerhalb des Feature-Ordners.");
      } else {
         return resolved.toFile();
      }
   }

   public final boolean isEnabled() {
      return this.config.getBoolean("enabled", true);
   }

   protected final String text(String path, String fallback) {
      return color(this.configuredText(path, fallback));
   }

   protected final String prefix() {
      return color(this.plugin.getConfig().getString("messages.prefix", "&2GreenAttack-Plugin &8» &7"));
   }

   protected final void send(CommandSender sender, String message) {
      String output = this.prefix() + message;
      if (sender instanceof Player player) {
         output = this.plugin.applyPlaceholders(player, output);
      }

      sender.sendMessage(color(output));
   }

   protected final boolean hasFeaturePermission(CommandSender sender, String permission) {
      return this.plugin.hasSystemPermission(sender, permission);
   }

   protected final void sendPath(CommandSender sender, String path, String fallback) {
      this.send(sender, this.configuredText(path, fallback));
   }

   private String configuredText(String path, String fallback) {
      if (!this.config.contains(path)) {
         this.config.set(path, fallback);
         this.save();
      }

      return this.config.getString(path, fallback);
   }

   protected final boolean save() {
      try {
         if (!this.featureFolder.isDirectory() && !this.featureFolder.mkdirs()) {
            throw new IOException("Feature-Ordner konnte nicht erstellt werden.");
         }

         this.config.save(this.configFile);
         return true;
      } catch (IOException exception) {
         this.plugin.getLogger().severe("Konnte Feature-Konfiguration " + this.configFile + " nicht speichern: " + exception.getMessage());
         return false;
      }
   }

   protected static String color(String value) {
      return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
   }
}

package de.greenattack.system.features;

import com.sun.management.OperatingSystemMXBean;
import de.greenattack.system.GreenAttackSystemPlugin;
import java.lang.management.ManagementFactory;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class MonitoringFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final Set<String> ELEMENTS = Set.of(
      "ping", "tps", "mspt", "tick", "cpu", "ram", "zeit", "time", "loc", "pos", "coords", "dir", "facing", "online", "spieler"
   );
   private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
   private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
   private final OperatingSystemMXBean operatingSystem;

   public MonitoringFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "monitoring");
      this.operatingSystem = ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean extended ? extended : null;
      Bukkit.getPluginManager().registerEvents(this, plugin);
      Bukkit.getScheduler().runTaskTimer(plugin, this::updateActionBars, 20L, 20L);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
         return true;
      } else {
         String name = command.getName().toLowerCase(Locale.ROOT);
         if (name.equals("performance") || name.equals("perf")) {
            return this.togglePerformanceMode(sender);
         } else if (!this.hasFeaturePermission(sender, this.config.getString("permissions.use", "system.monitoring.use"))) {
            this.sendPath(sender, "messages.no-permission", "&a&lGreenAttack &8» &cDazu hast du keine Rechte.");
            return true;
         } else if (!(sender instanceof Player player)) {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         } else {
            UUID id = player.getUniqueId();
            String path = "formats." + id;
            if (args.length == 0) {
               if (this.config.contains(path)) {
                  this.config.set(path, null);
                  this.save();
                  player.sendActionBar(LEGACY.deserialize(""));
                  this.sendPath(player, "messages.disabled-by-player", "&7Actionbar-Monitoring wurde &cdeaktiviert&7.");
                  player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 0.5F);
               } else {
                  this.config.set(path, this.config.getString("default-format", "ping tps ram"));
                  this.save();
                  this.sendPath(player, "messages.enabled-by-player", "&7Actionbar-Monitoring wurde &aaktiviert &7(Standardlayout).");
                  player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, 1.5F);
               }

               return true;
            } else {
               String first = args[0].toLowerCase(Locale.ROOT);
               if (!first.equals("help") && !first.equals("hilfe")) {
                  if (Set.of("off", "aus", "disable").contains(first)) {
                     this.config.set(path, null);
                     this.save();
                     player.sendActionBar(LEGACY.deserialize(""));
                     this.sendPath(player, "messages.disabled-by-player", "&7Dein Actionbar-Monitoring wurde &cdeaktiviert&7.");
                     return true;
                  } else if (first.equals("reset")) {
                     this.config.set(path, this.config.getString("default-format", "ping tps ram"));
                     this.save();
                     this.sendPath(player, "messages.reset", "&7Dein Monitoring wurde auf das Standardlayout zurückgesetzt.");
                     return true;
                  } else {
                     String format = String.join(" ", args).toLowerCase(Locale.ROOT).trim();
                     List<String> invalid = List.of(format.split("\\s+")).stream().filter(value -> !ELEMENTS.contains(value)).toList();
                     if (!invalid.isEmpty()) {
                        this.send(
                           player,
                           this.config.getString("messages.invalid-element", "&cUnbekanntes Element: &e%element%&c.").replace("%element%", invalid.getFirst())
                        );
                        return true;
                     } else {
                        this.config.set(path, format);
                        this.save();
                        this.send(
                           player,
                           this.config.getString("messages.layout-updated", "&7Actionbar-Monitoring angepasst auf: &a%format%").replace("%format%", format)
                        );
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
                        return true;
                     }
                  }
               } else {
                  for (String line : this.config.getStringList("messages.help")) {
                     this.send(player, line);
                  }

                  if (this.config.getStringList("messages.help").isEmpty()) {
                     this.sendPath(player, "messages.usage", "&7/monitoring [help|off|reset|<elemente>] — Elemente: ping tps mspt cpu ram zeit loc dir online");
                  }

                  return true;
               }
            }
         }
      }
   }

   private boolean togglePerformanceMode(CommandSender sender) {
      if (!this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.monitoring.admin"))) {
         this.sendPath(sender, "messages.no-permission", "&a&lGreenAttack &8» &cDazu hast du keine Rechte.");
         return true;
      }

      boolean enabled = !this.config.getBoolean("global-performance-mode", false);
      this.config.set("global-performance-mode", enabled);
      this.save();
      String path = enabled ? "messages.performance-enabled" : "messages.performance-disabled";
      String fallback = enabled ? "&e&l⚡ Globaler Performance-Anzeigemodus wurde aktiviert!" : "&c&l⚡ Globaler Performance-Anzeigemodus wurde deaktiviert!";
      Bukkit.broadcastMessage(this.prefix() + color(this.config.getString(path, fallback)));

      for (Player player : Bukkit.getOnlinePlayers()) {
         player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, enabled ? 1.5F : 0.5F);
      }

      return true;
   }

   private void updateActionBars() {
      if (this.isEnabled()) {
         for (Player player : Bukkit.getOnlinePlayers()) {
            String format = this.config.getString("formats." + player.getUniqueId());
            if (format != null && !format.isBlank()) {
               List<String> parts = new ArrayList<>();

               for (String tag : format.split("\\s+")) {
                  String value = this.element(player, tag.toLowerCase(Locale.ROOT));
                  if (value != null && !value.isBlank()) {
                     parts.add(value);
                  }
               }

               if (!parts.isEmpty()) {
                  String body = String.join(" &8| ", parts);
                  String display = this.config
                     .getString(
                        this.config.getBoolean("global-performance-mode", false) ? "actionbar.performance-format" : "actionbar.normal-format",
                        this.config.getBoolean("global-performance-mode", false) ? "&e&l⚡ %content% &e&l⚡" : "&a&lGreenAttack &8» %content%"
                     );
                  player.sendActionBar(LEGACY.deserialize(color(display.replace("%content%", body))));
               }
            }
         }
      }
   }

   private String element(Player player, String tag) {
      return switch (tag) {
         case "ping" -> this.format("elements.ping", "&7Ping: &a%value%ms", Integer.toString(player.getPing()));
         case "tps" -> {
            double[] tps = Bukkit.getTPS();
            yield this.format("elements.tps", "&7TPS: &a%value%", tps.length == 0 ? "?" : this.oneDecimal(tps[0]));
         }
         case "mspt", "tick" -> this.format("elements.mspt", "&7MSPT: &a%value%ms", this.oneDecimal(this.plugin.getServer().getAverageTickTime()));
         case "cpu" -> this.format("elements.cpu", "&7CPU: &a%value%", this.cpuLoad());
         case "ram" -> {
            Runtime runtime = Runtime.getRuntime();
            double used = (runtime.totalMemory() - runtime.freeMemory()) / 1024.0 / 1024.0 / 1024.0;
            double max = runtime.maxMemory() / 1024.0 / 1024.0 / 1024.0;
            yield this.format("elements.ram", "&7RAM: &a%used%GB &8/ &a%max%GB", this.oneDecimal(used), this.oneDecimal(max));
         }
         case "zeit", "time" -> this.format("elements.time", "&7Zeit: &a%value%", LocalTime.now().format(TIME_FORMAT));
         case "loc", "pos", "coords" -> this.format(
            "elements.location",
            "&7Pos: &a%x%, %y%, %z%",
            Integer.toString(player.getLocation().getBlockX()),
            Integer.toString(player.getLocation().getBlockY()),
            Integer.toString(player.getLocation().getBlockZ())
         );
         case "dir", "facing" -> this.format("elements.direction", "&7Richtung: &a%value%", player.getFacing().name().toLowerCase(Locale.ROOT));
         case "online", "spieler" -> this.format(
            "elements.online", "&7Online: &a%online%&8/&a%max%", Integer.toString(Bukkit.getOnlinePlayers().size()), Integer.toString(Bukkit.getMaxPlayers())
         );
         default -> null;
      };
   }

   private String format(String path, String fallback, String... values) {
      String value = color(this.config.getString(path, fallback));
      if (path.endsWith(".ram")) {
         return value.replace("%used%", values[0]).replace("%max%", values[1]);
      } else if (path.endsWith(".location")) {
         return value.replace("%x%", values[0]).replace("%y%", values[1]).replace("%z%", values[2]);
      } else {
         return path.endsWith(".online")
            ? value.replace("%online%", values[0]).replace("%max%", values[1])
            : value.replace("%value%", values.length == 0 ? "?" : values[0]);
      }
   }

   private String cpuLoad() {
      if (this.operatingSystem == null) {
         return "unbekannt";
      }

      double load = this.operatingSystem.getCpuLoad();
      return load < 0.0 ? "unbekannt" : String.format(Locale.ROOT, "%.1f%%", load * 100.0);
   }

   private String oneDecimal(double value) {
      return String.format(Locale.ROOT, "%.1f", value);
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (this.isEnabled() && this.hasFeaturePermission(sender, this.config.getString("permissions.use", "system.monitoring.use"))) {
         if (!command.getName().equalsIgnoreCase("monitoring")) {
            return List.of();
         }

         List<String> choices = new ArrayList<>(List.of("help", "hilfe", "off", "aus", "reset"));
         choices.addAll(ELEMENTS.stream().sorted().toList());
         String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
         return choices.stream().filter(value -> value.startsWith(partial)).toList();
      } else {
         return List.of();
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
   }
}

package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import de.greenattack.system.economy.EconomyService;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class PayFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String PAY_PERMISSION = "system.pay";
   private static final String PAY_ADMIN_PERMISSION = "system.pay.admin";
   private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
   private final File paymentsFile = this.featureFile("payments.yml");
   private FileConfiguration payments = YamlConfiguration.loadConfiguration(this.paymentsFile);

   public PayFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "economy");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (command.getName().equalsIgnoreCase("payinfo")) {
         this.showPaymentLog(sender, args);
         return true;
      }

      if (sender instanceof Player player) {
         this.pay(player, args);
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
      }

      return true;
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (command.getName().equalsIgnoreCase("pay")) {
         if (!this.hasFeaturePermission(sender, "system.pay")) {
            return List.of();
         }

         if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers()
               .stream()
               .filter(player -> !(sender instanceof Player senderPlayer && player.getUniqueId().equals(senderPlayer.getUniqueId())))
               .<String>map(Player::getName)
               .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
               .sorted(String.CASE_INSENSITIVE_ORDER)
               .toList();
         }

         if (args.length == 2) {
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target != null && !(sender instanceof Player senderPlayer && target.getUniqueId().equals(senderPlayer.getUniqueId()))) {
               String partial = args[1].toLowerCase(Locale.ROOT);
               return List.of("100", "500", "1k", "5k", "10k", "50k", "100k", "1m").stream().filter(amount -> amount.startsWith(partial)).toList();
            }

            return List.of();
         }
      } else if (command.getName().equalsIgnoreCase("payinfo") && this.hasFeaturePermission(sender, "system.pay.admin") && args.length == 1) {
         String partial = args[0];
         return List.of("5", "10", "25", "50").stream().filter(amount -> amount.startsWith(partial)).toList();
      }

      return List.of();
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
      String message = event.getMessage().trim();
      String[] parts = message.split("\\s+");
      if (parts.length != 0 && this.isPayLabel(parts[0])) {
         event.setCancelled(true);
         this.pay(event.getPlayer(), parts.length <= 1 ? new String[0] : Arrays.copyOfRange(parts, 1, parts.length));
      }
   }

   private void pay(Player sender, String[] args) {
      if (!this.hasFeaturePermission(sender, "system.pay")) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
      } else if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Economy-Feature ist deaktiviert.");
      } else if (args.length != 2) {
         this.sendPath(sender, "messages.usage", "&7Verwendung: &f/pay <Spieler> <Betrag> &8(z.B. 1k oder 1m)");
      } else {
         Player target = Bukkit.getPlayerExact(args[0]);
         if (target == null) {
            this.send(
               sender,
               this.config.getString("messages.target-not-online", "&cSpieler &e%player% &cist auf diesem Server nicht online.").replace("%player%", args[0])
            );
         } else if (sender.getUniqueId().equals(target.getUniqueId())) {
            this.sendPath(sender, "messages.self-payment", "&cDu kannst dir selbst kein Geld senden.");
         } else {
            BigDecimal parsed;
            try {
               parsed = CompactAmountParser.parse(args[1]);
            } catch (NumberFormatException exception) {
               this.sendPath(sender, "messages.invalid-amount", "&cGib einen positiven Betrag an, z.B. 1000, 1k oder 1m.");
               return;
            }

            double amount = parsed.doubleValue();
            if (parsed.signum() > 0 && Double.isFinite(amount)) {
               EconomyService economy = this.plugin.getEconomyService();
               if (economy == null) {
                  this.sendPath(sender, "messages.economy-unavailable", "&cDie Economy ist gerade nicht verfügbar.");
               } else if (!economy.has(sender, amount)) {
                  this.sendPath(sender, "messages.not-enough-money", "&cDu hast nicht genug Geld.");
               } else if (!economy.withdraw(sender, amount)) {
                  this.sendPath(sender, "messages.payment-failed", "&cDie Zahlung konnte nicht abgeschlossen werden.");
               } else if (!economy.deposit(target, amount)) {
                  if (!economy.deposit(sender, amount)) {
                     this.plugin
                        .getLogger()
                        .severe(
                           "Zahlung von "
                              + sender.getName()
                              + " an "
                              + target.getName()
                              + " ist fehlgeschlagen; auch die Rückerstattung konnte nicht abgeschlossen werden."
                        );
                  }

                  this.sendPath(sender, "messages.payment-failed", "&cDie Zahlung konnte nicht abgeschlossen werden.");
               } else {
                  String formatted = economy.format(amount);
                  this.recordPayment(sender, target, amount, formatted);
                  this.send(
                     sender,
                     this.config
                        .getString("messages.paid-sender", "&aDu hast &e%player% &a%amount% gesendet.")
                        .replace("%player%", target.getName())
                        .replace("%amount%", formatted)
                  );
                  this.send(
                     target,
                     this.config
                        .getString("messages.paid-target", "&aDu hast von &e%player% &a%amount% erhalten.")
                        .replace("%player%", sender.getName())
                        .replace("%amount%", formatted)
                  );
               }
            } else {
               this.sendPath(sender, "messages.invalid-amount", "&cGib einen positiven Betrag an, z.B. 1000, 1k oder 1m.");
            }
         }
      }
   }

   private void recordPayment(Player sender, Player target, double amount, String formatted) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("time", LOG_TIME.format(LocalDateTime.now()));
      entry.put("sender", sender.getName());
      entry.put("sender-uuid", sender.getUniqueId().toString());
      entry.put("target", target.getName());
      entry.put("target-uuid", target.getUniqueId().toString());
      entry.put("amount", amount);
      entry.put("formatted", formatted);
      int maxEntries = Math.max(1, Math.min(this.config.getInt("logging.max-entries", 500), 10000));
      List<Map<?, ?>> saved = this.payments.getMapList("transactions");
      List<Map<String, Object>> updated = new ArrayList<>(Math.min(maxEntries, saved.size() + 1));
      updated.add(entry);

      for (Map<?, ?> old : saved) {
         if (updated.size() >= maxEntries) {
            break;
         }

         Map<String, Object> copy = new LinkedHashMap<>();
         old.forEach((key, value) -> copy.put(Objects.toString(key), value));
         updated.add(copy);
      }

      this.payments.set("transactions", updated);

      try {
         this.payments.save(this.paymentsFile);
      } catch (IOException exception) {
         this.plugin
            .getLogger()
            .severe(
               "Erfolgreiche Zahlung von "
                  + sender.getName()
                  + " an "
                  + target.getName()
                  + " konnte nicht im lokalen Economy-Log gespeichert werden: "
                  + exception.getMessage()
            );
      }
   }

   private void showPaymentLog(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, "system.pay.admin")) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
      } else if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Economy-Feature ist deaktiviert.");
      } else if (args.length > 1) {
         this.sendPath(sender, "messages.info-usage", "&7Verwendung: &f/payinfo [Anzahl]");
      } else {
         int displayLimit = Math.max(1, Math.min(this.config.getInt("logging.display-limit", 10), 100));
         if (args.length == 1) {
            try {
               displayLimit = Math.max(1, Math.min(Integer.parseInt(args[0]), 100));
            } catch (NumberFormatException exception) {
               this.sendPath(sender, "messages.info-usage", "&7Verwendung: &f/payinfo [Anzahl]");
               return;
            }
         }

         List<Map<?, ?>> saved = this.payments.getMapList("transactions");
         if (saved.isEmpty()) {
            this.sendPath(sender, "messages.log-empty", "&eEs wurden noch keine Zahlungen protokolliert.");
         } else {
            this.sendPath(sender, "messages.log-header", "&6Letzte Zahlungen auf diesem Server:");

            for (int index = 0; index < Math.min(displayLimit, saved.size()); index++) {
               Map<?, ?> entry = saved.get(index);
               String line = this.config.getString("messages.log-entry", "&8[%time%] &e%sender% &7→ &b%target% &8| &a%amount%");
               line = line.replace("%time%", Objects.toString(entry.get("time"), "?"))
                  .replace("%sender%", Objects.toString(entry.get("sender"), "?"))
                  .replace("%target%", Objects.toString(entry.get("target"), "?"))
                  .replace("%amount%", Objects.toString(entry.get("formatted"), Objects.toString(entry.get("amount"), "?")));
               this.send(sender, line);
            }
         }
      }
   }

   private boolean isPayLabel(String label) {
      String normalized = label.toLowerCase(Locale.ROOT);
      return normalized.equals("/pay") || normalized.equals("/greenattack-system:pay");
   }
}

package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import de.greenattack.system.economy.EconomyService;
import de.greenattack.system.economy.GreenAttackEconomyService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class EconomyFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final String ADMIN_PERMISSION = "system.eco";

   public EconomyFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "economy");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);

      for (Player player : Bukkit.getOnlinePlayers()) {
         this.rememberPlayerName(player);
      }
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      this.rememberPlayerName(event.getPlayer());
   }

   private void rememberPlayerName(Player player) {
      if (this.plugin.getEconomyService() instanceof GreenAttackEconomyService service) {
         service.rememberPlayerName(player.getUniqueId(), player.getName());
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas Economy-Feature ist deaktiviert.");
         return true;
      }

      switch (command.getName().toLowerCase(Locale.ROOT)) {
         case "eco":
            this.handleEco(sender, args);
            break;
         case "balance":
            this.handleBalance(sender, args);
            break;
         case "balancetop":
            this.handleBalanceTop(sender, args);
            break;
         default:
            this.sendUsage(sender);
      }

      return true;
   }

   private void handleEco(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, "system.eco")) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
      } else if (args.length >= 2 && args.length <= 3) {
         String action = args[0].toLowerCase(Locale.ROOT);
         OfflinePlayer target = this.findPlayer(args[1]);
         if (target == null) {
            this.send(sender, this.text("messages.unknown-player", "&cSpieler &e%player% &cwurde nicht gefunden.").replace("%player%", args[1]));
         } else {
            EconomyService service = this.plugin.getEconomyService();
            if (service == null) {
               this.sendPath(sender, "messages.economy-unavailable", "&cDie Economy ist gerade nicht verfügbar.");
            } else if (action.equals("balance") && args.length == 2) {
               this.send(
                  sender,
                  this.text("messages.balance-other", "&e%player% &7hat &a%balance%&7.")
                     .replace("%player%", this.displayName(target))
                     .replace("%balance%", service.format(service.balance(target)))
               );
            } else if (action.equals("reset") && args.length == 2) {
               if (!this.greenAttackEconomy().reset(target)) {
                  this.sendPath(sender, "messages.operation-failed", "&cDas Guthaben konnte nicht gespeichert werden.");
               } else {
                  this.send(
                     sender,
                     this.text("messages.eco-reset", "&aGuthaben von &e%player% &aauf &e0 &azurückgesetzt.").replace("%player%", this.displayName(target))
                  );
               }
            } else if (List.of("give", "take", "set").contains(action) && args.length == 3) {
               BigDecimal parsed;
               try {
                  parsed = CompactAmountParser.parse(args[2]);
               } catch (NumberFormatException exception) {
                  this.sendPath(sender, "messages.invalid-amount", "&cGib einen gültigen Betrag ein.");
                  return;
               }

               double amount = parsed.doubleValue();
               if (parsed.signum() >= 0 && Double.isFinite(amount)) {
                  boolean success = switch (action) {
                     case "give" -> service.deposit(target, amount);
                     case "take" -> service.withdraw(target, amount);
                     case "set" -> this.greenAttackEconomy().setBalance(target, amount);
                     default -> false;
                  };
                  if (!success) {
                     this.sendPath(sender, "messages.operation-failed", "&cDas Guthaben konnte nicht geändert werden.");
                  } else {
                     String messagePath = switch (action) {
                        case "give" -> "messages.eco-give";
                        case "take" -> "messages.eco-take";
                        default -> "messages.eco-set";
                     };

                     String fallback = switch (action) {
                        case "give" -> "&a%player% erhielt &e%amount%&a. Neuer Kontostand: &e%balance%";
                        case "take" -> "&e%amount% &awurden von %player% abgezogen. Neuer Kontostand: &e%balance%";
                        default -> "&aGuthaben von &e%player% &aauf &e%balance% &agesetzt.";
                     };
                     this.send(
                        sender,
                        this.text(messagePath, fallback)
                           .replace("%player%", this.displayName(target))
                           .replace("%balance%", service.format(service.balance(target)))
                           .replace("%amount%", service.format(amount))
                           .replace("%action%", action)
                     );
                  }
               } else {
                  this.sendPath(sender, "messages.invalid-amount", "&cGib einen gültigen Betrag ein.");
               }
            } else {
               this.sendUsage(sender);
            }
         }
      } else {
         this.sendUsage(sender);
      }
   }

   private void handleBalance(CommandSender sender, String[] args) {
      if (args.length <= 1 && (args.length != 1 || this.hasFeaturePermission(sender, "system.eco"))) {
         OfflinePlayer target;
         if (args.length == 1) {
            target = this.findPlayer(args[0]);
         } else {
            if (!(sender instanceof Player player)) {
               this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler ohne Spielernamen verwenden.");
               return;
            }

            target = player;
         }

         if (target == null) {
            this.send(sender, this.text("messages.unknown-player", "&cSpieler &e%player% &cwurde nicht gefunden.").replace("%player%", args[0]));
         } else {
            EconomyService service = this.plugin.getEconomyService();
            if (service == null) {
               this.sendPath(sender, "messages.economy-unavailable", "&cDie Economy ist gerade nicht verfügbar.");
            } else {
               this.send(
                  sender, this.text("messages.balance-self", "&7Dein Guthaben: &a%balance%").replace("%balance%", service.format(service.balance(target)))
               );
            }
         }
      } else {
         this.sendUsage(sender);
      }
   }

   private void handleBalanceTop(CommandSender sender, String[] args) {
      if (this.plugin.getEconomyService() == null) {
         this.sendPath(sender, "messages.economy-unavailable", "&cDie Economy ist gerade nicht verfügbar.");
      } else if (args.length != 0) {
         this.sendPath(sender, "messages.baltop-usage", "&cVerwendung: /balancetop");
      } else {
         GreenAttackEconomyService service = this.greenAttackEconomy();
         List<Entry<UUID, Double>> entries = new ArrayList<>(service.balancesSnapshot().entrySet());
         entries.sort(Entry.comparingByValue(Comparator.reverseOrder()));
         List<Entry<UUID, Double>> topEntries = entries.subList(0, Math.min(5, entries.size()));
         if (topEntries.isEmpty()) {
            this.sendBalanceTopMessage(sender, this.text("messages.baltop-header", "&6Top Geld"));
            this.sendBalanceTopMessage(sender, this.text("messages.baltop-empty", "&eEs gibt noch keine Konten."));
         } else {
            List<CompletableFuture<String>> nameLookups = topEntries.stream().map(entry -> this.resolveTopPlayerName(entry.getKey(), service)).toList();
            CompletableFuture.allOf(nameLookups.toArray(CompletableFuture[]::new))
               .whenComplete(
                  (ignored, failure) -> Bukkit.getScheduler()
                     .runTask(
                        this.plugin,
                        () -> {
                           this.sendBalanceTopMessage(sender, this.text("messages.baltop-header", "&6Top Geld"));

                           for (int index = 0; index < topEntries.size(); index++) {
                              Entry<UUID, Double> entry = topEntries.get(index);
                              String playerName = nameLookups.get(index).getNow(null);
                              if (playerName != null && !playerName.isBlank()) {
                                 service.rememberPlayerName(entry.getKey(), playerName);
                              } else {
                                 playerName = "Spielername nicht verfügbar";
                              }

                              this.sendBalanceTopMessage(
                                 sender,
                                 this.text("messages.baltop-entry", "&e%position%. &f%player% &7- &a%balance%")
                                    .replace("%position%", Integer.toString(index + 1))
                                    .replace("%player%", playerName)
                                    .replace("%balance%", service.format(entry.getValue()))
                              );
                           }
                        }
                     )
               );
         }
      }
   }

   private CompletableFuture<String> resolveTopPlayerName(UUID uuid, GreenAttackEconomyService service) {
      String knownName = service.knownPlayerName(uuid);
      if (knownName != null) {
         return CompletableFuture.completedFuture(knownName);
      }

      String cachedName = Bukkit.getOfflinePlayer(uuid).getName();
      if (cachedName != null && !cachedName.isBlank()) {
         return CompletableFuture.completedFuture(cachedName);
      }

      try {
         return Bukkit.createPlayerProfile(uuid)
            .update()
            .orTimeout(5L, TimeUnit.SECONDS)
            .handle(
               (profile, failure) -> failure == null && profile != null && profile.getName() != null && !profile.getName().isBlank() ? profile.getName() : null
            );
      } catch (RuntimeException exception) {
         return CompletableFuture.completedFuture(null);
      }
   }

   private void sendBalanceTopMessage(CommandSender sender, String message) {
      String output = sender instanceof Player player ? this.plugin.applyPlaceholders(player, message) : message;
      sender.sendMessage(color(this.prefix() + output));
   }

   private OfflinePlayer findPlayer(String name) {
      for (Player player : Bukkit.getOnlinePlayers()) {
         if (player.getName().equalsIgnoreCase(name)) {
            return player;
         }
      }

      for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
         if (player.getName() != null && player.getName().equalsIgnoreCase(name)) {
            return player;
         }
      }

      return null;
   }

   private String displayName(OfflinePlayer player) {
      return player.getName() == null ? player.getUniqueId().toString() : player.getName();
   }

   private GreenAttackEconomyService greenAttackEconomy() {
      return (GreenAttackEconomyService)this.plugin.getEconomyService();
   }

   private void sendUsage(CommandSender sender) {
      this.sendPath(sender, "messages.eco-usage", "&7Verwendung: &f/eco <give|take|set|reset|balance> <Spieler> [Betrag] &8(1k = 1.000, 1m = 1.000.000)");
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (command.getName().equalsIgnoreCase("eco")) {
         if (!this.hasFeaturePermission(sender, "system.eco")) {
            return List.of();
         }

         if (args.length == 1) {
            return this.partial(List.of("give", "take", "set", "reset", "balance"), args[0]);
         }

         if (args.length == 2) {
            return this.partial(this.playerNames(), args[1]);
         }

         if (args.length == 3) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (List.of("give", "take", "set").contains(action)) {
               List<String> suggestions = action.equals("set")
                  ? List.of("0", "1", "10", "100", "1k", "10k", "1m")
                  : List.of("1", "10", "100", "1k", "10k", "100k", "1m");
               return this.partial(suggestions, args[2]);
            }
         }
      } else if (command.getName().equalsIgnoreCase("balance") && args.length == 1 && this.hasFeaturePermission(sender, "system.eco")) {
         return this.partial(this.playerNames(), args[0]);
      }

      return List.of();
   }

   private List<String> playerNames() {
      List<String> names = new ArrayList<>();
      Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
      Arrays.stream(Bukkit.getOfflinePlayers()).<String>map(OfflinePlayer::getName).filter(name -> name != null).forEach(names::add);
      return names.stream().distinct().toList();
   }

   private List<String> partial(List<String> values, String prefix) {
      String normalized = prefix.toLowerCase(Locale.ROOT);
      return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
   }
}

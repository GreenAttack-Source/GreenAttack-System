package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public final class RankFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final int MENU_SIZE = 27;
   private static final int RANK_ROW_START = 9;
   private static final int RANK_ROW_LENGTH = 9;
   private final KristallFeature kristallFeature;
   private final Set<UUID> pendingPurchases = new HashSet<>();

   public RankFeature(GreenAttackSystemPlugin plugin, KristallFeature kristallFeature) {
      super(plugin, "rank");
      this.kristallFeature = kristallFeature;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDer Rangmarkt ist deaktiviert.");
         return true;
      }

      if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
         if (!this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.rank"))) {
            this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
            return true;
         } else {
            this.reload();
            this.sendPath(sender, "messages.reloaded", "&aDer Rangmarkt wurde neu geladen.");
            return true;
         }
      } else {
         if (args.length != 0) {
            this.sendPath(sender, "messages.usage", "&7Verwendung: &f/rank &7oder &f/rank reload");
            return true;
         }

         if (sender instanceof Player player) {
            if (!this.hasFeaturePermission(player, this.config.getString("permissions.use", "system.rank.use"))) {
               this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
               return true;
            } else {
               this.openMenu(player);
               return true;
            }
         } else {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         }
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1 && this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.rank"))) {
         return "reload".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("reload") : List.of();
      } else {
         return List.of();
      }
   }

   private void openMenu(Player player) {
      this.openMenu(player, 0);
   }

   private void openMenu(Player player, int requestedPage) {
      RankFeature.RankMenuHolder holder = new RankFeature.RankMenuHolder();
      String title = color(this.config.getString("menu.title", "&8Rangmarkt"));
      Inventory inventory = Bukkit.createInventory(holder, 27, title);
      holder.inventory = inventory;
      List<RankFeature.RankOffer> offers = this.loadOffers();
      int pageCount = Math.max(1, (offers.size() + RANK_ROW_LENGTH - 1) / RANK_ROW_LENGTH);
      holder.page = Math.max(0, Math.min(requestedPage, pageCount - 1));
      ItemStack filler = this.createItem(this.config.getString("menu.filler-material", "GRAY_STAINED_GLASS_PANE"), "&7 ", List.of());

      for (int slot = 0; slot < 27; slot++) {
         inventory.setItem(slot, filler.clone());
      }

      List<String> balanceLore = new ArrayList<>();
      balanceLore.add("&7Kontostand: &b" + compactAmount(this.kristallFeature.getBalance(player.getUniqueId())) + " Kristalle");
      if (pageCount > 1) {
         balanceLore.add("&7Seite &f" + (holder.page + 1) + "&7/&f" + pageCount);
      }
      inventory.setItem(
         4,
         this.createItem(
            "EMERALD",
            this.config.getString("menu.balance-name", "&aDeine Kristalle"),
            balanceLore
         )
      );

      int pageFirstOffer = holder.page * RANK_ROW_LENGTH;
      List<RankFeature.RankOffer> pageOffers = offers.subList(pageFirstOffer, Math.min(pageFirstOffer + RANK_ROW_LENGTH, offers.size()));
      int firstSlot = RANK_ROW_START + Math.max(0, (RANK_ROW_LENGTH - pageOffers.size()) / 2);
      for (int index = 0; index < pageOffers.size(); index++) {
         RankFeature.RankOffer offer = pageOffers.get(index);
         List<String> lore = new ArrayList<>();
         lore.add("&7Preis: &b" + compactAmount(offer.price()) + " Kristalle");
         lore.add(" ");
         lore.add(this.config.getString("menu.purchase-hint", "&eKlicke zum Kaufen"));
         int slot = firstSlot + index;
         holder.offersBySlot.put(slot, offer);
         inventory.setItem(slot, this.createItem(offer.material(), offer.displayName(), lore));
      }

      if (holder.page > 0) {
         inventory.setItem(18, this.createItem("ARROW", "&eVorherige Seite", List.of("&7Klicke für die vorherigen Ränge.")));
      }
      if (holder.page + 1 < pageCount) {
         inventory.setItem(26, this.createItem("ARROW", "&eNächste Seite", List.of("&7Klicke für weitere Ränge.")));
      }
      inventory.setItem(22, this.createItem("BARRIER", "&cSchließen", List.of("&7Klicke, um das Menü zu schließen.")));
      player.openInventory(inventory);
   }

   @EventHandler
   public void onMenuClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof RankFeature.RankMenuHolder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player
            && event.getClickedInventory() != null
            && event.getClickedInventory().equals(event.getView().getTopInventory())) {
            int slot = event.getRawSlot();
            if (slot == 22) {
               player.closeInventory();
            } else if (slot == 18 && ((RankFeature.RankMenuHolder)event.getView().getTopInventory().getHolder()).page > 0) {
               this.openMenu(player, ((RankFeature.RankMenuHolder)event.getView().getTopInventory().getHolder()).page - 1);
            } else if (slot == 26) {
               RankFeature.RankMenuHolder holder = (RankFeature.RankMenuHolder)event.getView().getTopInventory().getHolder();
               List<RankFeature.RankOffer> offers = this.loadOffers();
               if ((holder.page + 1) * RANK_ROW_LENGTH < offers.size()) {
                  this.openMenu(player, holder.page + 1);
               }
            } else {
               RankFeature.RankMenuHolder holder = (RankFeature.RankMenuHolder)event.getView().getTopInventory().getHolder();
               RankFeature.RankOffer offer = holder.offersBySlot.get(slot);
               if (offer != null) {
                  List<RankFeature.RankOffer> offers = this.loadOffers();
                  RankFeature.RankOffer currentOffer = offers.stream().filter(current -> current.key().equals(offer.key())).findFirst().orElse(null);
                  if (currentOffer != null) {
                     this.purchase(player, currentOffer, offers);
                  }
               }
            }
         }
      }
   }

   @EventHandler
   public void onMenuDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof RankFeature.RankMenuHolder) {
         event.setCancelled(true);
      }
   }

   private void purchase(Player player, RankFeature.RankOffer offer, List<RankFeature.RankOffer> offers) {
      UUID playerId = player.getUniqueId();
      if (!this.hasFeaturePermission(player, this.config.getString("permissions.use", "system.rank.use"))) {
         this.sendPath(player, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         player.closeInventory();
      } else if (!this.pendingPurchases.add(playerId)) {
         this.sendPath(player, "messages.purchase-pending", "&eDein Rangkauf wird bereits verarbeitet.");
      } else {
         RankFeature.RankPlan plan;
         try {
            plan = this.prepareRankPlan(player, offer, offers);
         } catch (RankFeature.RankPurchaseException exception) {
            this.pendingPurchases.remove(playerId);
            if (exception.groupName() != null) {
               String message = this.config.getString(exception.messagePath(), exception.getMessage());
               if (message.contains("%group%")) {
                  message = message.replace("%group%", exception.groupName());
               } else {
                  message = message + " &7(" + exception.groupName() + ")";
               }

               this.send(player, message);
            } else {
               this.sendPath(player, exception.messagePath(), exception.getMessage());
            }

            return;
         } catch (ReflectiveOperationException | RuntimeException exception) {
            this.pendingPurchases.remove(playerId);
            this.plugin.getLogger().warning("LuckPerms-Rangprüfung für " + player.getName() + " ist fehlgeschlagen: " + exception.getMessage());
            this.sendPath(player, "messages.rank-check-failed", "&cDer Rang konnte nicht geprüft werden. Es wurden keine Kristalle abgezogen.");
            return;
         }

         try {
            if (!this.kristallFeature.withdrawBalance(playerId, offer.price())) {
               this.pendingPurchases.remove(playerId);
               this.send(
                  player,
                  this.config
                     .getString("messages.not-enough", "&cDu hast nicht genug Kristalle. &7Dein Kontostand: &b%balance%")
                     .replace("%balance%", compactAmount(this.kristallFeature.getBalance(playerId)))
               );
               return;
            }
         } catch (IOException | ArithmeticException exception) {
            this.pendingPurchases.remove(playerId);
            this.plugin.getLogger().severe("Kristallabbuchung für " + player.getName() + " fehlgeschlagen: " + exception.getMessage());
            this.sendPath(player, "messages.crystal-save-failed", "&cDein Kristallkonto konnte nicht gespeichert werden.");
            return;
         }

         try {
            plan.apply();
            CompletionStage<?> save = plan.save();
            save.whenComplete(
               (ignored, failure) -> Bukkit.getScheduler()
                  .runTask(
                     this.plugin,
                     () -> {
                        this.pendingPurchases.remove(playerId);
                        if (failure == null) {
                           this.send(
                              player,
                              this.config
                                 .getString("messages.purchase-success", "&aDu hast den Rang &f%rank% &afür &b%price% Kristalle &agekauft.")
                                 .replace("%rank%", offer.plainName())
                                 .replace("%price%", compactAmount(offer.price()))
                           );
                           player.closeInventory();
                        } else {
                           this.rollbackAndRefund(player, plan, offer);
                        }
                     }
                  )
            );
         } catch (ReflectiveOperationException | RuntimeException exception) {
            this.pendingPurchases.remove(playerId);
            this.plugin.getLogger().severe("Rangänderung für " + player.getName() + " konnte nicht gespeichert werden: " + exception.getMessage());

            try {
               plan.rollback();
            } catch (RuntimeException rollbackFailure) {
               this.plugin
                  .getLogger()
                  .severe("LuckPerms-Ranggruppen konnten für " + player.getName() + " nicht vollständig zurückgesetzt werden: " + rollbackFailure.getMessage());
            } finally {
               this.refund(player, offer);
            }

            this.sendPath(player, "messages.purchase-failed", "&cDer Rangkauf ist fehlgeschlagen. Kristalle wurden erstattet.");
         }
      }
   }

   private void rollbackAndRefund(Player player, RankFeature.RankPlan plan, RankFeature.RankOffer offer) {
      this.plugin
         .getLogger()
         .severe(
            "LuckPerms konnte den Rang von "
               + player.getName()
               + " nicht speichern; GreenAttack setzt die Rang-Eltern-Gruppe zurück und erstattet die Kristalle."
         );

      try {
         plan.rollback();
      } catch (RuntimeException rollbackFailure) {
         this.plugin
            .getLogger()
            .severe("LuckPerms-Ranggruppen konnten für " + player.getName() + " nicht vollständig zurückgesetzt werden: " + rollbackFailure.getMessage());
      }

      try {
         plan.save()
            .whenComplete(
               (ignored, rollbackFailure) -> {
                  if (rollbackFailure != null) {
                     this.plugin
                        .getLogger()
                        .severe("Zurücksetzen der Rang-Eltern-Gruppe für " + player.getName() + " fehlgeschlagen: " + rollbackFailure.getMessage());
                  }
               }
            );
      } catch (ReflectiveOperationException | RuntimeException rollbackFailure) {
         this.plugin.getLogger().severe("Zurücksetzen der Rang-Eltern-Gruppe für " + player.getName() + " fehlgeschlagen: " + rollbackFailure.getMessage());
      }

      this.refund(player, offer);
      this.sendPath(player, "messages.purchase-failed", "&cDer Rangkauf ist fehlgeschlagen. Kristalle wurden erstattet.");
   }

   private void refund(Player player, RankFeature.RankOffer offer) {
      try {
         this.kristallFeature.depositBalance(player.getUniqueId(), offer.price());
      } catch (IOException | ArithmeticException refundFailure) {
         this.plugin
            .getLogger()
            .severe("Kristallerstattung für " + player.getName() + " fehlgeschlagen: " + refundFailure.getMessage() + ". Bitte das Konto manuell prüfen.");
         this.sendPath(player, "messages.refund-failed", "&cDie Erstattung ist fehlgeschlagen. Bitte wende dich an ein Teammitglied.");
      }
   }

   private RankFeature.RankPlan prepareRankPlan(Player player, RankFeature.RankOffer selected, List<RankFeature.RankOffer> offers) throws ReflectiveOperationException, RankFeature.RankPurchaseException {
      Plugin luckPermsPlugin = this.plugin.getServer().getPluginManager().getPlugin("LuckPerms");
      if (luckPermsPlugin != null && luckPermsPlugin.isEnabled()) {
         ClassLoader loader = luckPermsPlugin.getClass().getClassLoader();
         Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader);
         Object api = providerClass.getMethod("get").invoke(null);
         Object groupManager = invoke(api, "getGroupManager");
         List<String> resolvedGroupNames = new ArrayList<>(offers.size());

         for (RankFeature.RankOffer offer : offers) {
            String resolvedName = resolveGroupName(groupManager, offer.group());
            if (resolvedName == null && offer.key().equals(selected.key())) {
               throw new RankFeature.RankPurchaseException(
                  "messages.group-missing",
                  "&cDie LuckPerms-Gruppe &e" + offer.group() + " &cexistiert nicht. Es wurden keine Kristalle abgezogen.",
                  offer.group()
               );
            }

            resolvedGroupNames.add(resolvedName == null ? offer.group() : resolvedName);
         }

         Object userManager = invoke(api, "getUserManager");
         Object user = invoke(userManager, "getUser", player.getUniqueId());
         if (user == null) {
            throw new RankFeature.RankPurchaseException(
               "messages.user-unavailable", "&cDein LuckPerms-Profil ist noch nicht geladen. Es wurden keine Kristalle abgezogen."
            );
         }

         Object data = invoke(user, "data");
         if (invoke(data, "toCollection") instanceof Collection<?> existingNodes) {
            ArrayList managedNodes = new ArrayList();
            ArrayList existingRankNodes = new ArrayList();
            int currentRankIndex = -1;

            for (int selectedIndex = 0; selectedIndex < offers.size(); selectedIndex++) {
               Object node = createInheritanceNode(loader, resolvedGroupNames.get(selectedIndex));
               managedNodes.add(node);
               if (existingNodes.stream().anyMatch(node::equals)) {
                  existingRankNodes.add(node);
                  currentRankIndex = selectedIndex;
               }
            }

            int selectedIndex = offers.indexOf(selected);
            if (currentRankIndex >= selectedIndex) {
               throw new RankFeature.RankPurchaseException(
                  "messages.rank-already-owned", "&eDu besitzt diesen Rang bereits oder hast schon einen höheren Rang."
               );
            } else {
               return new RankFeature.RankPlan(userManager, user, data, managedNodes, existingRankNodes, managedNodes.get(selectedIndex));
            }
         } else {
            throw new ReflectiveOperationException("LuckPerms lieferte keine lesbare Gruppenliste.");
         }
      } else {
         throw new RankFeature.RankPurchaseException("messages.luckperms-unavailable", "&cLuckPerms ist nicht verfügbar. Es wurden keine Kristalle abgezogen.");
      }
   }

   private static Object createInheritanceNode(ClassLoader luckPermsLoader, String groupName) throws ReflectiveOperationException {
      Class<?> inheritanceNode = Class.forName("net.luckperms.api.node.types.InheritanceNode", true, luckPermsLoader);
      Object builder = inheritanceNode.getMethod("builder", String.class).invoke(null, groupName);
      return invoke(builder, "build");
   }

   private static String resolveGroupName(Object groupManager, String configuredName) throws ReflectiveOperationException {
      if (invoke(groupManager, "getGroup", configuredName) instanceof Optional<?> exactMatch && exactMatch.isPresent()) {
         return (String)invoke(exactMatch.get(), "getName");
      } else {
         Object loadedGroups = invoke(groupManager, "getLoadedGroups");
         if (loadedGroups instanceof Iterable) {
            for (Object group : (Iterable)loadedGroups) {
               if (invoke(group, "getName") instanceof String actualName && actualName.equalsIgnoreCase(configuredName)) {
                  return actualName;
               }
            }
         }

         return null;
      }
   }

   private List<RankFeature.RankOffer> loadOffers() {
      List<RankFeature.RankOffer> offers = new ArrayList<>();
      long previousPrice = 0L;
      ConfigurationSection ranks = this.config.getConfigurationSection("ranks");
      if (ranks == null) {
         this.plugin.getLogger().warning("Keine Rangangebote in features/rank/config.yml konfiguriert.");
         return List.of();
      }

      List<String> rankKeys = new ArrayList<>(ranks.getKeys(false));
      Set<String> configuredGroups = new HashSet<>();
      for (String key : rankKeys) {
         String path = "ranks." + key + ".";
         String group = this.config.getString(path + "group", key);
         String display = this.config.getString(path + "name", "&f" + key);
         String materialName = this.config.getString(path + "material", "PAPER");
         Material material = Material.matchMaterial(materialName == null ? "" : materialName);

         long price;
         try {
            price = CompactAmountParser.parse(this.config.getString(path + "price", "")).longValueExact();
         } catch (NumberFormatException | ArithmeticException exception) {
            price = -1L;
         }

         if (key == null || !key.matches("[A-Za-z0-9_-]+") || group == null || !group.matches("[A-Za-z0-9_-]+")
            || !configuredGroups.add(group.toLowerCase(Locale.ROOT)) || material == null || price <= previousPrice) {
            this.plugin
               .getLogger()
               .warning("Ungültiges Rangangebot in features/rank/config.yml: " + key + ". Schlüssel, eindeutige LuckPerms-Gruppe, Item und steigender Kristallpreis müssen gültig sein.");
            return List.of();
         }

         offers.add(new RankFeature.RankOffer(key, RankFeature.ChatColorText.strip(display), color(display), group, price, material));
         previousPrice = price;
      }

      return offers;
   }

   private ItemStack createItem(String materialName, String displayName, List<String> lore) {
      Material material = Material.matchMaterial(materialName == null ? "" : materialName);
      if (material == null) {
         material = Material.PAPER;
      }

      return this.createItem(material, displayName, lore);
   }

   private static String compactAmount(long amount) {
      if (amount >= 1000000L) {
         return BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(1000000L)).stripTrailingZeros().toPlainString() + "m";
      } else {
         return amount >= 1000L
            ? BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(1000L)).stripTrailingZeros().toPlainString() + "k"
            : Long.toString(amount);
      }
   }

   private ItemStack createItem(Material material, String displayName, List<String> lore) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(color(displayName));
         if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore.stream().map(FeatureModule::color).toList());
         }

         item.setItemMeta(meta);
      }

      return item;
   }

   private static Object invoke(Object receiver, String name, Object... args) throws ReflectiveOperationException {
      if (receiver == null) {
         throw new NoSuchMethodException("LuckPerms receiver is null: " + name);
      } else {
         Method method = findApiMethod(receiver.getClass(), name, args);
         if (method == null) {
            throw new NoSuchMethodException("LuckPerms API method not found: " + name);
         } else {
            return method.invoke(receiver, args);
         }
      }
   }

   private static Method findApiMethod(Class<?> type, String name, Object[] args) {
      if (type.isInterface()) {
         Method method = matchingMethod(type.getMethods(), name, args);
         if (method != null) {
            return method;
         }

         for (Class<?> parent : type.getInterfaces()) {
            method = findApiMethod(parent, name, args);
            if (method != null) {
               return method;
            }
         }

         return null;
      } else {
         for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> apiInterface : current.getInterfaces()) {
               Method method = findApiMethod(apiInterface, name, args);
               if (method != null) {
                  return method;
               }
            }
         }

         for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method method = matchingMethod(current.getMethods(), name, args);
            if (method != null) {
               return method;
            }
         }

         return null;
      }
   }

   private static Method matchingMethod(Method[] methods, String name, Object[] args) {
      for (Method method : methods) {
         if (method.getName().equals(name) && method.getParameterCount() == args.length) {
            Class<?>[] parameters = method.getParameterTypes();
            boolean compatible = true;

            for (int index = 0; index < args.length; index++) {
               if (args[index] != null && !parameters[index].isAssignableFrom(args[index].getClass())) {
                  compatible = false;
                  break;
               }
            }

            if (compatible) {
               return method;
            }
         }
      }

      return null;
   }

   private static final class ChatColorText {
      private static String strip(String text) {
         return ChatColor.stripColor(FeatureModule.color(text));
      }
   }

   private static final class RankMenuHolder implements InventoryHolder {
      private Inventory inventory;
      private final Map<Integer, RankOffer> offersBySlot = new HashMap<>();
      private int page;

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private record RankOffer(String key, String plainName, String displayName, String group, long price, Material material) {
   }

   private static final class RankPlan {
      private final Object userManager;
      private final Object user;
      private final Object data;
      private final List<Object> managedNodes;
      private final List<Object> removedNodes;
      private final Object targetNode;
      private boolean targetAdded;

      private RankPlan(Object userManager, Object user, Object data, List<Object> managedNodes, List<Object> removedNodes, Object targetNode) {
         this.userManager = userManager;
         this.user = user;
         this.data = data;
         this.managedNodes = managedNodes;
         this.removedNodes = new ArrayList<>(removedNodes);
         this.targetNode = targetNode;
      }

      private void apply() throws ReflectiveOperationException {
         for (Object node : this.removedNodes) {
            RankFeature.invoke(this.data, "remove", node);
         }

         RankFeature.invoke(this.data, "add", this.targetNode);
         this.targetAdded = true;
      }

      private CompletionStage<?> save() throws ReflectiveOperationException {
         if (RankFeature.invoke(this.userManager, "saveUser", this.user) instanceof CompletionStage<?> stage) {
            return stage;
         } else {
            throw new ReflectiveOperationException("LuckPerms saveUser did not return an asynchronous result.");
         }
      }

      private void rollback() {
         try {
            if (this.targetAdded) {
               RankFeature.invoke(this.data, "remove", this.targetNode);
            }

            for (Object node : this.removedNodes) {
               RankFeature.invoke(this.data, "add", node);
            }
         } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("LuckPerms-Gruppen konnten nicht vollständig zurückgesetzt werden.", exception);
         }
      }
   }

   private static final class RankPurchaseException extends Exception {
      private final String messagePath;
      private final String groupName;

      private RankPurchaseException(String messagePath, String message) {
         this(messagePath, message, null);
      }

      private RankPurchaseException(String messagePath, String message, String groupName) {
         super(message);
         this.messagePath = messagePath;
         this.groupName = groupName;
      }

      private String messagePath() {
         return this.messagePath;
      }

      private String groupName() {
         return this.groupName;
      }
   }
}

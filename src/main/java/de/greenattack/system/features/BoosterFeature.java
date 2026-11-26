package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class BoosterFeature extends FeatureModule implements CommandExecutor, TabCompleter, Listener {
   private static final List<String> BOOSTER_TYPES = List.of("fly", "break", "drop", "xp");
   private static final List<Material> ORE_MATERIALS = List.of(
      Material.DIAMOND_ORE,
      Material.DEEPSLATE_DIAMOND_ORE,
      Material.EMERALD_ORE,
      Material.DEEPSLATE_EMERALD_ORE,
      Material.IRON_ORE,
      Material.DEEPSLATE_IRON_ORE,
      Material.GOLD_ORE,
      Material.DEEPSLATE_GOLD_ORE,
      Material.COAL_ORE,
      Material.DEEPSLATE_COAL_ORE,
      Material.LAPIS_ORE,
      Material.DEEPSLATE_LAPIS_ORE
   );
   private static final List<Material> XP_ORES = List.of(
      Material.DIAMOND_ORE,
      Material.DEEPSLATE_DIAMOND_ORE,
      Material.EMERALD_ORE,
      Material.DEEPSLATE_EMERALD_ORE,
      Material.COAL_ORE,
      Material.DEEPSLATE_COAL_ORE,
      Material.LAPIS_ORE,
      Material.DEEPSLATE_LAPIS_ORE,
      Material.REDSTONE_ORE,
      Material.DEEPSLATE_REDSTONE_ORE,
      Material.NETHER_QUARTZ_ORE
   );

   public BoosterFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "booster");
      Bukkit.getPluginManager().registerEvents(this, plugin);
      Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
         return true;
      }

      if (command.getName().equalsIgnoreCase("booster")) {
         if (sender instanceof Player player) {
            if (!this.hasPermission(player, "permissions.use", "system.booster.use")) {
               return true;
            }

            this.openMenu(player);
            return true;
         } else {
            this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
            return true;
         }
      } else {
         if (!this.hasPermission(sender, "permissions.admin", "system.booster.admin")) {
            return true;
         }

         if (args.length != 3) {
            this.sendPath(sender, "messages.give-usage", "&7Verwendung: &f/boostergive <Spieler> <break|drop|xp> <Anzahl>");
            return true;
         }

         Player target = Bukkit.getPlayerExact(args[0]);
         if (target == null) {
            this.send(sender, this.formattedText("messages.player-not-found", "&cSpieler &e%player% &cwurde nicht gefunden.", "%player%", args[0]));
            return true;
         }

         String type = args[1].toLowerCase(Locale.ROOT);
         if (!this.isTypeEnabled(type)) {
            this.send(
               sender,
               this.config.getString("messages.invalid-type", "&cGültige Typen: %types%").replace("%types%", String.join(", ", this.availableBoosterTypes()))
            );
            return true;
         }

         int amount;
         try {
            amount = Integer.parseInt(args[2]);
         } catch (NumberFormatException exception) {
            this.sendPath(sender, "messages.invalid-amount", "&cBitte gib eine positive ganze Zahl an.");
            return true;
         }

         if (amount >= 1 && amount <= 1000000) {
            String countPath = this.countPath(target.getUniqueId(), type);
            this.config.set(countPath, this.config.getInt(countPath, 0) + amount);
            this.save();
            this.send(
               sender,
               this.formattedText(
                  "messages.given",
                  "&7Du hast &f%player% &e%amount%x %type%-Booster &7gegeben!",
                  "%player%",
                  target.getName(),
                  "%amount%",
                  Integer.toString(amount),
                  "%type%",
                  type
               )
            );
            this.send(
               target,
               this.formattedText(
                  "messages.received", "&7Du hast &e%amount%x %type%-Booster &7erhalten! &8(/booster)", "%amount%", Integer.toString(amount), "%type%", type
               )
            );
            return true;
         } else {
            this.sendPath(sender, "messages.invalid-amount", "&cBitte gib eine positive ganze Zahl an.");
            return true;
         }
      }
   }

   private boolean hasPermission(CommandSender sender, String path, String fallback) {
      if (this.hasFeaturePermission(sender, this.config.getString(path, fallback))) {
         return true;
      }

      this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
      return false;
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (this.isEnabled() && this.hasFeaturePermission(sender, this.config.getString("permissions.admin", "system.booster.admin"))) {
         if (!command.getName().equalsIgnoreCase("boostergive")) {
            return List.of();
         } else {
            String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 1) {
               return Bukkit.getOnlinePlayers()
                  .stream()
                  .<String>map(Player::getName)
                  .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                  .toList();
            } else if (args.length == 2) {
               return this.availableBoosterTypes().stream().filter(type -> type.startsWith(partial)).toList();
            } else {
               return args.length == 3 ? List.of("1", "5", "10", "15", "32", "64").stream().filter(amount -> amount.startsWith(partial)).toList() : List.of();
            }
         }
      } else {
         return List.of();
      }
   }

   private void openMenu(Player player) {
      BoosterFeature.BoosterHolder holder = new BoosterFeature.BoosterHolder();
      Inventory inventory = Bukkit.createInventory(holder, 27, color(this.config.getString("gui.title", "&a&lGreenAttack &8» &7&lBooster")));
      holder.inventory = inventory;
      ItemStack glass = this.named(Material.GRAY_STAINED_GLASS_PANE, " ");

      for (int slot = 0; slot < inventory.getSize(); slot++) {
         inventory.setItem(slot, glass);
      }

      for (BoosterFeature.BoosterType type : BoosterFeature.BoosterType.values()) {
         if (this.isTypeEnabled(type.id)) {
            inventory.setItem(type.slot, this.boosterItem(player, type));
         }
      }

      player.openInventory(inventory);
   }

   private ItemStack boosterItem(Player player, BoosterFeature.BoosterType type) {
      String base = "gui.items." + type.id + ".";
      Material material = Material.matchMaterial(this.config.getString(base + "material", type.material.name()));
      ItemStack item = new ItemStack(material == null ? type.material : material);
      ItemMeta meta = item.getItemMeta();
      if (meta == null) {
         return item;
      }

      int count = this.config.getInt(this.countPath(player.getUniqueId(), type.id), 0);
      long seconds = this.remainingSeconds(type.id);
      String status = seconds > 0L
         ? this.text("gui.status-active", "&aAktiv (&e%seconds%s&a)").replace("%seconds%", Long.toString(seconds))
         : this.text("gui.status-inactive", "&cInaktiv");
      String click = count > 0 ? this.text("gui.click-active", "&eKlicke zum Aktivieren!") : this.text("gui.click-empty", "&cDu besitzt keinen Booster!");
      meta.setDisplayName(color(this.config.getString(base + "name", type.defaultName)));
      List<String> lore = this.config.getStringList(base + "lore");
      if (lore.isEmpty()) {
         lore = type.defaultLore;
      }

      meta.setLore(
         lore.stream().map(line -> color(line.replace("%status%", status).replace("%count%", Integer.toString(count)).replace("%click%", click))).toList()
      );
      item.setItemMeta(meta);
      return item;
   }

   @EventHandler
   public void onBoosterMenuClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof BoosterFeature.BoosterHolder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getView().getTopInventory()) {
            BoosterFeature.BoosterType type = BoosterFeature.BoosterType.forSlot(event.getRawSlot());
            if (type != null && this.isTypeEnabled(type.id)) {
               if (!this.isEnabled()) {
                  this.sendPath(player, "messages.disabled", "&cDieses Feature ist deaktiviert.");
                  player.closeInventory();
               } else if (!this.hasPermission(player, "permissions.use", "system.booster.use")) {
                  player.closeInventory();
               } else {
                  String countPath = this.countPath(player.getUniqueId(), type.id);
                  int count = this.config.getInt(countPath, 0);
                  if (count <= 0) {
                     player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                     this.send(player, this.formattedText("messages.empty", "&cDu hast keine %type%-Booster im Vorrat!", "%type%", type.displayName));
                  } else {
                     player.closeInventory();
                     this.activate(player, type);
                  }
               }
            }
         }
      }
   }

   private void activate(Player player, BoosterFeature.BoosterType type) {
      String countPath = this.countPath(player.getUniqueId(), type.id);
      int count = this.config.getInt(countPath, 0);
      if (count > 0) {
         long now = System.currentTimeMillis();
         long activePathValue = this.config.getLong(this.activePath(type.id), 0L);
         long base = Math.max(now, activePathValue);
         long durationMillis = Math.max(1L, this.config.getLong("duration-seconds", 900L)) * 1000L;
         long activeUntil = base + durationMillis;
         this.config.set(countPath, count - 1);
         this.config.set(this.activePath(type.id), activeUntil);
         this.save();
         String message = activePathValue > now ? "messages.extended" : "messages.activated";
         String fallback = activePathValue > now
            ? "&f%player% &7hat den %booster%-Booster um &e%duration% &7verlängert!"
            : "&f%player% &7hat einen %booster%-Booster für &e%duration% &7aktiviert!";
         String formatted = this.formattedText(
            message, fallback, "%player%", player.getName(), "%booster%", type.displayName, "%duration%", this.formatDuration(durationMillis / 1000L)
         );
         this.broadcast(formatted);
         if (activePathValue <= now && this.config.getBoolean("sounds.activation", true)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
               online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.0F);
            }
         }
      }
   }

   private void tick() {
      long now = System.currentTimeMillis();
      boolean changed = false;

      for (BoosterFeature.BoosterType type : BoosterFeature.BoosterType.values()) {
         long activeUntil = this.config.getLong(this.activePath(type.id), 0L);
         if (activeUntil > 0L) {
            if (!this.isTypeEnabled(type.id)) {
               this.config.set(this.activePath(type.id), null);
               changed = true;
               this.onExpired(type);
            } else if (activeUntil <= now) {
               this.config.set(this.activePath(type.id), null);
               changed = true;
               this.onExpired(type);
            } else {
               this.apply(type);
            }
         }
      }

      if (changed) {
         this.save();
      }
   }

   private void apply(BoosterFeature.BoosterType type) {
      switch (type) {
         case FLY:
            Bukkit.getOnlinePlayers().forEach(player -> player.setAllowFlight(true));
            break;
         case BREAK:
            Bukkit.getOnlinePlayers().forEach(player -> {
               int effectSeconds = Math.max(1, this.config.getInt("effects.break-effect-seconds", 2));
               int hasteAmplifier = Math.max(0, this.config.getInt("effects.break-haste-level", 2) - 1);
               int speedAmplifier = Math.max(0, this.config.getInt("effects.break-speed-level", 2) - 1);
               player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, effectSeconds * 20, hasteAmplifier, true, false, true));
               player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, effectSeconds * 20, speedAmplifier, true, false, true));
            });
         case DROP:
         case XP:
      }
   }

   private void onExpired(BoosterFeature.BoosterType type) {
      this.broadcast(this.formattedText("messages.expired", "&7Der %booster%-Booster ist abgelaufen.", "%booster%", type.displayName));
      if (type == BoosterFeature.BoosterType.FLY) {
         for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOp() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)) {
               player.setFlying(false);
               player.setAllowFlight(this.plugin.getFlyFeature() != null && this.plugin.getFlyFeature().isPersonalFlyEnabled(player));
            }
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onOreBreak(BlockBreakEvent event) {
      Player player = event.getPlayer();
      Block block = event.getBlock();
      Material type = block.getType();
      if (this.isActive("xp") && XP_ORES.contains(type)) {
         ExperienceOrb orb = (ExperienceOrb)block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class);
         orb.setExperience(this.config.getInt("effects.bonus-xp", 3));
      }

      if (this.isActive("drop") && ORE_MATERIALS.contains(type)) {
         Material toolType = player.getInventory().getItemInMainHand().getType();
         List<String> configuredPickaxes = this.config.getStringList("effects.drop-pickaxes");
         if (configuredPickaxes.isEmpty()) {
            configuredPickaxes = List.of("IRON_PICKAXE", "DIAMOND_PICKAXE", "NETHERITE_PICKAXE");
         }

         if (!configuredPickaxes.stream().noneMatch(value -> value.equalsIgnoreCase(toolType.name()))) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            List<ItemStack> drops = new ArrayList<>(block.getDrops(tool, player));
            event.setCancelled(true);
            block.setType(Material.AIR, false);
            int multiplier = Math.max(1, this.config.getInt("effects.drop-multiplier", 2));

            for (ItemStack drop : drops) {
               int remaining = drop.getAmount() * multiplier;

               while (remaining > 0) {
                  ItemStack multiplied = drop.clone();
                  int stackAmount = Math.min(multiplied.getMaxStackSize(), remaining);
                  multiplied.setAmount(stackAmount);
                  block.getWorld().dropItemNaturally(block.getLocation(), multiplied);
                  remaining -= stackAmount;
               }
            }
         }
      }
   }

   private boolean isActive(String type) {
      return this.config.getLong(this.activePath(type), 0L) > System.currentTimeMillis();
   }

   public boolean isFlyBoosterActive() {
      return this.isEnabled() && this.isTypeEnabled("fly") && this.isActive("fly");
   }

   private boolean isTypeEnabled(String type) {
      return BOOSTER_TYPES.contains(type)
         && this.config.getBoolean("categories." + (type.equals("fly") ? "fly-booster" : type) + ".enabled", !type.equals("fly"));
   }

   private List<String> availableBoosterTypes() {
      return BOOSTER_TYPES.stream().filter(this::isTypeEnabled).toList();
   }

   private long remainingSeconds(String type) {
      long remaining = this.config.getLong(this.activePath(type), 0L) - System.currentTimeMillis();
      return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
   }

   private String countPath(UUID uuid, String type) {
      return "storage.counts." + uuid + "." + type;
   }

   private String activePath(String type) {
      return "storage.active-until." + type;
   }

   private void broadcast(String message) {
      Bukkit.broadcastMessage(this.prefix() + color(message));
   }

   private ItemStack named(Material material, String name) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(color(name));
         item.setItemMeta(meta);
      }

      return item;
   }

   private String formatDuration(long seconds) {
      long minutes = seconds / 60L;
      long remainingSeconds = seconds % 60L;
      if (minutes == 0L) {
         return remainingSeconds + " Sekunden";
      } else {
         return remainingSeconds == 0L ? minutes + " Minuten" : minutes + " Min. " + remainingSeconds + " Sek.";
      }
   }

   private String formattedText(String path, String fallback, String... replacements) {
      String value = this.config.getString(path, fallback);

      for (int index = 0; index + 1 < replacements.length; index += 2) {
         value = value.replace(replacements[index], replacements[index + 1]);
      }

      return color(value);
   }

   private static final class BoosterHolder implements InventoryHolder {
      private Inventory inventory;

      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private enum BoosterType {
      FLY(
         "fly",
         10,
         Material.FEATHER,
         "&a&lFly Booster",
         "&a&lFLY",
         List.of(
            "&7Aktiviert zeitweises Fliegen", "&7für alle Spieler auf dem Server.", "", "&8» &7Status: %status%", "&8» &7Dein Vorrat: &e%count%", "", "%click%"
         )
      ),
      BREAK(
         "break",
         12,
         Material.GOLDEN_PICKAXE,
         "&e&lAbbau Booster",
         "&e&lABBAU",
         List.of("&7Gibt allen Spielern Eile II", "&7und Schnelligkeit II.", "", "&8» &7Status: %status%", "&8» &7Dein Vorrat: &e%count%", "", "%click%")
      ),
      DROP(
         "drop",
         14,
         Material.DIAMOND_ORE,
         "&b&lDrop Booster",
         "&b&lDROP",
         List.of("&7Verdoppelt abgebaute Erze", "&7und seltene Ressourcen.", "", "&8» &7Status: %status%", "&8» &7Dein Vorrat: &e%count%", "", "%click%")
      ),
      XP(
         "xp",
         16,
         Material.EXPERIENCE_BOTTLE,
         "&d&lXP Booster",
         "&d&lXP",
         List.of("&7Gibt zusätzliche Erfahrung", "&7beim Abbau von Erzen.", "", "&8» &7Status: %status%", "&8» &7Dein Vorrat: &e%count%", "", "%click%")
      );

      private final String id;
      private final int slot;
      private final Material material;
      private final String defaultName;
      private final String displayName;
      private final List<String> defaultLore;

      BoosterType(String id, int slot, Material material, String defaultName, String displayName, List<String> defaultLore) {
         this.id = id;
         this.slot = slot;
         this.material = material;
         this.defaultName = defaultName;
         this.displayName = displayName;
         this.defaultLore = defaultLore;
      }

      private static BoosterFeature.BoosterType forSlot(int slot) {
         for (BoosterFeature.BoosterType type : values()) {
            if (type.slot == slot) {
               return type;
            }
         }

         return null;
      }
   }
}

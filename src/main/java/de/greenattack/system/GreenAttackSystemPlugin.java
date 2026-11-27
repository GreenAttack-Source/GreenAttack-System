package de.greenattack.system;

import com.sun.management.OperatingSystemMXBean;
import de.greenattack.system.economy.EconomyService;
import de.greenattack.system.economy.GreenAttackEconomyService;
import de.greenattack.system.features.AktionshausFeature;
import de.greenattack.system.features.AntiCheatFeature;
import de.greenattack.system.features.AdminFeature;
import de.greenattack.system.features.BoosterFeature;
import de.greenattack.system.features.ClanFeature;
import de.greenattack.system.features.ClaimFeature;
import de.greenattack.system.features.CodeFeature;
import de.greenattack.system.features.ECFeature;
import de.greenattack.system.features.EconomyFeature;
import de.greenattack.system.features.FlyFeature;
import de.greenattack.system.features.GamemodeFeature;
import de.greenattack.system.features.HalloweenFeature;
import de.greenattack.system.features.HauptWorldCommand;
import de.greenattack.system.features.HomeFeature;
import de.greenattack.system.features.ISignFeature;
import de.greenattack.system.features.ItemDatabaseFeature;
import de.greenattack.system.features.KristallFeature;
import de.greenattack.system.features.KitFeature;
import de.greenattack.system.features.MarketFeature;
import de.greenattack.system.features.MonitoringFeature;
import de.greenattack.system.features.MsgFeature;
import de.greenattack.system.features.MySqlPlayerBridgeFeature;
import de.greenattack.system.features.NewsFeature;
import de.greenattack.system.features.OneClickFeature;
import de.greenattack.system.features.PayFeature;
import de.greenattack.system.features.InventoryFeature;
import de.greenattack.system.features.PlaytimeFeature;
import de.greenattack.system.features.RankFeature;

import de.greenattack.system.features.RtpFeature;
import de.greenattack.system.features.SellFeature;
import de.greenattack.system.features.SpawnFeature;
import de.greenattack.system.features.TeleportFeature;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class GreenAttackSystemPlugin extends JavaPlugin implements Listener {
   private static final int FEATURE_FOLDER_LAYOUT_VERSION = 1;
   private static final Set<String> CURRENT_FEATURE_FOLDERS = Set.of(
      "admin", "aktionshaus", "anticheat", "booster", "claims", "clans", "codes", "create-cmds", "db", "economy", "enderchest",
      "fly", "gamemode", "halloween", "home", "inventory", "invsee-bridge", "isign", "kit", "kristall", "market",
      "monitoring", "msg", "mysqlplayerbridge", "news", "one-klick", "playtime", "rank", "rtp", "sell", "spawn", "teleportsystem"
   );
   private static final String DEFAULT_BYPASS_PERMISSION = "system.playerlimitbreak";
   private static final String ADMIN_BYPASS_PERMISSION = "system.playerlimitbreak.admin";
   private OperatingSystemMXBean operatingSystem;
   private String bypassPermission = "system.playerlimitbreak";
   private ISignFeature isignFeature;
   private NewsFeature newsFeature;
   private ItemDatabaseFeature itemDatabaseFeature;
   private HalloweenFeature halloweenFeature;
   private HomeFeature homeFeature;
   private PlaytimeFeature playtimeFeature;
   private OneClickFeature oneClickFeature;
   private PayFeature payFeature;
   private EconomyFeature economyFeature;
   private GamemodeFeature gamemodeFeature;
   private FlyFeature flyFeature;
   private MsgFeature msgFeature;
   private ECFeature ecFeature;
   private InventoryFeature inventoryFeature;
   private ClaimFeature claimFeature;
   private SpawnFeature spawnFeature;
   private RtpFeature rtpFeature;
   private MarketFeature marketFeature;
   private SellFeature sellFeature;
   private MonitoringFeature monitoringFeature;
   private MySqlPlayerBridgeFeature mySqlPlayerBridgeFeature;
   private BoosterFeature boosterFeature;
   private AktionshausFeature aktionshausFeature;
   private KristallFeature kristallFeature;
   private RankFeature rankFeature;
   private KitFeature kitFeature;
   private CodeFeature codeFeature;
   private ClanFeature clanFeature;
   private AdminFeature adminFeature;
   private AntiCheatFeature antiCheatFeature;
   private TeleportFeature teleportFeature;
   private Object kristallPlaceholderExpansion;
   private Object economyPlaceholderExpansion;
   private EconomyService economyService;
   private Object vaultEconomyProvider;
   private boolean placeholderApiWarningLogged;

   public void onEnable() {
      this.migrateLegacyDataFolder();
      this.saveDefaultConfig();
      this.migrateFeatureFolderLayout();
      this.ensureGlobalPrefix();
      this.ensureChatConfigDefaults();
      this.ensureEconomyConfigDefaults();
      this.setupEconomy();
      if (this.getConfig().contains("tab")) {
         this.getConfig().set("tab", null);
         this.saveConfig();
         this.getLogger().info("Veraltete GreenAttack-Einstellungen der Spielerliste wurden entfernt.");
      }

      if (this.getConfig().contains("barrier-visibility")) {
         this.getConfig().set("barrier-visibility", null);
         this.saveConfig();
         this.getLogger().info("Veraltete Barriere-Einstellungen wurden entfernt.");
      }

      this.operatingSystem = this.findOperatingSystemBean();
      this.applyPermissionSetting();
      this.inheritSystemPermissions();
      this.applyConfiguredSlotLimit();
      this.getServer().getPluginManager().registerEvents(this, this);
      this.getServer().getPluginManager().registerEvents(new RespawnBugFix(), this);
      this.applyAdvancementAnnouncementRule();
      this.adminFeature = new AdminFeature(this);
      this.getCommand("greenattack").setExecutor(this.adminFeature);
      this.getCommand("greenattack").setTabCompleter(this.adminFeature);
      this.getCommand("slots").setExecutor(this.adminFeature);
      this.getCommand("slots").setTabCompleter(this.adminFeature);
      try {
         this.getCommand("haupt-world").setExecutor(new HauptWorldCommand(this));
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"HauptWorldCommand\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.isignFeature = new ISignFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"ISignFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.newsFeature = new NewsFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"NewsFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.itemDatabaseFeature = new ItemDatabaseFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"ItemDatabaseFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.halloweenFeature = new HalloweenFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"HalloweenFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.monitoringFeature = new MonitoringFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"MonitoringFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.boosterFeature = new BoosterFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"BoosterFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.aktionshausFeature = new AktionshausFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"AktionshausFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.kristallFeature = new KristallFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"KristallFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.rankFeature = new RankFeature(this, this.kristallFeature);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"RankFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.homeFeature = new HomeFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"HomeFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.playtimeFeature = new PlaytimeFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"PlaytimeFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.oneClickFeature = new OneClickFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"OneClickFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.payFeature = new PayFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"PayFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.economyFeature = new EconomyFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"EconomyFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.gamemodeFeature = new GamemodeFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"GamemodeFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.flyFeature = new FlyFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"FlyFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.msgFeature = new MsgFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"MsgFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.ecFeature = new ECFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"ECFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.inventoryFeature = new InventoryFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"InventoryFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.claimFeature = new ClaimFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"ClaimFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.spawnFeature = new SpawnFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"SpawnFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.rtpFeature = new RtpFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"RtpFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.sellFeature = new SellFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"SellFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.marketFeature = new MarketFeature(this, this.sellFeature);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"MarketFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.mySqlPlayerBridgeFeature = new MySqlPlayerBridgeFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"MySqlPlayerBridgeFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.antiCheatFeature = new AntiCheatFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"AntiCheatFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.teleportFeature = new TeleportFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"TeleportFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.kitFeature = new KitFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"KitFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.codeFeature = new CodeFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"CodeFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      try {
         this.clanFeature = new ClanFeature(this);
      } catch (Throwable throwable) {
         this.getLogger().log(java.util.logging.Level.WARNING, "Feature \"ClanFeature\" konnte nicht gestartet werden und wird uebersprungen (fehlende Abhaengigkeit oder nicht unterstuetzte Server-/Paper-Version).\nAlle anderen Features laufen trotzdem weiter. Fehler:", throwable);
      }
      this.getCommand("isign").setExecutor(this.isignFeature);
      this.getCommand("news").setExecutor(this.newsFeature);
      this.getCommand("news").setTabCompleter(this.newsFeature);
      this.getCommand("newsconfig").setExecutor(this.newsFeature);
      this.getCommand("newsconfig").setTabCompleter(this.newsFeature);
      this.registerFeatureCommand("db", this.itemDatabaseFeature, this.itemDatabaseFeature);
      this.registerFeatureCommand("halloween", this.halloweenFeature, this.halloweenFeature);
      this.registerFeatureCommand("monitoring", this.monitoringFeature, this.monitoringFeature);
      this.registerFeatureCommand("performance", this.monitoringFeature, this.monitoringFeature);
      this.registerFeatureCommand("booster", this.boosterFeature, this.boosterFeature);
      this.registerFeatureCommand("boostergive", this.boosterFeature, this.boosterFeature);
      this.registerFeatureCommand("aktionshaus", this.aktionshausFeature, this.aktionshausFeature);
      this.registerFeatureCommand("ka", this.kristallFeature, this.kristallFeature);
      this.registerFeatureCommand("kristall", this.kristallFeature, this.kristallFeature);
      this.registerFeatureCommand("kristalle", this.kristallFeature, this.kristallFeature);
      this.registerFeatureCommand("rank", this.rankFeature, this.rankFeature);
      this.registerFeatureCommand("kit", this.kitFeature, this.kitFeature);
      this.registerFeatureCommand("home", this.homeFeature, this.homeFeature);
      this.registerFeatureCommand("sethome", this.homeFeature, this.homeFeature);
      this.registerFeatureCommand("delhome", this.homeFeature, this.homeFeature);
      this.registerFeatureCommand("renamehome", this.homeFeature, this.homeFeature);
      this.registerFeatureCommand("homeadmin", this.homeFeature, this.homeFeature);
      this.registerFeatureCommand("playtime", this.playtimeFeature, this.playtimeFeature);
      this.registerFeatureCommand("one-klick", this.oneClickFeature, null);
      this.registerFeatureCommand("pay", this.payFeature, this.payFeature);
      this.registerFeatureCommand("payinfo", this.payFeature, this.payFeature);
      this.registerFeatureCommand("eco", this.economyFeature, this.economyFeature);
      this.registerFeatureCommand("balance", this.economyFeature, this.economyFeature);
      this.registerFeatureCommand("balancetop", this.economyFeature, this.economyFeature);
      this.registerFeatureCommand("sell", this.sellFeature, this.sellFeature);
      this.registerFeatureCommand("gamemode", this.gamemodeFeature, this.gamemodeFeature);
      this.claimGamemodeCommandLabels();
      this.registerFeatureCommand("fly", this.flyFeature, this.flyFeature);
      this.claimFlyCommandLabel();
      this.registerFeatureCommand("msg", this.msgFeature, this.msgFeature);
      this.registerFeatureCommand("msglogs", this.msgFeature, null);
      this.claimMsgCommandLabels();
      this.registerFeatureCommand("ec", this.ecFeature, this.ecFeature);
      this.registerFeatureCommand("claim", this.claimFeature, this.claimFeature);

      for (String commandName : List.of("invsee", "ecsee")) {
         this.registerFeatureCommand(commandName, this.inventoryFeature, this.inventoryFeature);
      }

      this.claimInventoryCommandLabels();
      this.registerFeatureCommand("spawn", this.spawnFeature, this.spawnFeature);
      this.registerFeatureCommand("setspawn", this.spawnFeature, this.spawnFeature);
      this.claimSpawnCommandLabels();
      this.registerFeatureCommand("rtp", this.rtpFeature, this.rtpFeature);
      this.registerFeatureCommand("market", this.marketFeature, this.marketFeature);
      this.claimRtpCommandLabel();
      this.claimEconomyCommandLabels();
      this.registerFeatureCommand("tpa", this.teleportFeature, this.teleportFeature);
      this.registerFeatureCommand("tpaaccept", this.teleportFeature, this.teleportFeature);
      this.registerFeatureCommand("tpacancel", this.teleportFeature, this.teleportFeature);
      this.registerFeatureCommand("tpaauto", this.teleportFeature, this.teleportFeature);
      this.registerFeatureCommand("tpalookup", this.teleportFeature, this.teleportFeature);
      this.registerFeatureCommand("tpahere", this.teleportFeature, this.teleportFeature);
      this.registerFeatureCommand("tphere", this.teleportFeature, this.teleportFeature);
      this.registerFeatureCommand("tp", this.teleportFeature, this.teleportFeature);
      this.adminFeature.registerDelegatedCommand("newsconfig", this.newsFeature, this.newsFeature);
      this.adminFeature.registerDelegatedCommand("monitoring", this.monitoringFeature, this.monitoringFeature);
      this.adminFeature.registerDelegatedCommand("performance", this.monitoringFeature, this.monitoringFeature);
      this.adminFeature.registerDelegatedCommand("boostergive", this.boosterFeature, this.boosterFeature);
      this.adminFeature.registerDelegatedCommand("kit", this.kitFeature, this.kitFeature);
      this.adminFeature.registerDelegatedCommand("homeadmin", this.homeFeature, this.homeFeature);
      this.adminFeature.registerDelegatedCommand("msglogs", this.msgFeature, null);
      this.adminFeature.registerDelegatedCommand("setspawn", this.spawnFeature, this.spawnFeature);
      this.adminFeature.registerDelegatedCommand("codecreate", this.codeFeature, this.codeFeature);
      this.adminFeature.registerDelegatedCommand("codeconfig", this.codeFeature, this.codeFeature);
      this.adminFeature.registerDelegatedCommand("codedelete", this.codeFeature, this.codeFeature);
      this.adminFeature.registerDelegatedCommand("codereset", this.codeFeature, this.codeFeature);
      this.adminFeature.registerDelegatedCommand("codetop", this.codeFeature, this.codeFeature);
      this.adminFeature.registerDelegatedCommand("clanadmin", this.clanFeature, this.clanFeature);
      this.refreshOnlineCommandTrees();
      this.registerKristallPlaceholderExpansion();
      this.registerEconomyPlaceholderExpansion();
      this.inheritSystemPermissions();
      this.getLogger().info("GreenAttack System ist aktiv. Bypass-Permission: " + this.bypassPermission);
   }

   private void migrateLegacyDataFolder() {
      Path oldFolder = this.getDataFolder().toPath().resolveSibling("GreenCraft-System");
      Path newFolder = this.getDataFolder().toPath();
      if (!Files.isDirectory(oldFolder) || Files.exists(newFolder)) return;
      try (var paths = Files.walk(oldFolder)) {
         paths.forEach(source -> {
            try {
               Path target = newFolder.resolve(oldFolder.relativize(source));
               if (Files.isDirectory(source)) Files.createDirectories(target);
               else Files.copy(source, target);
            } catch (IOException exception) {
               throw new java.io.UncheckedIOException(exception);
            }
         });
         this.getLogger().info("Vorhandene GreenCraft-System-Daten wurden nach GreenAttack-System übernommen.");
      } catch (IOException | java.io.UncheckedIOException exception) {
         this.getLogger().log(java.util.logging.Level.SEVERE, "Alte Plugin-Daten konnten nicht vollständig übernommen werden.", exception);
      }
   }

   private void migrateFeatureFolderLayout() {
      String versionPath = "internal.feature-folder-layout-version";
      if (this.getConfig().contains(versionPath, true)
         && this.getConfig().getInt(versionPath, 0) >= FEATURE_FOLDER_LAYOUT_VERSION) {
         return;
      }

      Path features = this.getDataFolder().toPath().resolve("features");
      boolean success = true;
      if (Files.isDirectory(features)) {
         try (var children = Files.list(features)) {
            for (Path child : children.toList()) {
               String name = child.getFileName().toString();
               if (Files.isDirectory(child)) {
                  if (!CURRENT_FEATURE_FOLDERS.contains(name.toLowerCase(Locale.ROOT))) {
                     this.archiveObsoleteFeaturePath(child);
                  }
               } else if (name.toLowerCase(Locale.ROOT).endsWith(".yml")) {
                  String featureName = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
                  if (!CURRENT_FEATURE_FOLDERS.contains(featureName)) {
                     this.archiveObsoleteFeaturePath(child);
                  }
               }
            }
         } catch (IOException exception) {
            success = false;
            this.getLogger().log(java.util.logging.Level.SEVERE, "Veraltete Feature-Ordner konnten nicht vollständig sortiert werden.", exception);
         }
      }

      if (success) {
         this.getConfig().set(versionPath, FEATURE_FOLDER_LAYOUT_VERSION);
         this.saveConfig();
         this.getLogger().info("Feature-Ordner wurden aktualisiert. Vorhandene Einstellungen in aktiven Features bleiben erhalten.");
      }
   }

   private void archiveObsoleteFeaturePath(Path source) throws IOException {
      Path archive = this.getDataFolder().toPath().resolve("legacy-feature-backup").resolve("layout-v" + FEATURE_FOLDER_LAYOUT_VERSION);
      Files.createDirectories(archive);
      Path target = archive.resolve(source.getFileName());
      if (Files.exists(target)) {
         target = archive.resolve(source.getFileName() + "-" + System.currentTimeMillis());
      }
      Files.move(source, target);
      this.getLogger().info("Veralteter Feature-Pfad wurde erhalten und aus features/ verschoben: " + source.getFileName());
   }

   public FlyFeature getFlyFeature() {
      return this.flyFeature;
   }

   public KristallFeature getKristallFeature() {
      return this.kristallFeature;
   }

   public TeleportFeature getTeleportFeature() {
      return this.teleportFeature;
   }

   public MsgFeature getMsgFeature() {
      return this.msgFeature;
   }

   private void ensureGlobalPrefix() {
      String current = this.getConfig().getString("messages.prefix");
      if (current == null
         || current.isBlank()
         || current.equals("&8[&aGreenAttack&2-System&8] &r")
         || current.equals("&8[&aGreenAttack-System&8] &r")
         || current.equals("&4OPSUCHT &8» &7")) {
         this.getConfig().set("messages.prefix", "&2GreenAttack-Plugin &8» &7");
         this.saveConfig();
      }
   }

   public void onDisable() {
      if (this.rtpFeature != null) {
         this.rtpFeature.shutdownFeature();
      }

      if (this.playtimeFeature != null) {
         this.playtimeFeature.shutdownFeature();
      }

      if (this.msgFeature != null) {
         this.msgFeature.shutdownFeature();
      }

      if (this.homeFeature != null) {
         this.homeFeature.shutdownFeature();
      }

      if (this.mySqlPlayerBridgeFeature != null) {
         this.mySqlPlayerBridgeFeature.shutdownFeature();
      }

      if (this.inventoryFeature != null) {
         this.inventoryFeature.shutdownFeature();
      }

      if (this.sellFeature != null) {
         this.sellFeature.shutdownFeature();
      }

      if (this.antiCheatFeature != null) {
         this.antiCheatFeature.shutdownFeature();
      }

      if (this.teleportFeature != null) {
         this.teleportFeature.shutdownFeature();
      }

      this.shutdownEconomy();
      this.unregisterKristallPlaceholderExpansion();
      this.unregisterEconomyPlaceholderExpansion();
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPluginEnable(PluginEnableEvent event) {
      this.inheritSystemPermissions();
   }

   private void inheritSystemPermissions() {
      PluginManager pluginManager = this.getServer().getPluginManager();
      Permission adminPermission = pluginManager.getPermission("system.admin");
      if (adminPermission == null) {
         this.getLogger().warning("system.admin konnte nicht gefunden werden; system.* wird nicht vererbt.");
      } else {
         for (Permission permission : pluginManager.getPermissions()) {
            String name = permission.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("system.")
               && permission != adminPermission
               && !Boolean.TRUE.equals(adminPermission.getChildren().get(name))) {
               permission.addParent(adminPermission, true);
            }
         }
      }
   }

   public boolean hasSystemPermission(CommandSender sender, String permission) {
      if (permission == null || permission.isBlank()) {
         return false;
      }
      if (sender.hasPermission(permission)) {
         return true;
      }
      if (permission.toLowerCase(Locale.ROOT).startsWith("system.") && !permission.equalsIgnoreCase("system.admin") && sender.hasPermission("system.admin")) {
         return true;
      }
      // Server-Operatoren duerfen jedes system.*-Recht nutzen, auch wenn die Berechtigungs-Plugin-Konfiguration
      // (noch) nicht geladen oder fehlerhaft ist. Das verhindert, dass ein falsch gesetztes LuckPerms-Recht
      // Admins komplett aussperrt.
      if (sender instanceof Player player && player.isOp()) {
         return true;
      }
      if (sender instanceof Player diagnosticPlayer && this.getConfig().getBoolean("debug.log-permission-denials", false)) {
         this.getLogger().info("Berechtigung verweigert: " + diagnosticPlayer.getName() + " hat nicht '" + permission
            + "' (auch nicht ueber system.admin). Pruefe das mit /lp user " + diagnosticPlayer.getName() + " permission check " + permission + ".");
      }
      return false;
   }

   private void ensureEconomyConfigDefaults() {
      boolean changed = false;
      if (!this.getConfig().contains("economy.enabled", true)) {
         this.getConfig().set("economy.enabled", true);
         changed = true;
      }

      String oldProvider = this.getConfig().getString("economy.provider", "greenattack");
      if (!"greenattack".equalsIgnoreCase(oldProvider)) {
         this.getConfig().set("economy.provider", "greenattack");
         changed = true;
      }

      if (!this.getConfig().contains("economy.starting-balance")) {
         this.getConfig().set("economy.starting-balance", 0.0);
         changed = true;
      }

      if (!this.getConfig().contains("economy.minimum-balance")) {
         this.getConfig().set("economy.minimum-balance", 0.0);
         changed = true;
      }

      if (!this.getConfig().contains("economy.maximum-balance")) {
         this.getConfig().set("economy.maximum-balance", 1.0E12);
         changed = true;
      }

      if (!this.getConfig().contains("economy.allow-negative")) {
         this.getConfig().set("economy.allow-negative", false);
         changed = true;
      }

      if (!this.getConfig().contains("economy.import-essentials-userdata")) {
         this.getConfig().set("economy.import-essentials-userdata", true);
         changed = true;
      }

      if (!this.getConfig().contains("economy.currency.symbol")) {
         this.getConfig().set("economy.currency.symbol", "$");
         changed = true;
      }

      if (!this.getConfig().contains("economy.currency.position")) {
         this.getConfig().set("economy.currency.position", "suffix");
         changed = true;
      }

      if (!this.getConfig().contains("economy.currency.decimal-places")) {
         this.getConfig().set("economy.currency.decimal-places", 2);
         changed = true;
      }

      if (!this.getConfig().contains("economy.currency.singular")) {
         this.getConfig().set("economy.currency.singular", "Dollar");
         changed = true;
      }

      if (!this.getConfig().contains("economy.currency.plural")) {
         this.getConfig().set("economy.currency.plural", "Dollar");
         changed = true;
      }

      if (!this.getConfig().contains("placeholderapi.enabled", true)) {
         this.getConfig().set("placeholderapi.enabled", true);
         changed = true;
      }

      if (changed) {
         this.saveConfig();
      }
   }

   private void ensureChatConfigDefaults() {
      if (!this.getConfig().contains("chat.advancement-announcements")) {
         this.getConfig().set("chat.advancement-announcements", false);
         this.saveConfig();
      }
   }

   private void applyAdvancementAnnouncementRule() {
      boolean announce = this.getConfig().getBoolean("chat.advancement-announcements", false);
      for (World world : this.getServer().getWorlds()) {
         world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, announce);
      }
   }

   @EventHandler
   public void onWorldLoad(WorldLoadEvent event) {
      event.getWorld().setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, this.getConfig().getBoolean("chat.advancement-announcements", false));
   }

   private void registerFeatureCommand(String name, CommandExecutor executor, TabCompleter completer) {
      this.getCommand(name).setExecutor(executor);
      if (completer != null) {
         this.getCommand(name).setTabCompleter(completer);
      }
   }

   private void claimEconomyCommandLabels() {
      try {
         Object commandMap = this.getServer().getClass().getMethod("getCommandMap").invoke(this.getServer());
         Field knownCommandsField = null;

         for (Class<?> type = commandMap.getClass(); type != null && knownCommandsField == null; type = type.getSuperclass()) {
            try {
               knownCommandsField = type.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException var10) {
            }
         }

         if (knownCommandsField == null) {
            throw new NoSuchFieldException("knownCommands");
         }

         knownCommandsField.setAccessible(true);
         Object knownCommandsObject = knownCommandsField.get(commandMap);
         if (!(knownCommandsObject instanceof Map<?, ?>)) {
            throw new IllegalStateException("CommandMap besitzt keine Known-Commands-Map.");
         }

         @SuppressWarnings("unchecked")
         Map<String, Command> knownCommands = (Map<String, Command>)knownCommandsObject;

         for (String commandName : List.of("pay", "eco", "balance", "balancetop")) {
            PluginCommand command = this.getCommand(commandName);
            if (command != null) {
               knownCommands.put(commandName.toLowerCase(Locale.ROOT), command);

               for (String alias : command.getAliases()) {
                  knownCommands.put(alias.toLowerCase(Locale.ROOT), command);
               }
            }
         }
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.getLogger()
            .warning(
               "GreenAttack konnte die Economy-Befehle nicht vor gleichnamigen Befehlen priorisieren. Nutze vorübergehend /greenattack-system:pay: "
                  + exception.getMessage()
            );
      }
   }

   private void claimInventoryCommandLabels() {
      try {
         Object commandMap = this.getServer().getClass().getMethod("getCommandMap").invoke(this.getServer());
         Field knownCommandsField = null;

         for (Class<?> type = commandMap.getClass(); type != null && knownCommandsField == null; type = type.getSuperclass()) {
            try {
               knownCommandsField = type.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException var10) {
            }
         }

         if (knownCommandsField == null) {
            throw new NoSuchFieldException("knownCommands");
         }

         knownCommandsField.setAccessible(true);
         Object knownCommandsObject = knownCommandsField.get(commandMap);
         if (!(knownCommandsObject instanceof Map<?, ?>)) {
            throw new IllegalStateException("CommandMap besitzt keine Known-Commands-Map.");
         }

         @SuppressWarnings("unchecked")
         Map<String, Command> knownCommands = (Map<String, Command>)knownCommandsObject;

         for (String commandName : List.of("invsee", "ec", "ecsee")) {
            PluginCommand command = this.getCommand(commandName);
            if (command != null) {
               knownCommands.put(commandName.toLowerCase(Locale.ROOT), command);

               for (String alias : command.getAliases()) {
                  knownCommands.put(alias.toLowerCase(Locale.ROOT), command);
               }
            }
         }
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.getLogger()
            .warning("GreenAttack konnte /invsee, /ec und /ecsee nicht vor gleichnamigen EssentialsX-Befehlen priorisieren: " + exception.getMessage());
      }
   }

   private void refreshOnlineCommandTrees() {
      for (Player player : this.getServer().getOnlinePlayers()) {
         player.updateCommands();
      }
   }

   private void claimGamemodeCommandLabels() {
      try {
         Object commandMap = this.getServer().getClass().getMethod("getCommandMap").invoke(this.getServer());
         Field knownCommandsField = null;

         for (Class<?> type = commandMap.getClass(); type != null && knownCommandsField == null; type = type.getSuperclass()) {
            try {
               knownCommandsField = type.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException var8) {
            }
         }

         if (knownCommandsField == null) {
            throw new NoSuchFieldException("knownCommands");
         }

         knownCommandsField.setAccessible(true);
         Object knownCommandsObject = knownCommandsField.get(commandMap);
         if (!(knownCommandsObject instanceof Map<?, ?>)) {
            throw new IllegalStateException("CommandMap besitzt keine Known-Commands-Map.");
         }

         @SuppressWarnings("unchecked")
         Map<String, Command> knownCommands = (Map<String, Command>)knownCommandsObject;

         PluginCommand command = this.getCommand("gamemode");
         if (command == null) {
            return;
         }

         knownCommands.put("gamemode", command);

         for (String alias : command.getAliases()) {
            knownCommands.put(alias.toLowerCase(Locale.ROOT), command);
         }
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.getLogger()
            .warning(
               "GreenAttack konnte die Gamemode-Befehle nicht vor gleichnamigen Befehlen priorisieren. Nutze vorübergehend /greenattack-system:gamemode: "
                  + exception.getMessage()
            );
      }
   }

   private void claimFlyCommandLabel() {
      try {
         Object commandMap = this.getServer().getClass().getMethod("getCommandMap").invoke(this.getServer());
         Field knownCommandsField = null;

         for (Class<?> type = commandMap.getClass(); type != null && knownCommandsField == null; type = type.getSuperclass()) {
            try {
               knownCommandsField = type.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException var6) {
            }
         }

         if (knownCommandsField == null) {
            throw new NoSuchFieldException("knownCommands");
         }

         knownCommandsField.setAccessible(true);
         Object knownCommandsObject = knownCommandsField.get(commandMap);
         if (!(knownCommandsObject instanceof Map<?, ?>)) {
            throw new IllegalStateException("CommandMap besitzt keine Known-Commands-Map.");
         }

         @SuppressWarnings("unchecked")
         Map<String, Command> knownCommands = (Map<String, Command>)knownCommandsObject;

         PluginCommand command = this.getCommand("fly");
         if (command != null) {
            knownCommands.put("fly", command);
         }
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.getLogger()
            .warning(
               "GreenAttack konnte /fly nicht vor dem gleichnamigen Befehl priorisieren. Nutze vorübergehend /greenattack-system:fly: " + exception.getMessage()
            );
      }
   }

   private void claimMsgCommandLabels() {
      try {
         Object commandMap = this.getServer().getClass().getMethod("getCommandMap").invoke(this.getServer());
         Field knownCommandsField = null;
         for (Class<?> type = commandMap.getClass(); type != null && knownCommandsField == null; type = type.getSuperclass()) {
            try {
               knownCommandsField = type.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException ignored) {
            }
         }
         if (knownCommandsField == null) {
            throw new NoSuchFieldException("knownCommands");
         }
         knownCommandsField.setAccessible(true);
         Object knownCommandsObject = knownCommandsField.get(commandMap);
         if (!(knownCommandsObject instanceof Map<?, ?>)) {
            throw new IllegalStateException("CommandMap besitzt keine Known-Commands-Map.");
         }
         @SuppressWarnings("unchecked")
         Map<String, Command> knownCommands = (Map<String, Command>)knownCommandsObject;
         for (String commandName : List.of("msg", "msglogs")) {
            PluginCommand command = this.getCommand(commandName);
            if (command != null) {
               knownCommands.put(commandName.toLowerCase(Locale.ROOT), command);
               for (String alias : command.getAliases()) {
                  knownCommands.put(alias.toLowerCase(Locale.ROOT), command);
               }
            }
         }
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.getLogger().warning("GreenAttack konnte /msg und /msglogs nicht vor gleichnamigen Befehlen priorisieren: " + exception.getMessage());
      }
   }

   private void claimSpawnCommandLabels() {
      try {
         Object commandMap = this.getServer().getClass().getMethod("getCommandMap").invoke(this.getServer());
         Field knownCommandsField = null;

         for (Class<?> type = commandMap.getClass(); type != null && knownCommandsField == null; type = type.getSuperclass()) {
            try {
               knownCommandsField = type.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException var10) {
            }
         }

         if (knownCommandsField == null) {
            throw new NoSuchFieldException("knownCommands");
         }

         knownCommandsField.setAccessible(true);
         Object knownCommandsObject = knownCommandsField.get(commandMap);
         if (!(knownCommandsObject instanceof Map<?, ?>)) {
            throw new IllegalStateException("CommandMap besitzt keine Known-Commands-Map.");
         }

         @SuppressWarnings("unchecked")
         Map<String, Command> knownCommands = (Map<String, Command>)knownCommandsObject;

         for (String name : List.of("spawn", "setspawn")) {
            PluginCommand command = this.getCommand(name);
            if (command != null) {
               knownCommands.put(name, command);

               for (String alias : command.getAliases()) {
                  knownCommands.put(alias.toLowerCase(Locale.ROOT), command);
               }
            }
         }
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.getLogger().warning("GreenAttack konnte die Spawn-Befehle nicht vor EssentialsX Spawn priorisieren: " + exception.getMessage());
      }
   }

   private void claimRtpCommandLabel() {
      try {
         Object commandMap = this.getServer().getClass().getMethod("getCommandMap").invoke(this.getServer());
         Field knownCommandsField = null;

         for (Class<?> type = commandMap.getClass(); type != null && knownCommandsField == null; type = type.getSuperclass()) {
            try {
               knownCommandsField = type.getDeclaredField("knownCommands");
            } catch (NoSuchFieldException var6) {
            }
         }

         if (knownCommandsField == null) {
            throw new NoSuchFieldException("knownCommands");
         }

         knownCommandsField.setAccessible(true);
         Object knownCommandsObject = knownCommandsField.get(commandMap);
         if (!(knownCommandsObject instanceof Map<?, ?>)) {
            throw new IllegalStateException("CommandMap besitzt keine Known-Commands-Map.");
         }

         @SuppressWarnings("unchecked")
         Map<String, Command> knownCommands = (Map<String, Command>)knownCommandsObject;

         PluginCommand command = this.getCommand("rtp");
         if (command != null) {
            knownCommands.put("rtp", command);
         }
      } catch (ReflectiveOperationException | RuntimeException exception) {
         this.getLogger()
            .warning(
               "GreenAttack konnte /rtp nicht vor gleichnamigen Befehlen priorisieren. Nutze vorübergehend /greenattack-system:rtp: " + exception.getMessage()
            );
      }
   }

   public void reloadFromAdmin(CommandSender sender) {
      this.reloadConfig();
      this.ensureChatConfigDefaults();
      this.setupEconomy();
      this.applyPermissionSetting();
      this.applyConfiguredSlotLimit();
      this.applyAdvancementAnnouncementRule();
      this.reloadFeatures();
      this.sendConfiguredMessage(sender, "messages.reload-success", "&aKonfiguration wurde neu geladen.");
      this.getLogger().info(sender.getName() + " hat die Konfiguration neu geladen.");
   }

   private void reloadFeatures() {
      if (this.adminFeature != null) {
         this.adminFeature.reload();
      }

      if (this.isignFeature != null) {
         this.isignFeature.reload();
      }

      if (this.newsFeature != null) {
         this.newsFeature.reload();
      }

      if (this.itemDatabaseFeature != null) {
         this.itemDatabaseFeature.reload();
      }

      if (this.halloweenFeature != null) {
         this.halloweenFeature.reload();
      }

      if (this.monitoringFeature != null) {
         this.monitoringFeature.reload();
      }

      if (this.boosterFeature != null) {
         this.boosterFeature.reload();
      }

      if (this.aktionshausFeature != null) {
         this.aktionshausFeature.reloadFeature();
      }

      if (this.kristallFeature != null) {
         this.kristallFeature.reload();
      }

      if (this.rankFeature != null) {
         this.rankFeature.reload();
      }

      if (this.homeFeature != null) {
         this.homeFeature.reloadFeature();
      }

      if (this.playtimeFeature != null) {
         this.playtimeFeature.reloadFeature();
      }

      if (this.oneClickFeature != null) {
         this.oneClickFeature.reload();
      }

      if (this.payFeature != null) {
         this.payFeature.reload();
      }

      if (this.economyFeature != null) {
         this.economyFeature.reload();
      }

      if (this.gamemodeFeature != null) {
         this.gamemodeFeature.reload();
      }

      if (this.flyFeature != null) {
         this.flyFeature.reload();
      }

      if (this.msgFeature != null) {
         this.msgFeature.reloadFeature();
      }

      if (this.ecFeature != null) {
         this.ecFeature.reloadFeature();
      }

      if (this.inventoryFeature != null) {
         this.inventoryFeature.reloadFeature();
      }

      if (this.claimFeature != null) {
         this.claimFeature.reload();
      }

      if (this.spawnFeature != null) {
         this.spawnFeature.reloadFeature();
      }

      if (this.rtpFeature != null) {
         this.rtpFeature.reload();
      }

      if (this.marketFeature != null) {
         this.marketFeature.reloadFeature();
      }

      if (this.mySqlPlayerBridgeFeature != null) {
         this.mySqlPlayerBridgeFeature.reloadFeature();
      }

      if (this.teleportFeature != null) {
         this.teleportFeature.reloadFeature();
      }

      if (this.antiCheatFeature != null) {
         this.antiCheatFeature.reloadFeature();
      }

      this.inheritSystemPermissions();
      if (this.getConfig().getBoolean("placeholderapi.enabled", true)) {
         this.registerKristallPlaceholderExpansion();
         this.registerEconomyPlaceholderExpansion();
      } else {
         this.unregisterKristallPlaceholderExpansion();
         this.unregisterEconomyPlaceholderExpansion();
      }
   }

   private void registerKristallPlaceholderExpansion() {
      if (this.kristallPlaceholderExpansion == null) {
         if (this.kristallFeature != null
            && this.getConfig().getBoolean("placeholderapi.enabled", true)
            && this.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
               Class<?> expansionClass = Class.forName(
                  "de.greenattack.system.features.KristallPlaceholderExpansion", true, GreenAttackSystemPlugin.class.getClassLoader()
               );
               Object expansion = expansionClass.getConstructor(GreenAttackSystemPlugin.class, KristallFeature.class).newInstance(this, this.kristallFeature);
               boolean registered = (Boolean)expansionClass.getMethod("register").invoke(expansion);
               if (registered) {
                  this.kristallPlaceholderExpansion = expansion;
                  this.getLogger().info("Kristall-Placeholder für PlaceholderAPI sind aktiv (%kristall_balance%).");
               } else {
                  this.getLogger().warning("Die Kristall-Placeholder konnten nicht bei PlaceholderAPI registriert werden.");
               }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
               this.getLogger().warning("Die Kristall-Placeholder konnten nicht geladen werden: " + exception.getMessage());
            }
         }
      }
   }

   private void unregisterKristallPlaceholderExpansion() {
      if (this.kristallPlaceholderExpansion != null) {
         try {
            this.kristallPlaceholderExpansion.getClass().getMethod("unregister").invoke(this.kristallPlaceholderExpansion);
         } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            this.getLogger().warning("Die Kristall-Placeholder konnten nicht entfernt werden: " + exception.getMessage());
         } finally {
            this.kristallPlaceholderExpansion = null;
         }
      }
   }

   private void registerEconomyPlaceholderExpansion() {
      if (this.economyPlaceholderExpansion == null) {
         if (this.getConfig().getBoolean("placeholderapi.enabled", true) && this.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
               Class<?> expansionClass = Class.forName(
                  "de.greenattack.system.economy.EconomyPlaceholderExpansion", true, GreenAttackSystemPlugin.class.getClassLoader()
               );
               Object expansion = expansionClass.getConstructor(GreenAttackSystemPlugin.class).newInstance(this);
               boolean registered = (Boolean)expansionClass.getMethod("register").invoke(expansion);
               if (registered) {
                  this.economyPlaceholderExpansion = expansion;
                  this.getLogger().info("GreenAttack-Economy-Placeholder für PlaceholderAPI sind aktiv (%greenattack_eco_balance_commas%).");
               } else {
                  this.getLogger().warning("Die GreenAttack-Economy-Placeholder konnten nicht bei PlaceholderAPI registriert werden.");
               }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
               this.getLogger().warning("Die GreenAttack-Economy-Placeholder konnten nicht geladen werden: " + exception.getMessage());
            }
         }
      }
   }

   private void unregisterEconomyPlaceholderExpansion() {
      if (this.economyPlaceholderExpansion != null) {
         try {
            this.economyPlaceholderExpansion.getClass().getMethod("unregister").invoke(this.economyPlaceholderExpansion);
         } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            this.getLogger().warning("Die GreenAttack-Economy-Placeholder konnten nicht entfernt werden: " + exception.getMessage());
         } finally {
            this.economyPlaceholderExpansion = null;
         }
      }
   }

   public void setSlotLimit(CommandSender sender, String input) {
      int slots;
      try {
         slots = Integer.parseInt(input);
      } catch (NumberFormatException exception) {
         this.sendPrefixedMessage(sender, "&cBitte gib eine ganze Zahl zwischen 1 und 10000 an.");
         return;
      }

      if (slots >= 1 && slots <= 10000) {
         this.getConfig().set("slots.max-players", slots);
         this.saveConfig();
         this.getServer().setMaxPlayers(slots);
         String message = this.getConfig().getString("messages.slots-updated", "&aSpielerlimit dieses Servers auf &e%slots% &agesetzt.");
         this.sendPrefixedMessage(sender, message.replace("%slots%", Integer.toString(slots)));
         this.getLogger().info(sender.getName() + " hat das Spielerlimit dieses Servers auf " + slots + " gesetzt.");
      } else {
         this.sendPrefixedMessage(sender, "&cDas Spielerlimit muss zwischen 1 und 10000 liegen.");
      }
   }

   private void applyConfiguredSlotLimit() {
      int slots = this.getConfig().getInt("slots.max-players", -1);
      if (slots != -1) {
         if (slots >= 1 && slots <= 10000) {
            this.getServer().setMaxPlayers(slots);
         } else {
            this.getLogger().warning("Ungültiger Wert für slots.max-players; verwende das Spielerlimit aus server.properties.");
         }
      }
   }

   private void sendConfiguredMessage(CommandSender sender, String path, String fallback) {
      this.sendPrefixedMessage(sender, this.getConfig().getString(path, fallback));
   }

   private void sendPrefixedMessage(CommandSender sender, String message) {
      String prefix = this.getConfig().getString("messages.prefix", "&2GreenAttack-Plugin &8» &7");
      String output = prefix + message;
      if (sender instanceof Player player) {
         output = this.applyPlaceholders(player, output);
      }

      sender.sendMessage(ChatColor.translateAlternateColorCodes('&', output));
   }

   private void setupEconomy() {
      this.shutdownEconomy();
      this.economyService = null;
      if (!this.getConfig().getBoolean("economy.enabled", true)) {
         this.getLogger().info("GreenAttack-Economy ist in der Hauptkonfiguration deaktiviert.");
      } else {
         GreenAttackEconomyService greenAttackEconomy = new GreenAttackEconomyService(this);
         this.economyService = greenAttackEconomy;
         if (!this.getServer().getPluginManager().isPluginEnabled("Vault")) {
            this.getLogger().info("GreenAttack-Economy läuft lokal. Vault wurde nicht gefunden; andere Plugins können sie nicht über Vault nutzen.");
         } else {
            try {
               Class<?> bridge = Class.forName(
                  "de.greenattack.system.economy.GreenAttackVaultEconomyProvider", true, GreenAttackSystemPlugin.class.getClassLoader()
               );
               Method register = bridge.getMethod("register", GreenAttackSystemPlugin.class, GreenAttackEconomyService.class);
               this.vaultEconomyProvider = register.invoke(null, this, greenAttackEconomy);
               this.getLogger().info("GreenAttack-Economy ist aktiv und als Vault-Anbieter registriert.");
            } catch (ReflectiveOperationException | LinkageError exception) {
               this.getLogger().warning("GreenAttack-Economy läuft lokal, konnte aber nicht als Vault-Anbieter registriert werden: " + exception.getMessage());
            }
         }
      }
   }

   private void shutdownEconomy() {
      if (this.vaultEconomyProvider != null) {
         try {
            Method unregister = this.vaultEconomyProvider.getClass().getMethod("unregister");
            unregister.invoke(this.vaultEconomyProvider);
         } catch (ReflectiveOperationException | LinkageError exception) {
            this.getLogger().warning("Vault-Anbieter konnte nicht sauber abgemeldet werden: " + exception.getMessage());
         }

         this.vaultEconomyProvider = null;
      }
   }

   public EconomyService getEconomyService() {
      return this.economyService;
   }

   public boolean isFlyBoosterActive() {
      return this.boosterFeature != null && this.boosterFeature.isFlyBoosterActive();
   }

   public String applyPlaceholders(OfflinePlayer player, String text) {
      if (text != null
         && player != null
         && this.getConfig().getBoolean("placeholderapi.enabled", true)
         && this.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
         try {
            Class<?> placeholderApi = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, GreenAttackSystemPlugin.class.getClassLoader());
            Method method = placeholderApi.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            return (String)method.invoke(null, player, text);
         } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            if (!this.placeholderApiWarningLogged) {
               this.placeholderApiWarningLogged = true;
               this.getLogger().warning("PlaceholderAPI konnte einen Text nicht auswerten: " + exception.getMessage());
            }

            return text;
         }
      } else {
         return text == null ? "" : text;
      }
   }

   private void applyPermissionSetting() {
      String configuredPermission = this.getConfig().getString("permission.node", "system.playerlimitbreak");
      if (configuredPermission != null && configuredPermission.startsWith("system.") && configuredPermission.matches("[A-Za-z0-9_.-]+")) {
         this.bypassPermission = configuredPermission;
      } else {
         this.getLogger().warning("Ungültiger Wert für permission.node; verwende system.playerlimitbreak.");
         this.bypassPermission = "system.playerlimitbreak";
      }

      if (this.getServer().getPluginManager().getPermission(this.bypassPermission) == null) {
         this.getServer().getPluginManager().addPermission(new Permission(this.bypassPermission, PermissionDefault.FALSE));
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onPlayerLogin(PlayerLoginEvent event) {
      if (event.getResult() == Result.KICK_FULL) {
         if (this.hasSystemPermission(event.getPlayer(), "system.playerlimitbreak.admin")) {
            event.allow();
            this.getLogger().info(() -> "Admin-Playerlimit-Bypass für " + event.getPlayer().getName() + " erlaubt.");
         } else if (this.hasSystemPermission(event.getPlayer(), this.bypassPermission)) {
            GreenAttackSystemPlugin.LoadStatus status = this.inspectLoad();
            if (status.allowed()) {
               event.allow();
               this.getLogger().info(() -> "Playerlimit-Bypass für " + event.getPlayer().getName() + " erlaubt (" + status.summary() + ").");
            } else {
               String prefix = this.getConfig().getString("messages.prefix", "&2GreenAttack-Plugin &8» &7");
               String message = prefix
                  + this.getConfig().getString("messages.overloaded", "&cDer Server ist derzeit stark ausgelastet. Bitte versuche es später erneut.");
               message = ChatColor.translateAlternateColorCodes('&', message);
               event.disallow(Result.KICK_OTHER, message);
               this.getLogger()
                  .warning(() -> "Playerlimit-Bypass für " + event.getPlayer().getName() + " wegen hoher Auslastung abgelehnt (" + status.summary() + ").");
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void refreshCommandsAfterPlayerJoin(PlayerJoinEvent event) {
      this.getServer().getScheduler().runTask(this, event.getPlayer()::updateCommands);
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void suppressJoinMessage(PlayerJoinEvent event) {
      if (this.getConfig().getBoolean("chat.suppress-join-leave-messages", true)) {
         event.setJoinMessage(null);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void suppressQuitMessage(PlayerQuitEvent event) {
      if (this.getConfig().getBoolean("chat.suppress-join-leave-messages", true)) {
         event.setQuitMessage(null);
      }
   }

   private GreenAttackSystemPlugin.LoadStatus inspectLoad() {
      double maxCpu = this.getConfig().getDouble("limits.max-cpu-percent", 85.0) / 100.0;
      double maxJvmMemory = this.getConfig().getDouble("limits.max-jvm-memory-percent", 90.0) / 100.0;
      double maxSystemMemory = this.getConfig().getDouble("limits.max-system-memory-percent", 92.0) / 100.0;
      double cpu = this.operatingSystem == null ? -1.0 : this.operatingSystem.getCpuLoad();
      Runtime runtime = Runtime.getRuntime();
      double jvmMemory = (double)(runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
      double systemMemory = -1.0;
      if (this.operatingSystem != null) {
         long total = this.operatingSystem.getTotalMemorySize();
         long free = this.operatingSystem.getFreeMemorySize();
         if (total > 0L && free >= 0L) {
            systemMemory = (double)(total - free) / total;
         }
      }

      boolean unknownIsOverloaded = this.getConfig().getBoolean("limits.deny-if-metric-unavailable", false);
      boolean unknownMetric = cpu < 0.0 || systemMemory < 0.0;
      boolean overloaded = cpu > maxCpu || jvmMemory > maxJvmMemory || systemMemory > maxSystemMemory;
      boolean allowed = !overloaded && (!unknownIsOverloaded || !unknownMetric);
      return new GreenAttackSystemPlugin.LoadStatus(allowed, percent(cpu), percent(jvmMemory), percent(systemMemory), unknownMetric);
   }

   private OperatingSystemMXBean findOperatingSystemBean() {
      if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean extended) {
         return extended;
      } else {
         this.getLogger().warning("CPU- und System-RAM-Messwerte sind auf dieser Java-Installation nicht verfügbar.");
         return null;
      }
   }

   private static String percent(double value) {
      return value < 0.0 ? "unbekannt" : String.format(Locale.ROOT, "%.1f%%", value * 100.0);
   }

   private record LoadStatus(boolean allowed, String cpu, String jvmMemory, String systemMemory, boolean hasUnavailableMetric) {
      private String summary() {
         return "CPU="
            + this.cpu
            + ", JVM-RAM="
            + this.jvmMemory
            + ", System-RAM="
            + this.systemMemory
            + (this.hasUnavailableMetric ? " (mindestens ein Messwert fehlt)" : "");
      }
   }
}

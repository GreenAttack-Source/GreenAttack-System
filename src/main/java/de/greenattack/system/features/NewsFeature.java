package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

public final class NewsFeature extends FeatureModule implements CommandExecutor, TabCompleter {
   private static final int MAX_BOOK_PAGES = 100;
   private static final int MAX_PAGE_CHARACTERS = 1024;
   private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
   private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

   public NewsFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "news");
      this.mergeBookDefaults();
   }

   private void mergeBookDefaults() {
      try (InputStream resource = this.plugin.getResource("features/news/config.yml")) {
         if (resource != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(resource, StandardCharsets.UTF_8));
            boolean changed = false;
            if (!this.config.contains("book.header")) {
               String previousHeader = this.config.getString("messages.header");
               this.config.set("book.header", previousHeader == null ? defaults.getString("book.header", "&a&lGreenAttack Neuigkeiten:") : previousHeader);
               changed = true;
            }

            for (String path : List.of("book.entry-format", "book.entry-separator")) {
               if (!this.config.contains(path) && defaults.contains(path)) {
                  this.config.set(path, defaults.get(path));
                  changed = true;
               }
            }

            if (changed && !this.save()) {
               this.plugin.getLogger().warning("News-Buchlayout konnte nicht in features/news/config.yml ergänzt werden.");
            }
         }
      } catch (IOException exception) {
         this.plugin.getLogger().warning("News-Buchlayout-Standards konnten nicht geladen werden: " + exception.getMessage());
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDieses Feature ist deaktiviert.");
         return true;
      } else {
         return !command.getName().equalsIgnoreCase("newsconfig") && args.length <= 0 ? this.showNews(sender) : this.configureNews(sender, args);
      }
   }

   private boolean showNews(CommandSender sender) {
      if (!this.hasFeaturePermission(sender, this.config.getString("permissions.view", "system.news.view"))) {
         this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return true;
      }

      List<String> news = this.config.getStringList("news.lines");
      if (news.isEmpty()) {
         this.sendPath(sender, "messages.no-news", "&7Derzeit gibt es keine Neuigkeiten.");
         return true;
      }

      if (sender instanceof Player player) {
         try {
            player.openBook(this.createBook(news));
         } catch (NewsFeature.NewsBookException exception) {
            this.sendPath(player, exception.configPath(), exception.fallback());
         }

         return true;
      } else {
         this.sendPath(sender, "messages.player-only", "&cNur Spieler können das News-Buch öffnen.");
         return true;
      }
   }

   private ItemStack createBook(List<String> news) {
      ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
      if (book.getItemMeta() instanceof BookMeta meta) {
         meta.title(Component.text(this.config.getString("book.title", "GreenAttack News")));
         meta.author(Component.text(this.config.getString("book.author", "GreenAttack")));
         meta.pages(this.createPages(news));
         book.setItemMeta(meta);
         return book;
      } else {
         throw new NewsFeature.NewsBookException("messages.book-error", "&cDas News-Buch konnte nicht erstellt werden.");
      }
   }

   private List<Component> createPages(List<String> news) {
      List<String> pageTexts = new ArrayList<>();
      String header = this.config.getString("book.header", this.config.getString("messages.header", "&a&lGreenAttack Neuigkeiten:"));
      if (this.visibleLength(header) > 1024) {
         throw new NewsFeature.NewsBookException("messages.book-error", "&cDie konfigurierte News-Überschrift ist zu lang.");
      }

      pageTexts.add(header);

      for (int i = 0; i < news.size(); i++) {
         String item = this.config
            .getString("book.entry-format", "&7%number%. &r%text%")
            .replace("%number%", Integer.toString(i + 1))
            .replace("%text%", news.get(i));
         if (this.visibleLength(item) > 1024) {
            throw new NewsFeature.NewsBookException(
               "messages.entry-too-long", "&cDieser News-Eintrag ist zu lang für eine Buchseite und wurde nicht gespeichert."
            );
         }

         int last = pageTexts.size() - 1;
         String separator = pageTexts.get(last).isEmpty() ? "" : this.config.getString("book.entry-separator", "\n\n");
         if (this.visibleLength(pageTexts.get(last) + separator + item) <= 1024) {
            pageTexts.set(last, pageTexts.get(last) + separator + item);
         } else {
            pageTexts.add(item);
         }

         if (pageTexts.size() > 100) {
            throw new NewsFeature.NewsBookException("messages.too-many-pages", "&cDie Neuigkeiten passen nicht in ein Minecraft-Buch (maximal 100 Seiten).");
         }
      }

      List<Component> pages = new ArrayList<>();

      for (String pageText : pageTexts) {
         pages.add(LEGACY.deserialize(pageText));
      }

      return pages;
   }

   private int visibleLength(String legacyText) {
      return PLAIN_TEXT.serialize(LEGACY.deserialize(legacyText)).length();
   }

   private boolean configureNews(CommandSender sender, String[] args) {
      if (!this.hasFeaturePermission(sender, this.config.getString("permissions.config", "system.news.config"))) {
         this.sendPath(sender, "messages.no-permission", "&cDafür hast du keine Berechtigung.");
         return true;
      }

      if (args.length == 0) {
         this.sendUsage(sender);
         return true;
      }

      List<String> news = new ArrayList<>(this.config.getStringList("news.lines"));
      switch (args[0].toLowerCase(Locale.ROOT)) {
         case "add":
            this.addNews(sender, args, news);
            break;
         case "set":
            this.setNews(sender, args, news);
            break;
         case "delete":
         case "remove":
            this.deleteNews(sender, args, news);
            break;
         case "clear":
            if (args.length != 1) {
               this.sendUsage(sender);
               return true;
            }

            this.config.set("news.lines", List.of());
            this.save();
            this.sendPath(sender, "messages.cleared", "&aAlle Neuigkeiten wurden gelöscht.");
            break;
         default:
            this.sendUsage(sender);
      }

      return true;
   }

   private void addNews(CommandSender sender, String[] args, List<String> news) {
      if (args.length < 2) {
         this.sendPath(sender, "messages.usage-add", "&7Verwendung: &f/newsconfig add <Text>");
      } else {
         String message = String.join(" ", List.of(args).subList(1, args.length));
         List<String> updated = new ArrayList<>(news);
         updated.add(message);
         if (this.isBookValid(sender, updated)) {
            this.config.set("news.lines", updated);
            this.save();
            this.sendPath(sender, "messages.added", "&aNeuigkeit wurde hinzugefügt.");
         }
      }
   }

   private void setNews(CommandSender sender, String[] args, List<String> news) {
      if (args.length < 3) {
         this.sendPath(sender, "messages.usage-set", "&7Verwendung: &f/newsconfig set <Nummer> <Text>");
      } else {
         int index = this.parseIndex(args[1]);
         if (index >= 0 && index < news.size()) {
            String message = String.join(" ", List.of(args).subList(2, args.length));
            List<String> updated = new ArrayList<>(news);
            updated.set(index, message);
            if (this.isBookValid(sender, updated)) {
               this.config.set("news.lines", updated);
               this.save();
               this.send(
                  sender, this.config.getString("messages.updated", "&aNeuigkeit &e%index% &awurde geändert.").replace("%index%", Integer.toString(index + 1))
               );
            }
         } else {
            this.sendPath(sender, "messages.invalid-index", "&cBitte gib eine vorhandene Nummer aus der News-Liste an.");
         }
      }
   }

   private void deleteNews(CommandSender sender, String[] args, List<String> news) {
      if (args.length != 2) {
         this.sendPath(sender, "messages.usage-delete", "&7Verwendung: &f/newsconfig delete <Nummer>");
      } else {
         int index = this.parseIndex(args[1]);
         if (index >= 0 && index < news.size()) {
            news.remove(index);
            this.config.set("news.lines", news);
            this.save();
            this.send(
               sender,
               this.config.getString("messages.deleted", "&aNeuigkeit Nummer &e%index% &awurde gelöscht.").replace("%index%", Integer.toString(index + 1))
            );
         } else {
            this.sendPath(sender, "messages.invalid-index", "&cBitte gib eine vorhandene Nummer aus der News-Liste an.");
         }
      }
   }

   private boolean isBookValid(CommandSender sender, List<String> proposedNews) {
      try {
         this.createPages(proposedNews);
         return true;
      } catch (NewsFeature.NewsBookException exception) {
         this.sendPath(sender, exception.configPath(), exception.fallback());
         return false;
      }
   }

   private int parseIndex(String value) {
      try {
         return Integer.parseInt(value) - 1;
      } catch (NumberFormatException exception) {
         return -1;
      }
   }

   private void sendUsage(CommandSender sender) {
      this.sendPath(sender, "messages.usage", "&7Verwendung: &f/newsconfig <add <Text>|set <Nummer> <Text>|delete <Nummer>|clear>");
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (this.isEnabled() && this.hasFeaturePermission(sender, this.config.getString("permissions.config", "system.news.config"))) {
         if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return List.of("add", "set", "delete", "clear").stream().filter(valuex -> valuex.startsWith(partial)).toList();
         }

         if (args.length == 2 && List.of("set", "delete", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            String partial = args[1];
            List<String> values = new ArrayList<>();

            for (int i = 1; i <= this.config.getStringList("news.lines").size(); i++) {
               String value = Integer.toString(i);
               if (value.startsWith(partial)) {
                  values.add(value);
               }
            }

            return values;
         } else {
            return List.of();
         }
      } else {
         return List.of();
      }
   }

   private static final class NewsBookException extends RuntimeException {
      private final String configPath;
      private final String fallback;

      private NewsBookException(String configPath, String fallback) {
         this.configPath = configPath;
         this.fallback = fallback;
      }

      private String configPath() {
         return this.configPath;
      }

      private String fallback() {
         return this.fallback;
      }
   }
}

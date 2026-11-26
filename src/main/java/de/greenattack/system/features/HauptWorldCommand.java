package de.greenattack.system.features;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class HauptWorldCommand implements CommandExecutor {
   private static final Pattern LEVEL_NAME = Pattern.compile("^(\\s*level-name\\s*[=:]\\s*).*$");
   private final JavaPlugin plugin;

   public HauptWorldCommand(JavaPlugin plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Dafür musst du OP sein.");
            return true;
         }

         if (args.length != 0) {
            player.sendMessage(ChatColor.GRAY + "Verwendung: " + ChatColor.WHITE + "/haupt-world");
            return true;
         }

         try {
            Path serverProperties = this.findServerProperties();
            String worldName = player.getWorld().getName();
            List<String> lines = new ArrayList<>(Files.readAllLines(serverProperties, StandardCharsets.UTF_8));
            boolean found = false;

            for (int i = 0; i < lines.size(); i++) {
               String line = lines.get(i);
               Matcher matcher = LEVEL_NAME.matcher(line);
               if (matcher.matches()) {
                  lines.set(i, matcher.group(1) + worldName);
                  found = true;
               }
            }

            if (!found) {
               lines.add("level-name=" + worldName);
            }

            this.writeAtomically(serverProperties, lines);
            player.sendMessage(ChatColor.GREEN + "Hauptwelt für den nächsten Serverstart auf " + ChatColor.YELLOW + worldName + ChatColor.GREEN + " gesetzt.");
            player.sendMessage(ChatColor.GRAY + "Multiverse-Core übernimmt die Änderung nach einem Serverneustart.");
            this.plugin
               .getLogger()
               .info(player.getName() + " hat die Server-Hauptwelt auf " + worldName + " gesetzt (server.properties: level-name). Neustart erforderlich.");
         } catch (Exception exception) {
            this.plugin.getLogger().log(Level.SEVERE, "Die Hauptwelt konnte nicht in server.properties gespeichert werden.", exception);
            String reason = exception.getMessage();
            if (reason == null || reason.isBlank()) {
               reason = exception.getClass().getSimpleName();
            }

            player.sendMessage(ChatColor.RED + "Die Hauptwelt konnte nicht gespeichert werden: " + reason);
         }

         return true;
      } else {
         sender.sendMessage(ChatColor.RED + "Diesen Befehl musst du im Spiel ausführen.");
         return true;
      }
   }

   private Path findServerProperties() throws IOException {
      Set<Path> candidates = new LinkedHashSet<>();
      candidates.add(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize().resolve("server.properties"));
      Path pluginDataFolder = this.plugin.getDataFolder().toPath();
      Path pluginsFolder = pluginDataFolder.getParent();
      if (pluginsFolder != null && pluginsFolder.getParent() != null) {
         candidates.add(pluginsFolder.getParent().resolve("server.properties").toAbsolutePath().normalize());
      }

      candidates.add(this.plugin.getServer().getWorldContainer().toPath().resolve("server.properties").toAbsolutePath().normalize());

      for (Path candidate : candidates) {
         if (Files.isRegularFile(candidate)) {
            return candidate;
         }
      }

      throw new IOException("server.properties wurde nicht gefunden. Geprüfte Pfade: " + candidates);
   }

   private void writeAtomically(Path target, List<String> lines) throws IOException {
      Path temporary = Files.createTempFile(target.getParent(), "server.properties-", ".tmp");

      try {
         Files.write(temporary, lines, StandardCharsets.UTF_8);

         try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
         }
      } finally {
         Files.deleteIfExists(temporary);
      }
   }
}

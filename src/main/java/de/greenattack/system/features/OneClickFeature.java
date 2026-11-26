package de.greenattack.system.features;

import de.greenattack.system.GreenAttackSystemPlugin;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class OneClickFeature extends FeatureModule implements CommandExecutor, Listener {
   private static final String ADMIN_PERMISSION = "system.oneclick.admin";
   private static final int DEFAULT_MAX_BLOCKS = 128;
   private final NamespacedKey toolModeKey;
   private final Set<UUID> processingPlayers = new HashSet<>();

   public OneClickFeature(GreenAttackSystemPlugin plugin) {
      super(plugin, "one-klick");
      this.toolModeKey = new NamespacedKey(plugin, "one_klick_mode");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!this.isEnabled()) {
         this.sendPath(sender, "messages.disabled", "&cDas One-Klick-Feature ist deaktiviert.");
         return true;
      }

      if (!this.hasFeaturePermission(sender, "system.oneclick.admin")) {
         this.sendPath(sender, "messages.no-permission", "&cDazu hast du keine Rechte.");
         return true;
      }

      if (sender instanceof Player player) {
         if (args.length != 0) {
            this.sendPath(player, "messages.usage", "&7Verwendung: &f/one-klick");
            return true;
         } else {
            ItemStack held = player.getInventory().getItemInMainHand();
            OneClickFeature.ToolMode mode = this.supportedMode(held);
            if (mode == null) {
               this.sendPath(player, "messages.invalid-item", "&cHalte dafür eine Axt oder Spitzhacke in der Haupthand.");
               return true;
            } else if (this.readMode(held) == mode) {
               this.sendPath(player, "messages.already-enabled", "&aOne-Klick ist für dieses Item bereits aktiviert.");
               return true;
            } else {
               ItemMeta meta = held.getItemMeta();
               if (meta == null) {
                  this.sendPath(player, "messages.invalid-item", "&cDieses Item kann nicht markiert werden.");
                  return true;
               } else {
                  meta.getPersistentDataContainer().set(this.toolModeKey, PersistentDataType.STRING, mode.key);
                  held.setItemMeta(meta);
                  this.send(
                     player,
                     this.config.getString("messages.enabled", "&aOne-Klick wurde für die gehaltene %type% aktiviert.").replace("%type%", mode.displayName)
                  );
                  return true;
               }
            }
         }
      } else {
         this.sendPath(sender, "messages.player-only", "&cDiesen Befehl können nur Spieler verwenden.");
         return true;
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBlockBreak(BlockBreakEvent event) {
      Player player = event.getPlayer();
      if (this.isEnabled() && !this.processingPlayers.contains(player.getUniqueId())) {
         OneClickFeature.ToolMode mode = this.readMode(player.getInventory().getItemInMainHand());
         Material targetType = event.getBlock().getType();
         if (mode != null && this.matchesBlock(mode, targetType)) {
            World world = event.getBlock().getWorld();
            OneClickFeature.BlockPosition origin = new OneClickFeature.BlockPosition(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
            Bukkit.getScheduler().runTask(this.plugin, () -> this.breakConnectedBlocks(player, world, origin, targetType, mode));
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBlockDrop(BlockDropItemEvent event) {
      if (this.isEnabled()) {
         Player player = event.getPlayer();
         OneClickFeature.ToolMode mode = this.readMode(player.getInventory().getItemInMainHand());
         if (mode != null && this.matchesBlock(mode, event.getBlockState().getType())) {
            for (Item drop : List.copyOf(event.getItems())) {
               if (drop.isValid()) {
                  ItemStack stack = drop.getItemStack();
                  HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack[]{stack.clone()});
                  if (leftovers.isEmpty()) {
                     drop.remove();
                  } else {
                     drop.setItemStack(leftovers.values().iterator().next());
                  }
               }
            }
         }
      }
   }

   private void breakConnectedBlocks(Player player, World world, OneClickFeature.BlockPosition origin, Material targetType, OneClickFeature.ToolMode mode) {
      if (player.isOnline() && this.readMode(player.getInventory().getItemInMainHand()) == mode) {
         int maxBlocks = Math.max(1, Math.min(this.config.getInt("max-blocks-per-click", 128), 2048));
         if (maxBlocks > 1) {
            List<OneClickFeature.BlockPosition> connected = this.findConnectedBlocks(world, origin, targetType, maxBlocks - 1);
            if (!connected.isEmpty()) {
               UUID playerId = player.getUniqueId();
               this.processingPlayers.add(playerId);

               try {
                  for (OneClickFeature.BlockPosition position : connected) {
                     if (!player.isOnline() || this.readMode(player.getInventory().getItemInMainHand()) != mode) {
                        break;
                     }

                     Block block = world.getBlockAt(position.x(), position.y(), position.z());
                     if (block.getType() == targetType) {
                        player.breakBlock(block);
                     }
                  }
               } finally {
                  this.processingPlayers.remove(playerId);
               }
            }
         }
      }
   }

   private List<OneClickFeature.BlockPosition> findConnectedBlocks(World world, OneClickFeature.BlockPosition origin, Material targetType, int limit) {
      List<OneClickFeature.BlockPosition> result = new ArrayList<>(Math.min(limit, 64));
      Set<OneClickFeature.BlockPosition> visited = new HashSet<>();
      Queue<OneClickFeature.BlockPosition> queue = new ArrayDeque<>();

      for (int dx = -1; dx <= 1; dx++) {
         for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (dx != 0 || dy != 0 || dz != 0) {
                  queue.add(new OneClickFeature.BlockPosition(origin.x() + dx, origin.y() + dy, origin.z() + dz));
               }
            }
         }
      }

      while (!queue.isEmpty() && result.size() < limit) {
         OneClickFeature.BlockPosition position = queue.remove();
         if (visited.add(position)
            && world.isChunkLoaded(position.x() >> 4, position.z() >> 4)
            && world.getBlockAt(position.x(), position.y(), position.z()).getType() == targetType) {
            result.add(position);

            for (int dx = -1; dx <= 1; dx++) {
               for (int dy = -1; dy <= 1; dy++) {
                  for (int dz = -1; dz <= 1; dz++) {
                     if (dx != 0 || dy != 0 || dz != 0) {
                        queue.add(new OneClickFeature.BlockPosition(position.x() + dx, position.y() + dy, position.z() + dz));
                     }
                  }
               }
            }
         }
      }

      return result;
   }

   private OneClickFeature.ToolMode readMode(ItemStack item) {
      if (item != null && !item.getType().isAir() && item.hasItemMeta()) {
         String key = (String)item.getItemMeta().getPersistentDataContainer().get(this.toolModeKey, PersistentDataType.STRING);
         if (key == null) {
            return null;
         }

         return switch (key) {
            case "axe" -> OneClickFeature.ToolMode.AXE;
            case "pickaxe" -> OneClickFeature.ToolMode.PICKAXE;
            default -> null;
         };
      } else {
         return null;
      }
   }

   private OneClickFeature.ToolMode supportedMode(ItemStack item) {
      if (item != null && !item.getType().isAir()) {
         String type = item.getType().name();
         if (type.endsWith("_PICKAXE")) {
            return OneClickFeature.ToolMode.PICKAXE;
         } else {
            return type.endsWith("_AXE") ? OneClickFeature.ToolMode.AXE : null;
         }
      } else {
         return null;
      }
   }

   private boolean matchesBlock(OneClickFeature.ToolMode mode, Material material) {
      String name = material.name();

      return switch (mode) {
         case AXE -> name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM") || name.endsWith("_HYPHAE") || name.equals("MANGROVE_ROOTS");
         case PICKAXE -> name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS");
      };
   }

   private record BlockPosition(int x, int y, int z) {
   }

   private enum ToolMode {
      AXE("axe", "Axt"),
      PICKAXE("pickaxe", "Spitzhacke");

      private final String key;
      private final String displayName;

      ToolMode(String key, String displayName) {
         this.key = key;
         this.displayName = displayName;
      }
   }
}

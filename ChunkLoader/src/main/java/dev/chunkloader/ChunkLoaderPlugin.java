package dev.chunkloader;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ChunkLoaderPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private NamespacedKey keyItem, keyTime, keySize;
    private final Map<String, Loader> loaders = new HashMap<>();
    private final Map<String, Integer> chunkRefs = new HashMap<>();
    private final Map<Material, Long> fuels = new LinkedHashMap<>();
    private final Map<Integer, Integer> drain = new HashMap<>();
    private String texture;
    private File dataFile;

    static class Loader {
        final UUID world;
        final int x, y, z;
        final int size;
        long remaining;
        boolean active;

        Loader(UUID world, int x, int y, int z, int size, long remaining) {
            this.world = world; this.x = x; this.y = y; this.z = z;
            this.size = size; this.remaining = remaining;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyItem = new NamespacedKey(this, "chunkloader");
        keyTime = new NamespacedKey(this, "time");
        keySize = new NamespacedKey(this, "size");
        dataFile = new File(getDataFolder(), "data.yml");
        loadConfigValues();
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        // tick ทุก 1 วินาที
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        // เซฟทุก 5 นาที
        getServer().getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        saveData();
        for (Loader l : new ArrayList<>(loaders.values())) deactivate(l);
    }

    private void loadConfigValues() {
        drain.clear();
        for (int i = 1; i <= 5; i++) drain.put(i, Math.max(1, getConfig().getInt("drain." + i, 1)));
        texture = getConfig().getString("head-texture", "");
        fuels.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("fuel");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m != null) fuels.put(m, sec.getLong(k));
            }
        }
    }

    // ---------------------------------------------------------------- item

    private String fmtTime(long s) {
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(" ชม. ");
        if (h > 0 || m > 0) sb.append(m).append(" นาที ");
        sb.append(sec).append(" วิ.");
        return sb.toString();
    }

    private Component c(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static final String[] RARITY = {
            "<gray>COMMON", "<green>UNCOMMON", "<blue>RARE", "<light_purple>EPIC", "<red>MYTHIC"};

    private int itemSize(ItemStack item) {
        int sz = item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(keySize, PersistentDataType.INTEGER, 3);
        return Math.max(1, Math.min(5, sz));
    }

    private ItemStack createItem(long seconds, int size) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        if (texture != null && !texture.isEmpty()) {
            PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
            profile.setProperty(new ProfileProperty("textures", texture));
            meta.setPlayerProfile(profile);
        }

        meta.displayName(c("<gradient:#55ffff:#aa55ff><bold>[CHUNKLOADER]</bold></gradient>"));

        List<Component> lore = new ArrayList<>();
        lore.add(c("<dark_gray>▸ <white>ความหายาก: <bold>" + RARITY[size - 1]));
        lore.add(c("<dark_gray>▸ <white>เปิดได้อีก: <gold>" + fmtTime(seconds)));
        lore.add(Component.empty());
        lore.add(c("<white>โหลดชังก์อย่างต่อเนื่องแม้จะไม่มีคนอยู่"));
        lore.add(c("<white>ระยะการทำงาน : <aqua>" + size + "×" + size + " <white>(" + (size * size) + " chunks)"));
        lore.add(c("<white>คลิกขวาเติมเชื้อเพลิงเพื่อเปิดการทำงาน"));
        lore.add(c("<white>ไอเทมที่สามารถเป็นเชื้อเพลิงได้:"));
        for (Material m : fuels.keySet()) {
            lore.add(c("<gray>- " + prettyName(m)));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(keyItem, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(keyTime, PersistentDataType.LONG, seconds);
        meta.getPersistentDataContainer().set(keySize, PersistentDataType.INTEGER, size);
        item.setItemMeta(meta);
        return item;
    }

    private String prettyName(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String part : m.name().toLowerCase().split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString().replace("Coal Block", "Block of Coal");
    }

    private boolean isLoaderItem(ItemStack item) {
        return item != null && item.getType() == Material.PLAYER_HEAD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(keyItem, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- loaders

    private String key(UUID w, int x, int y, int z) { return w + ";" + x + ";" + y + ";" + z; }
    private String key(Block b) { return key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }

    private void activate(Loader l) {
        World w = Bukkit.getWorld(l.world);
        if (w == null || l.active) return;
        int cx0 = l.x >> 4, cz0 = l.z >> 4;
        int lo = -((l.size - 1) / 2);
        for (int dx = lo; dx < lo + l.size; dx++) {
            for (int dz = lo; dz < lo + l.size; dz++) {
                int cx = cx0 + dx, cz = cz0 + dz;
                String k = l.world + ";" + cx + ";" + cz;
                if (chunkRefs.merge(k, 1, Integer::sum) == 1) {
                    w.addPluginChunkTicket(cx, cz, this);
                }
            }
        }
        l.active = true;
    }

    private void deactivate(Loader l) {
        World w = Bukkit.getWorld(l.world);
        if (w == null || !l.active) return;
        int cx0 = l.x >> 4, cz0 = l.z >> 4;
        int lo = -((l.size - 1) / 2);
        for (int dx = lo; dx < lo + l.size; dx++) {
            for (int dz = lo; dz < lo + l.size; dz++) {
                int cx = cx0 + dx, cz = cz0 + dz;
                String k = l.world + ";" + cx + ";" + cz;
                int left = chunkRefs.merge(k, -1, Integer::sum);
                if (left <= 0) {
                    chunkRefs.remove(k);
                    w.removePluginChunkTicket(cx, cz, this);
                }
            }
        }
        l.active = false;
    }

    private void removeLoader(Loader l) {
        deactivate(l);
        loaders.remove(key(l.world, l.x, l.y, l.z));
    }

    private void tick() {
        for (Loader l : new ArrayList<>(loaders.values())) {
            World w = Bukkit.getWorld(l.world);
            if (w == null) continue;

            // ถ้าบล็อกหายไปแล้ว (เช่น ถูกลบด้วยคำสั่ง/WorldEdit) ให้ลบ loader
            if (w.isChunkLoaded(l.x >> 4, l.z >> 4)) {
                Material t = w.getBlockAt(l.x, l.y, l.z).getType();
                if (t != Material.PLAYER_HEAD && t != Material.PLAYER_WALL_HEAD) {
                    removeLoader(l);
                    continue;
                }
            }

            if (l.remaining > 0) {
                if (!l.active) activate(l);
                l.remaining = Math.max(0, l.remaining - drain.getOrDefault(l.size, 1));
                if (l.remaining == 0) deactivate(l);
            } else if (l.active) {
                deactivate(l);
            }
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (!isLoaderItem(item)) return;
        long time = item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(keyTime, PersistentDataType.LONG, 0L);
        Block b = e.getBlockPlaced();
        int size = itemSize(item);
        loaders.put(key(b), new Loader(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(), size, time));
        e.getPlayer().sendMessage(c("<green>วาง Chunkloader " + size + "×" + size + " แล้ว <gray>คลิกขวาด้วยถ่านเพื่อเติมเชื้อเพลิง"));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Loader l = loaders.get(key(b));
        if (l == null) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();

        // Shift + ถือของ = วางบล็อกตามปกติ
        if (p.isSneaking() && !hand.getType().isAir()) return;

        e.setCancelled(true);
        if (!p.hasPermission("chunkloader.use")) return;

        Long add = fuels.get(hand.getType());
        if (add != null) {
            hand.setAmount(hand.getAmount() - 1);
            l.remaining += add;
            if (!l.active) activate(l);
            p.sendMessage(c("<green>เติมเชื้อเพลิง +" + fmtTime(add) + " <gray>(เหลือ " + fmtTime(l.remaining) + ")"));
            p.playSound(b.getLocation(), Sound.BLOCK_FURNACE_FIRE_CRACKLE, 1f, 1f);
        } else {
            String status = l.remaining > 0 ? "<green>ทำงานอยู่" : "<red>หยุดทำงาน";
            p.sendMessage(c("<aqua>[Chunkloader " + l.size + "×" + l.size + "] " + status + " <gray>| เหลือ <gold>" + fmtTime(l.remaining)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Loader l = loaders.get(key(e.getBlock()));
        if (l == null) return;
        e.setDropItems(false);
        Location loc = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            loc.getWorld().dropItemNaturally(loc, createItem(l.remaining, l.size));
        }
        removeLoader(l);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> loaders.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> loaders.containsKey(key(b)));
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("chunkloader.give")) {
            s.sendMessage(c("<red>คุณไม่มีสิทธิ์ใช้คำสั่งนี้"));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadConfigValues();
            s.sendMessage(c("<green>รีโหลด config แล้ว"));
            return true;
        }
        if (a.length == 0 || !a[0].equalsIgnoreCase("give")) {
            s.sendMessage(c("<yellow>/chunkloader give [player] [size 1-5] [minutes] | reload"));
            return true;
        }
        Player target = null;
        if (a.length >= 2) target = Bukkit.getPlayerExact(a[1]);
        else if (s instanceof Player p) target = p;
        if (target == null) {
            s.sendMessage(c("<red>ไม่พบผู้เล่น"));
            return true;
        }
        int size = 3;
        long minutes = 0;
        try {
            if (a.length >= 3) size = Integer.parseInt(a[2]);
            if (a.length >= 4) minutes = Long.parseLong(a[3]);
        } catch (NumberFormatException ex) {
            s.sendMessage(c("<red>ขนาดหรือจำนวนนาทีไม่ถูกต้อง"));
            return true;
        }
        if (size < 1 || size > 5) {
            s.sendMessage(c("<red>ขนาดต้องเป็น 1-5"));
            return true;
        }
        for (ItemStack rest : target.getInventory().addItem(createItem(minutes * 60, size)).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        s.sendMessage(c("<green>ให้ Chunkloader แก่ " + target.getName() + " แล้ว"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 1) return List.of("give", "reload");
        if (a.length == 2 && a[0].equalsIgnoreCase("give"))
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if (a.length == 3 && a[0].equalsIgnoreCase("give")) return List.of("1", "2", "3", "4", "5");
        return List.of();
    }

    // ---------------------------------------------------------------- data

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> out = new ArrayList<>();
        for (Loader l : loaders.values()) {
            out.add(l.world + ";" + l.x + ";" + l.y + ";" + l.z + ";" + l.remaining + ";" + l.size);
        }
        y.set("loaders", out);
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Cannot save data.yml: " + ex.getMessage());
        }
    }

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : y.getStringList("loaders")) {
            try {
                String[] p = s.split(";");
                Loader l = new Loader(UUID.fromString(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        p.length > 5 ? Integer.parseInt(p[5]) : 3, Long.parseLong(p[4]));
                loaders.put(key(l.world, l.x, l.y, l.z), l);
            } catch (Exception ex) {
                getLogger().warning("Bad loader entry: " + s);
            }
        }
    }
}

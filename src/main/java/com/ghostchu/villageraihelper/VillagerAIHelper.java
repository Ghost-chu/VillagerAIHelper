package com.ghostchu.villageraihelper;

import com.google.gson.Gson;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class VillagerAIHelper extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private ItemStack HELPER_STICK;
    private final NamespacedKey VILLAGER_KEY = new NamespacedKey(this, "villager_managed");
    private final NamespacedKey HELPER_STICK_KEY = new NamespacedKey(this, "helper_stick");
    private final NamespacedKey LAST_RESTOCK_AT_KEY = new NamespacedKey(this, "villager_last_restock_at");
    private final NamespacedKey AI_REMOVER_KEY = new NamespacedKey(this, "villager_ai_remover");
    private int[] restockScheduler;
    private final Map<Villager.Profession, Material> jobSites = new HashMap<>();
    private boolean chunkLoadingPatch = true;
    private YamlConfiguration config;

    /**
     * 设置村民的工作方块
     * 当版本更新时，此处可能需要更新
     */
    private void setupJobSites() {
        jobSites.put(Villager.Profession.ARMORER, Material.BLAST_FURNACE);
        jobSites.put(Villager.Profession.BUTCHER, Material.SMOKER);
        jobSites.put(Villager.Profession.CARTOGRAPHER, Material.CARTOGRAPHY_TABLE);
        jobSites.put(Villager.Profession.CLERIC, Material.BREWING_STAND);
        jobSites.put(Villager.Profession.FARMER, Material.COMPOSTER);
        jobSites.put(Villager.Profession.FISHERMAN, Material.BARREL);
        jobSites.put(Villager.Profession.FLETCHER, Material.FLETCHING_TABLE);
        jobSites.put(Villager.Profession.LEATHERWORKER, Material.CAULDRON);
        jobSites.put(Villager.Profession.LIBRARIAN, Material.LECTERN);
        jobSites.put(Villager.Profession.MASON, Material.STONECUTTER);
        jobSites.put(Villager.Profession.SHEPHERD, Material.LOOM);
        jobSites.put(Villager.Profession.TOOLSMITH, Material.SMITHING_TABLE);
        jobSites.put(Villager.Profession.WEAPONSMITH, Material.GRINDSTONE);
    }


    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        this.config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        // 设置工具物品
        ItemStack plugItem = new ItemStack(Material.getMaterial(config.getString("item.type", "STICK")));
        ItemMeta meta = plugItem.getItemMeta();
        String itemName = Util.parseColours(config.getString("item.name"));
        String itemLore = Util.parseColours(config.getString("item.lore"));
        meta.setDisplayName(itemName);
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setLore(Arrays.stream(itemLore.split("\n")).collect(Collectors.toList()));
        meta.getPersistentDataContainer().set(HELPER_STICK_KEY, PersistentDataType.BOOLEAN, true);
        plugItem.setItemMeta(meta);
        this.HELPER_STICK = plugItem;
        // 读取补货时间表
        List<Integer> restockTmp = config.getIntegerList("restock-schedule");
        restockScheduler = new int[restockTmp.size()]; // use array for best performance
        for (int i = 0; i < restockTmp.size(); i++) {
            restockScheduler[i] = restockTmp.get(i);
            getLogger().info("Registering scheduler for time: " + restockScheduler[i]);
        }
        // 设置职业方块映射表
        this.setupJobSites();
        Bukkit.getPluginManager().registerEvents(this, this);
        chunkLoadingPatch = config.getBoolean("patch-villagers-on-chunk-loading", true);
        getLogger().info("VillagerAIHelper has been successfully enabled!");
        // 每个 tick 都要执行的时间检查
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                boolean hit = false;
                for (int scheduled : restockScheduler) {
                    if (world.getTime() != scheduled) {
                        continue;
                    }
                    hit = true;
                    break;
                }
                if (!hit) {
                    continue;
                }
                // 补货
                int restocked = 0;
                int skipped = 0;
                getLogger().info("Checking for villagers restocking in world " + world.getName() + "!");
                for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                    if (isManagedVillager(villager)) {
                        if (restockVillager(villager))
                            restocked++;
                        else
                            skipped++;
                    }
                }
                getLogger().info("Total " + restocked + " managed villagers restocked and " + skipped + " skipped!");
            }

        }, 0, 1);

        Bukkit.getScheduler().runTask(this, () -> {
            getLogger().info("Checking for villagers and try repairing if needs...");
            Bukkit.getWorlds().forEach(world -> {
                for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                    tryRepairIfNeeds(villager);
                }
            });
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (event.isNewChunk())
            return;
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager) {
                if (chunkLoadingPatch) {
                    tryRepairIfNeeds(villager);
                }
                if (shouldCheckCanRestock(villager)) {
                    if (canRestock(villager)) {
                        restockVillager(villager);
                    }
                }
            }
        }
    }

    private void tryRepairIfNeeds(@NotNull Villager villager) {
        if (isManagedVillager(villager)) {
            if (!isManageWorking(villager)) {
                getLogger().info("Patching the villager at: " + villager.getLocation() + " since it status has been changed by other things.");
                UUID uuid = getVillagerManager(villager);
                if (uuid == null) uuid = new UUID(0, 0);
                applyManage(villager, uuid);
            }
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    @EventHandler(ignoreCancelled = true)
    public void interact(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager))
            return;
        if (event.getHand() != EquipmentSlot.HAND)
            return;
        if (!isHelperStick(event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }
        if (!isManagedVillager(villager)) {
            if (!villager.hasMetadata("shopkeeper")) {
                applyManage(villager, event.getPlayer().getUniqueId());
                event.getPlayer().sendMessage(Util.parseColours(config.getString("message.apply")));
            }
        } else {
            undoManage(villager);
            event.getPlayer().sendMessage(Util.parseColours(config.getString("message.undo")));
        }
    }

    private boolean isHelperStick(ItemStack item) {
        if(item.hasItemMeta()) {
            return item.getItemMeta().getPersistentDataContainer().has(HELPER_STICK_KEY);
        }
        return false;
    }


    /**
     * 获取村民是否在 Villager AI Helper 的管理之下
     *
     * @param villager 村民
     * @return 是否管理中
     */
    private boolean isManagedVillager(@NotNull Villager villager) {
        return villager.getPersistentDataContainer().has(VILLAGER_KEY, PersistentDataType.STRING);
    }

    /**
     * 获取使用 AI Helper 操作村民的玩家
     *
     * @param villager 村民
     * @return 玩家 UUID，未受管理的返回 null
     */
    @Nullable
    private UUID getVillagerManager(@NotNull Villager villager) {
        if (!isManagedVillager(villager))
            return null;
        VillagerPastStatus data = gson.fromJson(villager.getPersistentDataContainer().get(VILLAGER_KEY, PersistentDataType.STRING), VillagerPastStatus.class);
        return data.getOperator();
    }

    @EventHandler(ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager villager))
            return;
        if (!(event.getDamager() instanceof Player player))
            return;
        if (!isHelperStick(player.getInventory().getItemInMainHand()))
            return;
        if (!isManagedVillager(villager)) {
            player.sendMessage(Util.parseColours(config.getString("message.query-miss")));
        } else {
            String json = villager.getPersistentDataContainer().get(VILLAGER_KEY, PersistentDataType.STRING);
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                VillagerPastStatus status = gson.fromJson(json, VillagerPastStatus.class);
                String playerName = status.getOperator().toString();
                String queryName = Bukkit.getOfflinePlayer(status.getOperator()).getName();
                if (queryName != null) {
                    playerName = queryName;
                }
                player.sendMessage(Util.parseColours(String.format(config.getString("message.query-hit", ""), playerName)));
            });
        }
        event.setCancelled(true);
    }

    /**
     * 将村民纳入管理之下
     *
     * @param villager 村民
     * @param player   玩家
     */
    private void applyManage(@NotNull Villager villager, @NotNull UUID player) {
        if (!isManagedVillager(villager)) {
            VillagerPastStatus pastStatus = new VillagerPastStatus(villager.hasAI(), villager.isAware(), player);
            villager.getPersistentDataContainer().set(VILLAGER_KEY, PersistentDataType.STRING, gson.toJson(pastStatus));
        }
        villager.setAI(false);
        villager.setAware(false);
    }

    /**
     * 将村民移除出管理状态
     *
     * @param villager 村民
     */
    private void undoManage(@NotNull Villager villager) {
        if (!isManagedVillager(villager))
            return;
        VillagerPastStatus pastStatus = gson.fromJson(villager.getPersistentDataContainer().get(VILLAGER_KEY, PersistentDataType.STRING), VillagerPastStatus.class);
        if (pastStatus == null)
            return;
        villager.getPersistentDataContainer().remove(VILLAGER_KEY);
        villager.getPersistentDataContainer().remove(LAST_RESTOCK_AT_KEY);
        villager.setAI(pastStatus.isAiEnabled());
        villager.setAware(pastStatus.isAwareEnabled());
    }

    private boolean isManageWorking(@NotNull Villager villager) {
        return !villager.hasAI() && !villager.isAware();
    }

    /**
     * 使得指定的村民补货
     *
     * @param villager 村民
     * @return 是否补货成功
     */
    private boolean restockVillager(@NotNull Villager villager) {
        if (canRestock(villager)) {
            villager.getRecipes().forEach(recipe -> recipe.setUses(0));
            villager.getPersistentDataContainer().set(LAST_RESTOCK_AT_KEY, PersistentDataType.LONG, villager.getWorld().getGameTime());
            return true;
        }
        return false;
    }

    private boolean shouldCheckCanRestock(@NotNull Villager villager) {
        if (!isManagedVillager(villager))
            return false;
        Long lastRestockAt = villager.getPersistentDataContainer().get(LAST_RESTOCK_AT_KEY, PersistentDataType.LONG);
        if (lastRestockAt == null) {
            lastRestockAt = 0L;
        }
        return Math.abs(villager.getWorld().getGameTime() - lastRestockAt) > getConfig().getLong("restock-max-duration");
    }

    private boolean canRestock(@NotNull Villager villager) {
        if (!isManagedVillager(villager))
            return false;
        Location jobSitePos = villager.getMemory(MemoryKey.JOB_SITE);
        if (jobSitePos == null)
            return false;
        Block jobSite = jobSitePos.getBlock();
        Material jobMaterial = jobSites.get(villager.getProfession());
        if (jobMaterial != null) {
            if (jobSite.getType() != jobMaterial)
                return false;
        }
        if (jobSitePos.getWorld() != villager.getWorld())
            return false;
        return !(jobSitePos.distance(villager.getLocation()) > config.getInt("jobsite-max-distance", 2));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0)
            return false;
        if (args[0].equalsIgnoreCase("give")) {
            if (sender.hasPermission("villageraihelper.give") && sender instanceof Player player) {
                player.getInventory().addItem(HELPER_STICK);
                return true;
            }
        }
        if (args[0].equalsIgnoreCase("stats")) {
            if (sender.hasPermission("villageraihelper.stats")) {
                sender.sendMessage("Calculating...");
                AtomicInteger total = new AtomicInteger(0);
                AtomicInteger canRestock = new AtomicInteger(0);
                Bukkit.getWorlds().forEach(world -> world.getEntitiesByClass(Villager.class).forEach(villager -> {
                    if (!isManagedVillager(villager))
                        return;
                    total.incrementAndGet();
                    if (canRestock(villager))
                        canRestock.incrementAndGet();
                }));
                sender.sendMessage("Total managed (loaded and active) villager(s): " + total.get());
                sender.sendMessage("And " + canRestock.get() + " villager(s) can restock.");
                return true;
            }
        }
        return false;
    }
}

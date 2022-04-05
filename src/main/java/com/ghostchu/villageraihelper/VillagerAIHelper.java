package com.ghostchu.villageraihelper;

import com.google.gson.Gson;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftVillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class VillagerAIHelper extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private ItemStack HELPER_STICK;
    private final NamespacedKey VILLAGER_KEY = new NamespacedKey(this, "villager_managed");
    private int[] restockScheduler;
    private final Map<Villager.Profession, Material> jobSites = new HashMap<>();

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
        // 配置文件上色
        Util.parseColours(getConfig());
        // 设置工具物品
        ItemStack plugItem = new ItemStack(Material.STICK);
        ItemMeta meta = plugItem.getItemMeta();
        meta.setDisplayName(getConfig().getString("item.name"));
        List<String> lores = getConfig().getStringList("item.lores");
        lores = lores.stream().map(Util::parseColours).collect(Collectors.toList());
        meta.setLore(lores);
        plugItem.setItemMeta(meta);
        this.HELPER_STICK = plugItem;
        // 读取补货时间表
        List<Integer> restockTmp = getConfig().getIntegerList("restock-schedule");
        restockScheduler = new int[restockTmp.size()]; // use array for best performance
        for (int i = 0; i < restockTmp.size(); i++) {
            restockScheduler[i] = restockTmp.get(i);
            getLogger().info("Registering scheduler for time: " + restockScheduler[i]);
        }
        // 设置职业方块映射表
        this.setupJobSites();
        Bukkit.getPluginManager().registerEvents(this, this);
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
                getLogger().info("Checking for villagers restocking in world " + world.getName() + "!");
                int restocked = 0;
                int skipped = 0;
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
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    @EventHandler(ignoreCancelled = true)
    public void interact(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager))
            return;
        if (event.getHand() != EquipmentSlot.HAND)
            return;
        if (!event.getPlayer().getInventory().getItem(event.getHand()).isSimilar(HELPER_STICK)) {
            return;
        }
        Villager villager = (Villager) event.getRightClicked();
        if (!isManagedVillager(villager)) {
            applyManage(villager, event.getPlayer().getUniqueId());
            event.getPlayer().sendMessage(getConfig().getString("message.apply"));
        } else {
            undoManage(villager);
            event.getPlayer().sendMessage(getConfig().getString("message.undo"));
        }
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
        {
            if (!isManagedVillager(villager))
                return null;
            VillagerPastStatus data = gson.fromJson(villager.getPersistentDataContainer().get(VILLAGER_KEY, PersistentDataType.STRING), VillagerPastStatus.class);
            return data.getOperator();
        }
    }

    /**
     * 将村民纳入管理之下
     *
     * @param villager 村民
     * @param player   玩家
     */
    private void applyManage(@NotNull Villager villager, @NotNull UUID player) {
        if (isManagedVillager(villager))
            return;
        VillagerPastStatus pastStatus = new VillagerPastStatus(villager.hasAI(), villager.isAware(), player);
        villager.getPersistentDataContainer().set(VILLAGER_KEY, PersistentDataType.STRING, gson.toJson(pastStatus));
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
        villager.setAI(pastStatus.isAiEnabled());
        villager.setAware(pastStatus.isAwareEnabled());
    }

    /**
     * 使得指定的村民补货
     * @param villager 村民
     * @return 是否补货成功
     */
    private boolean restockVillager(@NotNull Villager villager) {
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
        // TODO: 当版本更新时，此处也需要同时更新
        // TODO： 1.17+ 时，此处需要使用混淆映射表
        CraftVillager craftVillager = (CraftVillager) villager;
        craftVillager.getHandle().fb(); // fb -> restock()
        return true;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender.hasPermission("villageraihelper.admin") && sender instanceof Player) {
            Player player = (Player) sender;
            player.getInventory().addItem(HELPER_STICK);
            return true;
        }
        return false;
    }
}

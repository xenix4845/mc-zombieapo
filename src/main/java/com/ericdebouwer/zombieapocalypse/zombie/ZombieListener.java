package com.ericdebouwer.zombieapocalypse.zombie;

import com.ericdebouwer.zombieapocalypse.ZombieApocalypse;
import com.ericdebouwer.zombieapocalypse.api.ZombieSpawnedEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.*;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.scheduler.BukkitTask;
import org.bukkit.attribute.Attribute;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

@RequiredArgsConstructor
public class ZombieListener implements Listener {

    private final ZombieApocalypse plugin;
    private final Map<UUID, BukkitTask> vindicatorTasks = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    private void onMobSpawn(CreatureSpawnEvent event){
        if (!(event.getEntity() instanceof Monster)) return;
        if (event.getEntity().hasMetadata("ignoreZombie")) return;
        
        String worldName = event.getLocation().getWorld().getName();
        if (!plugin.getApocalypseManager().isApocalypse(worldName)) return;
        if (plugin.getConfigManager().getIgnoredReasons().contains(event.getSpawnReason())) return;
        
        if (event.getEntity() instanceof Zombie zombie && ZombieType.getType(zombie) != null) return;

        event.setCancelled(true);
        
        if (event.getLocation().getBlockY() < plugin.getConfigManager().getMinSpawnHeight()) return;
        
        Bukkit.getScheduler().runTask(plugin, () -> 
            plugin.getZombieFactory().spawnApocalypseZombie(event.getLocation()));
    }

    @EventHandler
    private void onDeath(EntityDeathEvent event){
        if (!(event.getEntity() instanceof Zombie)) return;
        Zombie zombie = (Zombie) event.getEntity();

        if (plugin.getConfigManager().isRemoveSkullDrops()){
            event.getDrops().removeIf(i -> i.getType() == Material.PLAYER_HEAD);
        }
        
        ZombieType type = ZombieType.getType(zombie);
        if (type == ZombieType.BOOMER){
            createDelayedExplosion(zombie.getLocation(), 3f, zombie);
        }
        else if (type == ZombieType.MULTIPLIER){
            spawnMultiplierZombies(zombie.getLocation());
        }
    }

    @EventHandler
    public void onVindicatorSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Vindicator vindicator)) return;
        if (ZombieType.getType(vindicator) != ZombieType.EXPLOSIVE_VINDICATOR) return;
        
        // 주기적으로 체크하는 task 생성
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (vindicator.isDead() || !vindicator.isValid()) {
                vindicatorTasks.remove(vindicator.getUniqueId()).cancel();
                return;
            }
            
            Player target = (Player) vindicator.getTarget();
            if (target != null && target.isValid() && !target.isDead()) {
                double distance = vindicator.getLocation().distanceSquared(target.getLocation());
                if (distance < 6) { // 2.5 블록 이내
                    Location loc = vindicator.getLocation();
                    vindicator.remove();
                    loc.getWorld().createExplosion(
                        loc,
                        4f,
                        false,
                        plugin.getConfigManager().isBlockDamage(),
                        vindicator
                    );
                    vindicatorTasks.remove(vindicator.getUniqueId()).cancel();
                }
            }
        }, 0L, 2L); // 틱 주기 (0.1초마다 체크)
        
        vindicatorTasks.put(vindicator.getUniqueId(), task);
    }

    @EventHandler
    public void onVindicatorDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Vindicator vindicator)) return;
        if (ZombieType.getType(vindicator) != ZombieType.EXPLOSIVE_VINDICATOR) return;
        
        // 죽을 때 폭발
        Location loc = vindicator.getLocation();
        loc.getWorld().createExplosion(
            loc,
            4f,
            false,
            plugin.getConfigManager().isBlockDamage(),
            vindicator
        );
        
        // task 정리
        BukkitTask task = vindicatorTasks.remove(vindicator.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler
    public void onVindicatorDamage(EntityDamageByEntityEvent event) {

	    Entity damager = event.getDamager();
        Entity entity = event.getEntity();
        
        Vindicator vindicator = null;
        if (damager instanceof Vindicator) {
            vindicator = (Vindicator) damager;
        } else if (entity instanceof Vindicator && damager instanceof Player) {
            vindicator = (Vindicator) entity;
        }
        
        if (vindicator == null || ZombieType.getType(vindicator) != ZombieType.EXPLOSIVE_VINDICATOR) return;
        
        event.setCancelled(true);
        Location loc = vindicator.getLocation();
        vindicator.remove();
        
        createDelayedExplosion(loc, 4f, null);
    }

    // 플러그인 비활성화 시 모든 task 정리
    public void cleanup() {
        vindicatorTasks.values().forEach(BukkitTask::cancel);
        vindicatorTasks.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    private void onPhantomInteract(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity entity = event.getEntity();
        
        Phantom phantom = null;
        if (damager instanceof Phantom) {
            phantom = (Phantom) damager;
        } else if (entity instanceof Phantom && damager instanceof Player) {
            phantom = (Phantom) entity;
        }
        
        if (phantom == null || ZombieType.getType((Mob)phantom) != ZombieType.BANSHEE) return;
        
        event.setCancelled(true);
        Location loc = phantom.getLocation().clone();
        phantom.remove();
        
        createDelayedExplosion(loc, 4f, null);
    }
    
    private void createDelayedExplosion(Location loc, float power, Entity source) {
        Bukkit.getScheduler().runTask(plugin, () -> 
            loc.getWorld().createExplosion(
                loc, 
                power,
                false,
                plugin.getConfigManager().isBlockDamage(),
                source
            )
        );
    }
    
    private void spawnMultiplierZombies(Location loc) {
        int zombieAmount = ThreadLocalRandom.current().nextInt(5);
        for (int i = 0; i <= zombieAmount; i++){
            double xOffset = ThreadLocalRandom.current().nextDouble() * 2 - 1;
            double zOffset = ThreadLocalRandom.current().nextDouble() * 2 - 1;
            plugin.getZombieFactory().spawnZombie(
                loc.clone().add(xOffset, 0, zOffset),
                ZombieType.DEFAULT,
                ZombieSpawnedEvent.SpawnReason.ZOMBIE_EFFECT
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    private void throwerHit(final EntityDamageByEntityEvent event){
        if (!(event.getDamager() instanceof Zombie)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        
        ZombieType type = ZombieType.getType((Zombie) event.getDamager());
        if (type == ZombieType.THROWER){
            Bukkit.getScheduler().runTask(plugin, () -> {
                Vector newSpeed = event.getDamager().getLocation().getDirection().multiply(1.5).setY(1.5);
                event.getEntity().setVelocity(newSpeed);
            });
        }
    }

    @EventHandler
    private void onBurn(EntityCombustEvent event){
        if (plugin.getConfigManager().isBurnInDay()) return;
        if (!(event.getEntity() instanceof Zombie)) return;

        if (ZombieType.getType((Zombie) event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onSpawnerSpawn(SpawnerSpawnEvent event){
        ZombieType type = plugin.getZombieItems().getZombieType(event.getSpawner());
        if (type == null) return;

        event.getEntity().remove();
        plugin.getZombieFactory().spawnZombie(event.getLocation(), type, ZombieSpawnedEvent.SpawnReason.CUSTOM_SPAWNER);
    }

    @EventHandler
    public void onEggSpawn(PlayerInteractEvent event){
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) return;
        if (event.getItem().getType() != Material.ZOMBIE_SPAWN_EGG) return;

        ZombieType type = plugin.getZombieItems().getZombieType(event.getItem().getItemMeta());
        if (type == null) return;

        event.setCancelled(true);
        Location spawnLoc = event.getClickedBlock().getLocation().add(0, 1, 0);
        plugin.getZombieFactory().spawnZombie(spawnLoc, type, ZombieSpawnedEvent.SpawnReason.SPAWN_EGG);

        if (event.getPlayer().getGameMode() == GameMode.SURVIVAL){
            handleItemConsumption(event);
        }
    }
    
    private void handleItemConsumption(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
            event.getPlayer().getInventory().setItem(event.getHand(), item);
        } else {
            event.getPlayer().getInventory().setItem(event.getHand(), null);
        }
    }

    @EventHandler
    public void onZombieClick(PlayerInteractEntityEvent event){
        if (event.getPlayer().getEquipment() == null) return;

        ItemStack hand = event.getPlayer().getEquipment().getItem(event.getHand());
        if (hand.getType() != Material.ZOMBIE_SPAWN_EGG || 
            plugin.getZombieItems().getZombieType(hand.getItemMeta()) == null) return;

        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event){
        ZombieType type = plugin.getZombieItems().getZombieType(event.getItemInHand().getItemMeta());
        if (type == null) return;

        if (!(event.getBlock().getState() instanceof CreatureSpawner spawner)) return;

        spawner.getPersistentDataContainer().set(plugin.getZombieItems().getKey(), 
            PersistentDataType.STRING, type.toString());
        spawner.setSpawnedType(EntityType.ZOMBIE);
        spawner.update();
    }
}
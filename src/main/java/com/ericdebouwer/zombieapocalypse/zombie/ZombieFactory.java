package com.ericdebouwer.zombieapocalypse.zombie;

import com.ericdebouwer.zombieapocalypse.ZombieApocalypse;
import com.ericdebouwer.zombieapocalypse.api.ZombiePreSpawnEvent;
import com.ericdebouwer.zombieapocalypse.api.ZombieSpawnedEvent;
import com.ericdebouwer.zombieapocalypse.config.ZombieWrapper;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class ZombieFactory {

    private final ZombieApocalypse plugin;
    private final Map<ZombieType, ZombieWrapper> zombieWrappers = new HashMap<>();

    public void reload() {
        zombieWrappers.clear();
    }

    public void addZombieWrapper(ZombieWrapper wrapper) {
        zombieWrappers.put(wrapper.getType(), wrapper);
    }

    public @Nonnull ZombieWrapper getWrapper(ZombieType type) {
        return zombieWrappers.getOrDefault(type, new ZombieWrapper(type));
    }

    private ZombieType getRandomZombieType() {
        Map<ZombieType, Integer> weightedTypes = new HashMap<>();
        int totalWeight = 0;
        
        for (Map.Entry<ZombieType, ZombieWrapper> entry : zombieWrappers.entrySet()) {
            int weight = plugin.getConfig().getInt("zombies." + entry.getKey().toString().toLowerCase() + ".spawn_weight", 100);
            weightedTypes.put(entry.getKey(), weight);
            totalWeight += weight;
        }
        
        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int countWeight = 0;
        
        for (Map.Entry<ZombieType, Integer> entry : weightedTypes.entrySet()) {
            countWeight += entry.getValue();
            if (random < countWeight) {
                return entry.getKey();
            }
        }
        
        return ZombieType.DEFAULT;
    }

    public void spawnApocalypseZombie(Location loc) {
        ZombiePreSpawnEvent preSpawnEvent = new ZombiePreSpawnEvent(loc, getRandomZombieType());
        Bukkit.getServer().getPluginManager().callEvent(preSpawnEvent);

        if (!preSpawnEvent.isCancelled()) {
            this.spawnZombie(loc, preSpawnEvent.getType(), ZombieSpawnedEvent.SpawnReason.APOCALYPSE);
        }
    }

    public Mob spawnZombie(Location loc, ZombieSpawnedEvent.SpawnReason reason) {
        return this.spawnZombie(loc, getRandomZombieType(), reason);
    }

    public Mob spawnZombie(Location loc, ZombieType type, ZombieSpawnedEvent.SpawnReason reason) {
        if (!loc.getChunk().isLoaded()) {
            return null;
        }
        
        Mob mob = this.spawnForEnvironment(loc, type, reason);
        if (mob == null) return null;
        
        mob.setRemoveWhenFarAway(true);
        if (mob.getVehicle() != null) {
            mob.getVehicle().remove();
        }

        if (mob instanceof Zombie) {
            setupZombie((Zombie)mob, type, loc, reason);
        }

        ZombieSpawnedEvent spawnedEvent = new ZombieSpawnedEvent(loc, type, reason, mob);
        Bukkit.getServer().getPluginManager().callEvent(spawnedEvent);
        
        return mob;
    }
    
    private void setupZombie(Zombie zombie, ZombieType type, Location loc, ZombieSpawnedEvent.SpawnReason reason) {
        zombie = (Zombie) getWrapper(type).apply(zombie);
        
        if (!plugin.getConfigManager().doBabies()) {
            zombie.setBaby(false);
        }

        switch (type) {
            case JUMPER:
                zombie.addPotionEffect(new PotionEffect(
                    PotionEffectType.JUMP, 
                    Integer.MAX_VALUE, 
                    5, 
                    false, false, false
                ));
                break;
                
            case PILLAR:
                zombie.setBaby(false);
                int passengers = ThreadLocalRandom.current().nextInt(4) + 1;
                Zombie lowerZombie = zombie;
                
                for (int i = 1; i <= passengers; i++) {
                    Location passengerLoc = loc.clone().add(0, 1.5 * i, 0);
                    Mob newMob = this.spawnZombie(
                        passengerLoc, 
                        ZombieType.DEFAULT, 
                        ZombieSpawnedEvent.SpawnReason.ZOMBIE_EFFECT
                    );
                    if (newMob instanceof Zombie) {
                        Zombie newZombie = (Zombie) newMob;
                        newZombie.setBaby(false);
                        lowerZombie.addPassenger(newZombie);
                        lowerZombie = newZombie;
                    }
                }
                break;
        }
    }
    
    private Mob spawnForEnvironment(Location loc, ZombieType type, ZombieSpawnedEvent.SpawnReason reason) {
        if (type == ZombieType.BANSHEE) {
            try {
                Location spawnLoc = loc.clone().add(0, 10, 0);
                Phantom phantom;
                
                if (plugin.isPaperMC()) {
                    phantom = loc.getWorld().spawn(spawnLoc, Phantom.class, CreatureSpawnEvent.SpawnReason.NATURAL);
                } else {
                    phantom = loc.getWorld().spawn(spawnLoc, Phantom.class);
                }
                
                type.set(phantom);
                phantom.setSize(2);
                phantom.addScoreboardTag("ApocalypsePhantom");
                phantom.setAware(true);

                if (loc.getWorld().getEnvironment() == World.Environment.NETHER) {
                    phantom.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 
                        Integer.MAX_VALUE, 1, false, false, false));
                }

                // 150블록 범위 내의 모든 플레이어를 가져와서 무작위로 하나 선택
                List<Player> nearbyPlayers = loc.getWorld().getNearbyEntities(loc, 150, 150, 150, 
                    entity -> entity instanceof Player && !((Player)entity).isDead())
                    .stream()
                    .map(entity -> (Player)entity)
                    .collect(Collectors.toList());
                    
                if (!nearbyPlayers.isEmpty()) {
                    Player target = nearbyPlayers.get(ThreadLocalRandom.current().nextInt(nearbyPlayers.size()));
                    phantom.setTarget(target);
                }
                        
                return phantom;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to spawn Phantom: " + e.getMessage());
                return null;
            }
        }
        
        boolean isNether = loc.getWorld().getEnvironment() == World.Environment.NETHER;
        Class<? extends Zombie> environmentType = (isNether && plugin.getConfigManager().doNetherPigmen()) 
            ? PigZombie.class 
            : Zombie.class;

        if (loc.getBlock().getType() == Material.WATER) {
            environmentType = Drowned.class;
        }

        Zombie zombie;
        if (plugin.isPaperMC()) {
            zombie = loc.getWorld().spawn(loc, environmentType, CreatureSpawnEvent.SpawnReason.NATURAL, type::set);
        } else {
            zombie = loc.getWorld().spawn(loc, environmentType, type::set);
        }

        if (zombie.getType() == EntityType.ZOMBIE && isNether) {
            zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 
                Integer.MAX_VALUE, 1, false, false, false));
        }

        return zombie;
    }
}
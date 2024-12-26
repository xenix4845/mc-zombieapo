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

@RequiredArgsConstructor
public class ZombieFactory {

    private final ZombieApocalypse plugin;
    private final Map<ZombieType, ZombieWrapper> zombieWrappers = new HashMap<>();

    public void reload() {
        zombieWrappers.clear();
    }

    public void addZombieWrapper(ZombieWrapper wrapper){
        zombieWrappers.put(wrapper.getType(), wrapper);
    }

    public @Nonnull ZombieWrapper getWrapper(ZombieType type){
        return zombieWrappers.getOrDefault(type, new ZombieWrapper(type));
    }

    private ZombieType getRandomZombieType(){
        List<ZombieType> types = new ArrayList<>(zombieWrappers.keySet());
        if (types.isEmpty()) types = Arrays.asList(ZombieType.values());
        return types.get(ThreadLocalRandom.current().nextInt(types.size()));
    }

    // only invoked for regular apocalypse zombies
    public void spawnApocalypseZombie(Location loc){
        ZombiePreSpawnEvent preSpawnEvent = new ZombiePreSpawnEvent(loc, getRandomZombieType());
        Bukkit.getServer().getPluginManager().callEvent(preSpawnEvent);

        if (!preSpawnEvent.isCancelled()){
            this.spawnZombie(loc, preSpawnEvent.getType(), ZombieSpawnedEvent.SpawnReason.APOCALYPSE);
        }
    }

    public Zombie spawnZombie(Location loc, ZombieSpawnedEvent.SpawnReason reason){
        return this.spawnZombie(loc, getRandomZombieType(), reason);
    }

    public Zombie spawnZombie(Location loc, ZombieType type, ZombieSpawnedEvent.SpawnReason reason) {
        // 청크가 로드되지 않은 경우 처리
        if (!loc.getChunk().isLoaded()) {
            return null;
        }
        
        // spawnForEnvironment 메소드는 환경별 특수 처리(네더/물속 등)가 있으므로 유지
        Zombie zombie = this.spawnForEnvironment(loc, type);
        
        // 기본 설정들
        zombie.setRemoveWhenFarAway(true);
        if (zombie.getVehicle() != null) {
            zombie.getVehicle().remove();
        }

        // 좀비 특성 적용 - 기존 래퍼 로직 유지
        zombie = getWrapper(type).apply(zombie);

        // 아기 좀비 설정
        if (!plugin.getConfigManager().doBabies()) {
            zombie.setBaby(false);
        }

        // 특수 타입별 처리
        switch (type) {
            case JUMPER:
                // 기존 점프 효과 유지
                zombie.addPotionEffect(new PotionEffect(
                    PotionEffectType.JUMP, 
                    Integer.MAX_VALUE, 
                    5, 
                    false, false, false
                ));
                break;
                
            case PILLAR:
                // 기존 필러 좀비 로직 유지
                zombie.setBaby(false);
                int passengers = ThreadLocalRandom.current().nextInt(4) + 1; // [1-4], so 2-5 high
                Zombie lowerZombie = zombie;
                
                // 상단 좀비들 스폰
                for (int i = 1; i <= passengers; i++) {
                    Location passengerLoc = loc.clone().add(0, 1.5 * i, 0);
                    Zombie newZombie = this.spawnZombie(
                        passengerLoc, 
                        ZombieType.DEFAULT, 
                        ZombieSpawnedEvent.SpawnReason.ZOMBIE_EFFECT
                    );
                    newZombie.setBaby(false);
                    lowerZombie.addPassenger(newZombie);
                    lowerZombie = newZombie;
                }
                break;
        }

        // 이벤트 발생
        ZombieSpawnedEvent spawnedEvent = new ZombieSpawnedEvent(loc, type, reason, zombie);
        Bukkit.getServer().getPluginManager().callEvent(spawnedEvent);
        
        return spawnedEvent.getZombie();
    }
    
    private Mob spawnForEnvironment(Location loc, ZombieType type) {
        if (type == ZombieType.BANSHEE) {
            if (!loc.getChunk().isLoaded()) {
                return null;
            }

            try {
                Location spawnLoc = loc.clone().add(0, 10, 0);
                Phantom phantom;
                
                if (plugin.isPaperMC()) {
                    phantom = loc.getWorld().spawn(spawnLoc, Phantom.class, CreatureSpawnEvent.SpawnReason.NATURAL, (entity) -> {
                        type.set(entity);
                        entity.setSize(2);
                        entity.addScoreboardTag("ApocalypsePhantom");
                        if (entity instanceof Mob mob) {
                            mob.setAware(true);
                        }
                    });
                } else {
                    phantom = loc.getWorld().spawn(spawnLoc, Phantom.class);
                    type.set(phantom);
                    phantom.setSize(2);
                    phantom.addScoreboardTag("ApocalypsePhantom");
                    if (phantom instanceof Mob mob) {
                        mob.setAware(true);
                    }
                }

                if (loc.getWorld().getEnvironment() == World.Environment.NETHER) {
                    phantom.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 
                        Integer.MAX_VALUE, 1, false, false, false));
                }

                phantom.setTarget(loc.getWorld().getNearbyEntities(loc, 32, 32, 32, 
                        entity -> entity instanceof Player)
                    .stream()
                    .filter(entity -> entity instanceof Player)
                    .map(entity -> (Player) entity)
                    .findFirst()
                    .orElse(null));
                    
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

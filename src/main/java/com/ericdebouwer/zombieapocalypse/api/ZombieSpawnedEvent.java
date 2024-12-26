package com.ericdebouwer.zombieapocalypse.api;

import com.ericdebouwer.zombieapocalypse.zombie.ZombieType;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import javax.annotation.Nonnull;

public class ZombieSpawnedEvent extends Event {
    private static final HandlerList handlers = new HandlerList();

    private final Location location;
    private final ZombieType type;
    private final SpawnReason reason;
    private final Mob mob;

    public ZombieSpawnedEvent(Location location, ZombieType type, SpawnReason reason, Mob mob) {
        this.location = location;
        this.type = type;
        this.reason = reason;
        this.mob = mob;
    }

    public @Nonnull Location getLocation() {
        return location;
    }

    public @Nonnull SpawnReason getSpawnReason() {
        return reason;
    }

    public @Nonnull ZombieType getType() {
        return type;
    }

    public @Nonnull Mob getMob() {
        return mob;
    }

    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    public enum SpawnReason {
        APOCALYPSE,
        ZOMBIE_EFFECT,
        SPAWN_EGG,
        CUSTOM_SPAWNER,
        API
    }
}
package com.ericdebouwer.zombieapocalypse.zombie;

import org.bukkit.entity.*;
import javax.annotation.Nullable;

public enum ZombieType {
    DEFAULT,
    SPRINTER,
    BOOMER,
    THROWER,
    TANK,
    NINJA,
    MULTIPLIER,
    JUMPER,
    PILLAR,
    BANSHEE,  // 반자이 팬텀
    EXPLOSIVE_VINDICATOR;  // 자폭 변명자

    private final static String ZOMBIE_IDENTIFIER = "ApocalypseZombieType";

    /**
     * Get the type of a zombie, or null if doesn't have one
     *
     * @return the type of a zombie, or null
     */
    public static @Nullable ZombieType getType(Mob mob) {
        if (!(mob instanceof Zombie || mob instanceof Phantom || mob instanceof Vindicator)) {
            return null;
        }

        for (String tag : mob.getScoreboardTags()) {
            if (tag.startsWith(ZOMBIE_IDENTIFIER)) {
                try {
                    String type = tag.replaceFirst(ZOMBIE_IDENTIFIER, "");
                    return ZombieType.valueOf(type);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    public Mob set(Mob mob) {
        String type = ZOMBIE_IDENTIFIER + this.toString();
        mob.getScoreboardTags().add(type);
        return mob;
    }
}


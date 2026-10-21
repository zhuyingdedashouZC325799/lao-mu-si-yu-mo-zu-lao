package com.hanafubuki.duet;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;

/**
 * The kick damage type.
 *
 * <p>The damage is authored as data, not as a hand-built source: the type json
 * lives at {@code data/mmdduet/damage_type/kick.json}, and the mod adds it to
 * Minecraft's own {@code bypasses_armor}, {@code bypasses_enchantments},
 * {@code bypasses_resistance}, {@code bypasses_effects} and
 * {@code bypasses_shield} tags. Those tags are what turn a plain number into
 * true damage: armour, Protection, Resistance, Absorption and a raised shield
 * are all skipped by the vanilla damage pipeline itself, so the target loses
 * exactly the stated amount and every other mod that reads damage sees the same
 * number. Faking it by writing health directly would skip the death handling,
 * the hurt animation and the combat tracker.
 *
 * <p>1.20.1 exposes the {@code DamageSource(Holder, Entity, Entity)}
 * constructor directly, so the source is built from the datapack registry
 * rather than through {@code DamageSources}, whose {@code source(...)} helpers
 * are private in this version.
 */
public final class DuetDamage {

    public static final ResourceKey<DamageType> KICK =
            ResourceKey.create(Registries.DAMAGE_TYPE, DuetMod.id("kick"));

    private DuetDamage() {
    }

    /** A kick source attributed to {@code attacker}, both directly and causally. */
    public static DamageSource kick(ServerLevel level, Entity attacker) {
        Registry<DamageType> registry =
                level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        Holder<DamageType> holder = registry.getHolderOrThrow(KICK);
        return new DamageSource(holder, attacker, attacker);
    }
}

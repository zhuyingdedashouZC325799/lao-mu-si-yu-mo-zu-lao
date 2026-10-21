package com.hanafubuki.duet;

import java.lang.reflect.Field;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * Widens the {@code max_health} attribute range so the singers can keep the
 * hit points they were given.
 *
 * <p>Vanilla declares max health as {@code new RangedAttribute(..., 1.0, 1024.0)}
 * and {@code AttributeInstance#getValue()} runs the total through
 * {@code sanitizeValue}, which clamps to that upper bound. Any base value or
 * modifier above 1024 therefore reads back as 1024. The only way past it
 * without a mixin is to rewrite the range field itself, which is what the
 * widely used AttributeFix mod does as well.
 *
 * <p>The change is global and harmless: it only raises the ceiling, it never
 * alters a value that was already inside the vanilla range.
 *
 * <p>1.20.1 keeps {@code Attributes.MAX_HEALTH} as a plain {@link Attribute}
 * rather than a registry holder, so the constant is used directly.
 */
final class HealthCeiling {

    /** Wide enough for any boss-style pool while staying finite. */
    private static final double NEW_MAX = 4096.0;

    /**
     * Names the upper bound field can have.
     *
     * <p>The Forge 1.20.1 runtime is remapped to SRG, so the field the source
     * calls {@code maxValue} is {@code f_22308_} at run time. Looking up the
     * source name alone therefore threw {@code NoSuchFieldException}, the
     * exception was swallowed, and both singers silently shipped with the
     * vanilla 1024 ceiling - which is exactly what the in-game attribute query
     * reported. Matching on name order first and then on "the last double
     * declared by the class" covers official, SRG and Mojang-mapped runtimes
     * without knowing which one we are on.
     */
    private static final String[] MAX_NAMES = {"maxValue", "f_22308_", "field_22308"};

    private static boolean applied;

    private HealthCeiling() {
    }

    static synchronized void raise() {
        if (applied) {
            return;
        }
        applied = true;
        Attribute attribute = Attributes.MAX_HEALTH;
        if (!(attribute instanceof RangedAttribute ranged)) {
            return;
        }
        if (ranged.getMaxValue() >= NEW_MAX) {
            return;
        }
        for (String name : MAX_NAMES) {
            if (write(ranged, field(name))) {
                return;
            }
        }
        // Last resort: find the double field that currently holds the value
        // getMaxValue() reports. Picking by declaration order alone would risk
        // overwriting minValue instead, which would leave the range inverted.
        double reported = ranged.getMaxValue();
        for (Field field : RangedAttribute.class.getDeclaredFields()) {
            if (field.getType() != double.class) {
                continue;
            }
            if (Math.abs(read(ranged, field) - reported) > 1.0e-9) {
                continue;
            }
            if (write(ranged, field)) {
                return;
            }
        }
    }

    private static double read(RangedAttribute ranged, Field field) {
        try {
            field.setAccessible(true);
            return field.getDouble(ranged);
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    private static Field field(String name) {
        try {
            Field field = RangedAttribute.class.getDeclaredField(name);
            return field.getType() == double.class ? field : null;
        } catch (Throwable missing) {
            return null;
        }
    }

    private static boolean write(RangedAttribute ranged, Field field) {
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            field.setDouble(ranged, NEW_MAX);
            return true;
        } catch (Throwable ignored) {
            // A future mapping change would break every name here; the singers
            // then simply fall back to the vanilla 1024 ceiling instead of
            // failing to load the mod at all.
            return false;
        }
    }
}

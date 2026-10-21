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
 * and {@link net.minecraft.world.entity.ai.attributes.AttributeInstance#getValue()}
 * runs the total through {@code sanitizeValue}, which clamps to that upper
 * bound. Any base value or modifier above 1024 therefore reads back as 1024.
 * The only way past it without a mixin is to rewrite the range field itself,
 * which is what the widely used AttributeFix mod does as well.
 *
 * <p>The change is global and harmless: it only raises the ceiling, it never
 * alters a value that was already inside the vanilla range.
 */
final class HealthCeiling {

    /** Wide enough for any boss-style pool while staying finite. */
    private static final double NEW_MAX = 4096.0;

    private static boolean applied;

    private HealthCeiling() {
    }

    static synchronized void raise() {
        if (applied) {
            return;
        }
        applied = true;
        Attribute attribute = Attributes.MAX_HEALTH.value();
        if (!(attribute instanceof RangedAttribute ranged)) {
            return;
        }
        if (ranged.getMaxValue() >= NEW_MAX) {
            return;
        }
        try {
            Field field = RangedAttribute.class.getDeclaredField("maxValue");
            field.setAccessible(true);
            field.setDouble(ranged, NEW_MAX);
        } catch (Throwable ignored) {
            // A future mapping change would break the field name; the singers
            // then simply fall back to the vanilla 1024 ceiling instead of
            // failing to load the mod at all.
        }
    }
}

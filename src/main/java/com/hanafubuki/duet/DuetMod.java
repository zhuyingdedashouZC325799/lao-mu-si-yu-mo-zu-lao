package com.hanafubuki.duet;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Registers the two singers, their spawn eggs and the duet sound.
 */
public final class DuetMod implements ModInitializer {

    public static final String ID = "mmdduet";

    public static EntityType<SingerEntity> SI;
    public static EntityType<SingerEntity> LIINO;
    public static Item SI_EGG;
    public static Item LIINO_EGG;
    public static SoundEvent DUET;

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    @Override
    public void onInitialize() {
        HealthCeiling.raise();
        SI = registerSinger("si");
        LIINO = registerSinger("liino");
        SI_EGG = registerEgg("si_spawn_egg", SI);
        LIINO_EGG = registerEgg("liino_spawn_egg", LIINO);
        DUET = Registry.register(BuiltInRegistries.SOUND_EVENT, id("duet"),
                SoundEvent.createVariableRangeEvent(id("duet")));

        FabricDefaultAttributeRegistry.register(SI, SingerEntity.attributes(SingerEntity.SI_MAX_HEALTH));
        FabricDefaultAttributeRegistry.register(LIINO, SingerEntity.attributes(SingerEntity.LIINO_MAX_HEALTH));

        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(
                        Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("spawn_eggs")))
                .register((FabricCreativeModeTabOutput output) -> {
                    output.accept(SI_EGG);
                    output.accept(LIINO_EGG);
                });
    }

    private static EntityType<SingerEntity> registerSinger(String name) {
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id(name));
        EntityType<SingerEntity> type = EntityType.Builder
                .of(SingerEntity::new, MobCategory.MISC)
                .sized(0.85F, 1.9F)
                .eyeHeight(1.72F)
                // The MMD mesh is drawn outside the vanilla model path, which
                // leaves the hitbox derived NAME_TAG anchor unset.  Without an
                // explicit offset the label is never drawn at all, so pin it
                // just above the modelled head.
                .nameTagOffset(1.70F)
                .clientTrackingRange(10)
                .updateInterval(2)
                .build(key);
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
    }

    private static Item registerEgg(String name, EntityType<SingerEntity> type) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
        Item item = new SpawnEggItem(new Item.Properties().setId(key).spawnEgg(type));
        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }
}

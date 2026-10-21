package com.hanafubuki.duet;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers the two singers, their spawn eggs and the duet sound.
 *
 * <p>The Fabric build registers straight into {@code BuiltInRegistries} while
 * the mod initialises. NeoForge instead wants a {@link DeferredRegister} per
 * registry, handed to the mod event bus and only fired during the register
 * phase, so none of the entries exist yet while this constructor runs. That is
 * why the fields are {@link DeferredHolder}s and every read site goes through
 * {@code get()} rather than caching a value here: calling {@code value()} in
 * the constructor throws "Cannot get value from unbound holder".
 *
 * <p>Everything below this glue - the entity, the goals, the mesh pipeline, the
 * renderer - is the same source as the other builds, because NeoForge and
 * Fabric ship the identical Mojang-mapped Minecraft classes for 26.1.2.
 */
@Mod(DuetMod.ID)
public final class DuetMod {

    public static final String ID = "mmdduet";

    private static final DeferredRegister.Entities ENTITIES =
            DeferredRegister.createEntities(ID);
    private static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(ID);
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, ID);

    public static final DeferredHolder<EntityType<?>, EntityType<SingerEntity>> SI =
            registerSinger("si");
    public static final DeferredHolder<EntityType<?>, EntityType<SingerEntity>> LIINO =
            registerSinger("liino");

    public static final DeferredHolder<Item, Item> SI_EGG =
            registerEgg("si_spawn_egg", SI);
    public static final DeferredHolder<Item, Item> LIINO_EGG =
            registerEgg("liino_spawn_egg", LIINO);

    public static final DeferredHolder<SoundEvent, SoundEvent> DUET =
            SOUNDS.register("duet",
                    () -> SoundEvent.createVariableRangeEvent(id("duet")));

    public DuetMod(IEventBus modBus) {
        // HealthCeiling only rewrites a field on the vanilla max_health
        // attribute object, which already exists by the time any mod
        // constructs, so it does not need to wait for a register event.
        HealthCeiling.raise();

        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        SOUNDS.register(modBus);

        modBus.addListener(this::attributes);
        modBus.addListener(this::creativeTab);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    /**
     * Attribute suppliers have to be handed over in this event.
     *
     * <p>On Fabric the same map is filled by
     * {@code FabricDefaultAttributeRegistry.register}. NeoForge refuses to
     * spawn a mob whose type has no supplier here, so leaving it out makes the
     * spawn egg place nothing while logging no obvious error.
     */
    private void attributes(EntityAttributeCreationEvent event) {
        // .build() is required: SingerEntity.attributes returns the mutable
        // Builder, while the event stores a finished AttributeSupplier.
        event.put(SI.get(), SingerEntity.attributes(SingerEntity.SI_MAX_HEALTH).build());
        event.put(LIINO.get(), SingerEntity.attributes(SingerEntity.LIINO_MAX_HEALTH).build());
    }

    /** Puts both eggs in the vanilla spawn egg tab. */
    private void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(ResourceKey.create(
                Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("spawn_eggs")))) {
            return;
        }
        // The single argument accept() defaults to PARENT_AND_SEARCH_TABS.
        // Naming that constant directly is not possible here: TabVisibility is
        // declared protected inside CreativeModeTab, so it is not visible from
        // this package at all.
        event.accept(new ItemStack(SI_EGG.get()));
        event.accept(new ItemStack(LIINO_EGG.get()));
    }

    private static DeferredHolder<EntityType<?>, EntityType<SingerEntity>> registerSinger(String name) {
        // EntityFactory is generic over the exact entity class, so the lambda
        // has to name the parameter type instead of relying on inference.
        return ENTITIES.registerEntityType(name,
                (EntityType<SingerEntity> type, Level level) -> new SingerEntity(type, level),
                MobCategory.MISC,
                builder -> builder
                        .sized(0.85F, 1.9F)
                        .eyeHeight(1.72F)
                        // The MMD mesh is drawn outside the vanilla model path,
                        // which leaves the hitbox derived NAME_TAG anchor unset.
                        // Without an explicit offset the label is never drawn at
                        // all, so pin it just above the modelled head.
                        .nameTagOffset(1.70F)
                        .clientTrackingRange(10)
                        .updateInterval(2));
    }

    private static DeferredHolder<Item, Item> registerEgg(
            String name, DeferredHolder<EntityType<?>, EntityType<SingerEntity>> type) {
        return ITEMS.register(name, () -> {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
            // The entity type is resolved through the deferred holder only
            // when the item is actually built, so the egg does not require the
            // entity to be registered first.
            return new SpawnEggItem(new Item.Properties().setId(key).spawnEgg(type.get()));
        });
    }
}

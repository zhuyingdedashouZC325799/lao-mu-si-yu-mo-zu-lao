package com.hanafubuki.duet;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers the two singers, their spawn eggs and the duet sound.
 *
 * <p>This is the 1.20.1 Forge build. Forge wants a {@link DeferredRegister} per
 * registry, handed to the mod event bus and only fired during the register
 * phase, so none of the entries exist yet while this constructor runs. Every
 * read site therefore goes through {@link RegistryObject#get()} instead of
 * caching a value here.
 *
 * <p>Everything below this glue - the entity, the goals, the mesh pipeline, the
 * renderer - is the same source as the other builds, with only the renames the
 * 1.20.1 mappings require.
 */
@Mod(DuetMod.ID)
public final class DuetMod {

    public static final String ID = "mmdduet";

    /**
     * The vanilla spawn egg tab key.
     *
     * <p>Built by hand because {@code CreativeModeTabs.SPAWN_EGGS} is private in
     * 1.20.1; {@link ResourceKey} equality is by id, so this matches the real
     * tab key exactly.
     */
    private static final ResourceKey<CreativeModeTab> SPAWN_EGGS_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    new ResourceLocation("minecraft", "spawn_eggs"));

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, ID);
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, ID);

    public static final RegistryObject<EntityType<SingerEntity>> SI =
            registerSinger("si");
    public static final RegistryObject<EntityType<SingerEntity>> LIINO =
            registerSinger("liino");

    public static final RegistryObject<Item> SI_EGG =
            registerEgg("si_spawn_egg", SI, 0xE6DEDB, 0xB4303A);
    public static final RegistryObject<Item> LIINO_EGG =
            registerEgg("liino_spawn_egg", LIINO, 0xF0E7DA, 0x6C5AA6);

    public static final RegistryObject<SoundEvent> DUET =
            SOUNDS.register("duet",
                    () -> SoundEvent.createVariableRangeEvent(id("duet")));

    public DuetMod() {
        // HealthCeiling only rewrites a field on the vanilla max_health
        // attribute object, which already exists by the time any mod
        // constructs, so it does not need to wait for a register event.
        HealthCeiling.raise();

        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        ITEMS.register(bus);
        SOUNDS.register(bus);

        bus.addListener(this::attributes);
        bus.addListener(this::creativeTab);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(ID, path);
    }

    /**
     * Attribute suppliers have to be handed over in this event.
     *
     * <p>Forge refuses to spawn a mob whose type has no supplier here, so
     * leaving it out makes the spawn egg place nothing while logging no obvious
     * error.
     */
    private void attributes(EntityAttributeCreationEvent event) {
        // .build() is required: SingerEntity.attributes returns the mutable
        // Builder, while the event stores a finished AttributeSupplier.
        event.put(SI.get(), SingerEntity.attributes(SingerEntity.SI_MAX_HEALTH).build());
        event.put(LIINO.get(), SingerEntity.attributes(SingerEntity.LIINO_MAX_HEALTH).build());
    }

    /** Puts both eggs in the vanilla spawn egg tab. */
    private void creativeTab(BuildCreativeModeTabContentsEvent event) {
        // CreativeModeTabs.SPAWN_EGGS is private in 1.20.1, so the key is
        // matched by its id instead of by identity.
        if (!SPAWN_EGGS_KEY.equals(event.getTabKey())) {
            return;
        }
        // The single argument accept() defaults to PARENT_AND_SEARCH_TABS.
        // Naming that constant directly is not possible here: TabVisibility is
        // declared protected inside CreativeModeTab, so it is not visible from
        // this package at all.
        event.accept(SI_EGG);
        event.accept(LIINO_EGG);
    }

    private static RegistryObject<EntityType<SingerEntity>> registerSinger(String name) {
        // 1.20.1's EntityType.Builder has neither nameTagOffset nor eyeHeight:
        // the label anchor is fixed up in SingerRenderer instead, and the eye
        // height falls back to the vanilla 0.85 * height.
        return ENTITIES.register(name, () -> EntityType.Builder
                .of(new SingerFactory(), MobCategory.MISC)
                .sized(0.85F, 1.9F)
                .clientTrackingRange(10)
                .updateInterval(2)
                .build(id(name).toString()));
    }

    /**
     * Creates the singleton entity instance for either variant.
     *
     * <p>This is a declared class rather than {@code SingerEntity::new} on
     * purpose. A method reference compiles to an {@code invokedynamic} whose
     * call-site name is the single abstract method of
     * {@code EntityType$EntityFactory}. Reobfuscation rewrites declared member
     * names by walking the owner's superclass and interface chain, and an
     * {@code invokedynamic} carries no owner, so the synthetic body kept the
     * official name {@code create} while the SRG runtime asked for
     * {@code m_20721_}. The result was an {@code AbstractMethodError} the first
     * time anything summoned a singer - the command reported nothing, the
     * entity never appeared, and the integrated server died on the spot.
     *
     * <p>A named class has a real override whose owner is this file, so the
     * name resolves through {@link EntityType.EntityFactory} and is remapped
     * like any other override - the same fix already used for the renderer
     * providers in {@code DuetClientSetup}.
     */
    private static final class SingerFactory
            implements EntityType.EntityFactory<SingerEntity> {

        @Override
        public SingerEntity create(EntityType<SingerEntity> type, Level level) {
            return new SingerEntity(type, level);
        }
    }

    private static RegistryObject<Item> registerEgg(
            String name, RegistryObject<EntityType<SingerEntity>> type,
            int backgroundColor, int highlightColor) {
        // The item registry is populated before the entity-type registry in
        // 1.20.1, so calling type.get() while building the egg throws
        // "Registry Object not present: mmdduet:si" and aborts startup.
        // ForgeSpawnEggItem exists exactly for this: it only stores the
        // supplier and resolves the type on first use.
        return ITEMS.register(name, () -> new ForgeSpawnEggItem(
                type, backgroundColor, highlightColor, new Item.Properties()));
    }
}

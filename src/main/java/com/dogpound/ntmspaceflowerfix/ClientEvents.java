package com.dogpound.ntmspaceflowerfix;

import com.google.common.collect.ImmutableMap;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.client.renderer.block.model.ModelRotation;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.model.IModel;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.ModelLoaderRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.lang.reflect.Field;

/**
 * Client-only event handler. Registered for the FORGE event bus on the CLIENT side.
 *
 * <p>Replicates exactly what {@code com.hbm.blocks.generic.BlockMeta#registerSprite} and
 * {@code BlockMeta#bakeModel} do for the CROSS form, but only for the strawberry/mint
 * metas of {@code hbm:plant_flower}, and at LOWEST priority so it runs after HBM and the
 * NTM-Space addon have already (mis-)wired things.
 */
@Mod.EventBusSubscriber(modid = NTMSpaceFlowerFix.MODID, value = Side.CLIENT)
public final class ClientEvents {

    private ClientEvents() {}

    /** HBM namespace / mod id. */
    private static final String HBM = "hbm";

    /** The two flower texture base names NTM-Space registers as {@code BlockBakeFrame.cross(...)}. */
    private static final String[] FLOWER_TEXTURES = { "plant_flower_strawberry", "plant_flower_mint" };

    /** Enum constant names NTM-Space appends to {@code PlantEnums.EnumFlowerPlantType}. */
    private static final String[] FLOWER_ENUM_NAMES = { "STRAWBERRY", "MINT" };

    /** {@code BlockBakeFrame.ROOT_PATH} — the prefix HBM stitches under (e.g. "blocks/"). */
    private static final String ROOT_PATH = "blocks/";

    /** Vanilla cross base model NTM-Space's CROSS frame uses. */
    private static final ResourceLocation CROSS_MODEL = new ResourceLocation("minecraft", "block/cross");

    /**
     * THE ROOT-CAUSE FIX. Fires at the very start of model loading — before HBM's
     * {@code BlockMeta} state mapper ({@code BlockMeta$2}) resolves each flower blockstate to a
     * {@code meta=N} model key. That mapper does {@code if (meta < META_COUNT) "meta="+meta else
     * "meta=0"}, reading {@code META_COUNT} LIVE. NTM-Space leaves META_COUNT corrupted
     * (a negative short, e.g. -19309), so strawberry(6)/mint(7) fail the {@code < META_COUNT}
     * test and get sent to a never-baked fallback key -> purple missing-model cube.
     *
     * <p>Clamping META_COUNT here (the earliest model-load hook) makes the mapper resolve
     * strawberry->meta=6 / mint->meta=7 correctly. HBM's bakeModel loop is bounds-guarded
     * against blockFrames.length, so a now-correct META_COUNT can't make it crash; our
     * {@link #onModelBake} backstop guarantees those two keys hold a baked cross model either way.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onModelRegistry(ModelRegistryEvent event) {
        NTMSpaceFlowerFix.clampFlowerMetaCount();
    }

    /**
     * Stitch the strawberry + mint sprites into the block atlas. Idempotent: TextureMap
     * de-dupes, so registering a sprite HBM already registered is harmless.
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onTextureStitch(TextureStitchEvent.Pre event) {
        // Belt-and-suspenders: keep META_COUNT correct through the stitch phase too.
        NTMSpaceFlowerFix.clampFlowerMetaCount();
        try {
            if (event.getMap() == null) return;
            // Only touch the block atlas (not the item-only atlas), matching where HBM stitches.
            // The block atlas location is minecraft:textures/atlas/blocks.png.
            TextureMap map = event.getMap();
            for (String tex : FLOWER_TEXTURES) {
                map.registerSprite(new ResourceLocation(HBM, ROOT_PATH + tex));
            }
            NTMSpaceFlowerFix.LOGGER.info("Registered NTM-Space flower sprites (strawberry, mint).");
        } catch (Throwable t) {
            // Never break the texture stitch — log and move on.
            NTMSpaceFlowerFix.LOGGER.error("Failed to register NTM-Space flower sprites; flowers may stay purple.", t);
        }
    }

    /**
     * Re-bake the strawberry/mint metas of {@code hbm:plant_flower} after everyone else.
     * Mirrors {@code BlockMeta#bakeModel}'s CROSS branch:
     *   base = minecraft:block/cross, retexture {cross, particle} -> hbm:blocks/plant_flower_*,
     *   bake X0_Y0 / BLOCK format, putObject under hbm:plant_flower#meta=N.
     * The item model reuses the same meta=N location, so this also fixes the held item.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onModelBake(ModelBakeEvent event) {
        NTMSpaceFlowerFix.clampFlowerMetaCount();
        try {
            Block plantFlower = findPlantFlower();
            if (plantFlower == null) {
                NTMSpaceFlowerFix.LOGGER.warn("hbm:plant_flower block not found; skipping flower model fix.");
                return;
            }
            ResourceLocation registryName = plantFlower.getRegistryName();
            if (registryName == null) {
                NTMSpaceFlowerFix.LOGGER.warn("hbm:plant_flower has no registry name; skipping flower model fix.");
                return;
            }

            int metaCount = readMetaCount(plantFlower);

            IModel crossBase;
            try {
                crossBase = ModelLoaderRegistry.getModel(CROSS_MODEL);
            } catch (Throwable t) {
                NTMSpaceFlowerFix.LOGGER.error("Could not load base model " + CROSS_MODEL + "; skipping flower model fix.", t);
                return;
            }

            int fixed = 0;
            for (int i = 0; i < FLOWER_ENUM_NAMES.length; i++) {
                String enumName = FLOWER_ENUM_NAMES[i];
                String texture = FLOWER_TEXTURES[i];

                int meta = resolveMetaOrdinal(plantFlower, enumName);
                if (meta < 0) {
                    NTMSpaceFlowerFix.LOGGER.warn("Could not resolve meta ordinal for flower " + enumName
                            + "; it may not be installed. Skipping.");
                    continue;
                }
                if (metaCount >= 0 && meta >= metaCount) {
                    NTMSpaceFlowerFix.LOGGER.warn("Resolved meta " + meta + " for " + enumName
                            + " is >= META_COUNT " + metaCount + "; baking anyway as a safety net.");
                }

                try {
                    String sprite = HBM + ":" + ROOT_PATH + texture; // hbm:blocks/plant_flower_strawberry
                    ImmutableMap<String, String> textures = ImmutableMap.of(
                            "cross", sprite,
                            "particle", sprite
                    );
                    IModel retextured = crossBase.retexture(textures);
                    IBakedModel baked = retextured.bake(
                            ModelRotation.X0_Y0,
                            DefaultVertexFormats.BLOCK,
                            ModelLoader.defaultTextureGetter()
                    );

                    ModelResourceLocation loc = new ModelResourceLocation(registryName, "meta=" + meta);
                    event.getModelRegistry().putObject(loc, baked);
                    fixed++;
                    NTMSpaceFlowerFix.LOGGER.info("Re-baked flower model " + loc + " -> " + sprite);
                } catch (Throwable t) {
                    NTMSpaceFlowerFix.LOGGER.error("Failed to bake flower model for " + enumName
                            + " (meta " + meta + "); it may stay purple.", t);
                }
            }

            NTMSpaceFlowerFix.LOGGER.info("NTM-Space Flower Fix re-baked " + fixed + "/" + FLOWER_ENUM_NAMES.length + " flower model(s).");
        } catch (Throwable t) {
            // Absolute backstop — must never propagate out of ModelBakeEvent.
            NTMSpaceFlowerFix.LOGGER.error("NTM-Space Flower Fix ModelBakeEvent handler errored; doing nothing.", t);
        }
    }

    // ------------------------------------------------------------------
    // helpers (all reflective + defensive; HBM classes referenced by name only)
    // ------------------------------------------------------------------

    /** Look up {@code ModBlocks.plant_flower} reflectively (no hard link to HBM at class-init time). */
    private static Block findPlantFlower() {
        try {
            Class<?> modBlocks = Class.forName("com.hbm.blocks.ModBlocks");
            Field f = modBlocks.getField("plant_flower"); // public static final
            Object value = f.get(null);
            if (value instanceof Block) return (Block) value;
        } catch (Throwable t) {
            NTMSpaceFlowerFix.LOGGER.debug("Reflective ModBlocks.plant_flower lookup failed.", t);
        }
        return null;
    }

    /**
     * Read {@code BlockMeta.META_COUNT} (protected short) from the live plant_flower block.
     * Returns -1 if unavailable (we then skip the bounds sanity check).
     */
    private static int readMetaCount(Block plantFlower) {
        try {
            Field f = findField(plantFlower.getClass(), "META_COUNT");
            if (f != null) {
                f.setAccessible(true);
                return ((Number) f.get(plantFlower)).intValue();
            }
        } catch (Throwable t) {
            NTMSpaceFlowerFix.LOGGER.debug("Could not read META_COUNT.", t);
        }
        return -1;
    }

    /**
     * Resolve the runtime meta index for a flower enum constant.
     *
     * <p>Primary path: read the public static {@code EnumAddonFlowerPlantTypes.STRAWBERRY/MINT}
     * field and call {@code .ordinal()} — this is the exact value HBM uses for the
     * {@code meta=N} model key. This survives any enum-ordering changes the addon makes.
     *
     * <p>Fallback path: scan {@code PlantEnums.EnumFlowerPlantType.values()} for a constant
     * whose {@code name()} matches, in case the addon's static fields aren't populated yet.
     */
    private static int resolveMetaOrdinal(Block plantFlower, String enumName) {
        // 1) NTM-Space's own public static handle.
        try {
            Class<?> addon = Class.forName("com.hbmspace.enums.EnumAddonFlowerPlantTypes");
            Field f = addon.getField(enumName); // public static
            Object value = f.get(null);
            if (value instanceof Enum) {
                return ((Enum<?>) value).ordinal();
            }
        } catch (Throwable t) {
            NTMSpaceFlowerFix.LOGGER.debug("Addon enum handle lookup failed for " + enumName + "; trying values() scan.", t);
        }

        // 2) Scan the live flower enum by name.
        try {
            Class<?> enumClass = Class.forName("com.hbm.blocks.PlantEnums$EnumFlowerPlantType");
            Object[] values = (Object[]) enumClass.getMethod("values").invoke(null);
            for (Object v : values) {
                if (v instanceof Enum && enumName.equals(((Enum<?>) v).name())) {
                    return ((Enum<?>) v).ordinal();
                }
            }
        } catch (Throwable t) {
            NTMSpaceFlowerFix.LOGGER.debug("PlantEnums.EnumFlowerPlantType.values() scan failed for " + enumName + ".", t);
        }

        return -1;
    }

    /** Walk the class hierarchy looking for a declared field (BlockMeta is a superclass). */
    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}

package com.dogpound.ntmspaceflowerfix;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;

/**
 * NTM-Space Flower Fix.
 *
 * <p>NTM-Space appends STRAWBERRY + MINT to HBM's {@code PlantEnums.EnumFlowerPlantType} and
 * tries to update {@code ModBlocks.plant_flower}'s {@code BlockMeta.META_COUNT} + blockFrames
 * via reflection. On this Cleanroom/Java-25 setup that reflection misfires badly:
 * <ul>
 *   <li>It leaves the strawberry/mint metas with no baked model (purple) — fixed client-side
 *       in {@link ClientEvents} (re-stitch sprites + re-bake meta 6/7).</li>
 *   <li>It blows {@code META_COUNT} up to a garbage value (thousands), so {@code getSubBlocks}
 *       spits out THOUSANDS of duplicate flower items into creative/JEI, and hovering one builds
 *       an impossible blockstate ({@code meta=17551} on a 0-15 property) → hard crash.</li>
 * </ul>
 *
 * <p>This fix, at postInit (after hbm/hbmspace have run), clamps {@code META_COUNT} back to the
 * real flower count (≤16, the block's metadata range). That deletes the duplicate spam AND the
 * tooltip crash in one shot. All reflective + wrapped in try/catch — never breaks boot.
 */
@Mod(
        modid = NTMSpaceFlowerFix.MODID,
        name = NTMSpaceFlowerFix.NAME,
        version = NTMSpaceFlowerFix.VERSION,
        dependencies = "after:hbm;after:hbmspace;after:hbm_m",
        clientSideOnly = true,
        acceptableRemoteVersions = "*"
)
public class NTMSpaceFlowerFix {

    public static final String MODID = "ntmspaceflowerfix";
    public static final String NAME = "NTM-Space Flower Fix";
    public static final String VERSION = "1.1.0";

    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent e) {
        clampFlowerMetaCount();
    }

    /**
     * Force {@code plant_flower.META_COUNT} to the real number of flower variants. HBM's
     * BlockMeta uses META_COUNT as the loop bound for getSubBlocks() (creative/JEI) and for the
     * state mapper; a corrupted huge value is what creates the thousands of bogus duplicate
     * items and the meta-out-of-range crash on hover.
     */
    public static void clampFlowerMetaCount() {
        try {
            Class<?> modBlocks = Class.forName("com.hbm.blocks.ModBlocks");
            Object plantFlower = modBlocks.getField("plant_flower").get(null);
            if (plantFlower == null) {
                LOGGER.warn("ModBlocks.plant_flower was null; skipping META_COUNT clamp.");
                return;
            }

            // Real variant count = the enum's value count, but a 4-bit metadata block can only
            // legally hold 0-15, so anything outside [1,16] means it's corrupted -> use 8
            // (foxglove, hemp, mustard_willow_0/1, nightshade, tobacco, strawberry, mint).
            int real = 8;
            try {
                Class<?> enumCls = Class.forName("com.hbm.blocks.PlantEnums$EnumFlowerPlantType");
                Object[] vals = (Object[]) enumCls.getMethod("values").invoke(null);
                if (vals != null && vals.length >= 1 && vals.length <= 16) {
                    real = vals.length;
                }
            } catch (Throwable t) {
                LOGGER.debug("Could not read EnumFlowerPlantType.values(); defaulting count to 8.", t);
            }

            Field metaCount = findField(plantFlower.getClass(), "META_COUNT");
            if (metaCount == null) {
                LOGGER.warn("Could not find BlockMeta.META_COUNT field; skipping clamp.");
                return;
            }
            metaCount.setAccessible(true);
            int current = ((Number) metaCount.get(plantFlower)).intValue();
            if (current != real) {
                metaCount.set(plantFlower, (short) real);
                LOGGER.info("Clamped plant_flower META_COUNT " + current + " -> " + real
                        + " (removes duplicate-flower spam in NEI/HEI + the meta-out-of-range crash).");
            } else {
                LOGGER.info("plant_flower META_COUNT already " + real + "; nothing to clamp.");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to clamp plant_flower META_COUNT; duplicate flowers / crash may persist.", t);
        }
    }

    /** Walk the class hierarchy for a declared field (META_COUNT lives on the BlockMeta superclass). */
    private static Field findField(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ex) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}

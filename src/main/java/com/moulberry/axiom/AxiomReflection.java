package com.moulberry.axiom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public class AxiomReflection {

    private static Method updateBlockEntityTicker = null;
    private static Field blockEntitiesField = null;

    public static void init() {
        ReflectionRemapper reflectionRemapper = ReflectionRemapper.forReobfMappingsInPaperJar();
        String methodName = reflectionRemapper.remapMethodName(LevelChunk.class, "updateBlockEntityTicker", BlockEntity.class);
        String fieldName = reflectionRemapper.remapFieldName(LevelChunk.class, "blockEntities");

        try {
            updateBlockEntityTicker = LevelChunk.class.getDeclaredMethod(methodName, BlockEntity.class);
            updateBlockEntityTicker.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        try {
            blockEntitiesField = LevelChunk.class.getDeclaredField(fieldName);
            blockEntitiesField.setAccessible(true);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static void updateBlockEntityTicker(LevelChunk levelChunk, BlockEntity blockEntity) {
        try {
            updateBlockEntityTicker.invoke(levelChunk, blockEntity);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<BlockPos, BlockEntity> getBlockEntities(LevelChunk levelChunk) {
        try {
            return (Map<BlockPos, BlockEntity>) blockEntitiesField.get(levelChunk);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}

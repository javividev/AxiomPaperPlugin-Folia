package com.moulberry.axiom.operations;

import com.moulberry.axiom.AxiomConstants;
import com.moulberry.axiom.VersionHelper;
import com.moulberry.axiom.buffer.CompressedBlockEntity;
import com.moulberry.axiom.packet.impl.RequestChunkDataPacketListener;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongComparators;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftChunk;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestChunksOperation implements PendingOperation {

    private static final int MAX_CHUNK_FUTURES = 256;
    private boolean finished = false;

    private final ServerPlayer serverPlayer;
    private final long id;

    private final LongArrayList getChunkFutures;
    // Each CompletableFuture here is completed AFTER the chunk data has been extracted
    // on the correct region thread, so joining it is safe from the global scheduler.
    private List<CompletableFuture<Void>> chunkFutures = new ArrayList<>();
    private final Long2ObjectMap<LongList> sendBlockEntityForPendingChunks;
    private final Long2ObjectMap<IntList> sendSectionsForPendingChunks;
    private final boolean sendBlockEntitiesInChunks;

    private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sendingSections;
    private final Long2ObjectOpenHashMap<CompressedBlockEntity> sendingBlockEntities;
    private final ByteArrayOutputStream baos;

    // Counts region-thread tasks that are still running so tick() knows when all work is done.
    private final AtomicInteger pendingRegionTasks = new AtomicInteger(0);

    public RequestChunksOperation(ServerPlayer serverPlayer, long id, LongSet chunkFutures, Long2ObjectMap<LongList> sendBlockEntityForPendingChunks, Long2ObjectMap<IntList> sendSectionsForPendingChunks, boolean sendBlockEntitiesInChunks, Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sendingSections, Long2ObjectOpenHashMap<CompressedBlockEntity> sendingBlockEntities, ByteArrayOutputStream baos) {
        this.serverPlayer = serverPlayer;
        this.id = id;
        this.sendBlockEntityForPendingChunks = sendBlockEntityForPendingChunks;
        this.sendSectionsForPendingChunks = sendSectionsForPendingChunks;
        this.sendBlockEntitiesInChunks = sendBlockEntitiesInChunks;
        this.sendingSections = sendingSections;
        this.sendingBlockEntities = sendingBlockEntities;
        this.baos = baos;

        LongArrayList getChunkFutures = new LongArrayList(chunkFutures);
        getChunkFutures.unstableSort(LongComparators.NATURAL_COMPARATOR);
        this.getChunkFutures = getChunkFutures;
    }

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public ServerPlayer executor() {
        return this.serverPlayer;
    }

    @Override
    public void tick(ServerLevel level) {
        if (this.finished) {
            return;
        }

        if (this.serverPlayer.hasDisconnected()) {
            this.finished = true;
            return;
        }

        // Schedule new chunk loads, up to MAX_CHUNK_FUTURES in flight at once.
        if (!this.getChunkFutures.isEmpty()) {
            int count = this.chunkFutures.size() + this.pendingRegionTasks.get();
            LongIterator newFutureIterator = this.getChunkFutures.longIterator();
            while (count++ < MAX_CHUNK_FUTURES && newFutureIterator.hasNext()) {
                long chunkPos = newFutureIterator.nextLong();
                newFutureIterator.remove();

                int x = ChunkPos.getX(chunkPos);
                int z = ChunkPos.getZ(chunkPos);

                // This CompletableFuture is completed only after we have extracted all chunk
                // data on the correct region thread — so it is safe to check isDone() from
                // the global scheduler without touching the chunk again.
                CompletableFuture<Void> future = new CompletableFuture<>();
                this.pendingRegionTasks.incrementAndGet();

                Bukkit.getRegionScheduler().run(com.moulberry.axiom.AxiomPaper.PLUGIN, level.getWorld(), x, z, regionTask -> {
                    level.getWorld().getChunkAtAsync(x, z).thenAccept(bukkitChunk -> {
                        // We are now on the region thread that owns this chunk — safe to call getHandle().
                        Bukkit.getRegionScheduler().run(com.moulberry.axiom.AxiomPaper.PLUGIN, level.getWorld(), x, z, innerTask -> {
                            try {
                                LevelChunk chunk = (LevelChunk) ((CraftChunk) bukkitChunk).getHandle(ChunkStatus.FULL);
                                processChunkOnRegionThread(chunk);
                            } finally {
                                this.pendingRegionTasks.decrementAndGet();
                                future.complete(null);
                            }
                        });
                    }).exceptionally(err -> {
                        this.pendingRegionTasks.decrementAndGet();
                        future.completeExceptionally(err);
                        return null;
                    });
                });

                this.chunkFutures.add(future);
            }
        }

        // Remove futures that are already done.
        this.chunkFutures.removeIf(CompletableFuture::isDone);

        // Wait until all scheduled region tasks AND all futures are resolved.
        if (!this.getChunkFutures.isEmpty() || !this.chunkFutures.isEmpty() || this.pendingRegionTasks.get() > 0) {
            return;
        }

        RequestChunkDataPacketListener.sendResponse(this.serverPlayer, this.id, this.sendingBlockEntities, this.sendingSections);
        this.finished = true;
    }

    /**
     * Called on the region thread that owns {@code chunk}.
     * Reads block entities and section data into the shared sending maps.
     * The sending maps are Long2ObjectOpenHashMap (not thread-safe), but because
     * we only write from region threads and only read from the global scheduler
     * after all region tasks have finished (pendingRegionTasks == 0 and futures done),
     * there is no concurrent access.
     */
    private void processChunkOnRegionThread(LevelChunk chunk) {
        long chunkPosLong = ChunkPos.asLong(chunk.locX, chunk.locZ);

        LongList blockEntitiesInChunk = this.sendBlockEntityForPendingChunks.get(chunkPosLong);
        if (blockEntitiesInChunk != null) {
            // Use a local mutable pos to avoid sharing the field across threads.
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            LongIterator iterator = blockEntitiesInChunk.longIterator();
            while (iterator.hasNext()) {
                long blockEntityPos = iterator.nextLong();
                pos.set(blockEntityPos);

                BlockEntity blockEntity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                if (blockEntity != null) {
                    CompoundTag tag = blockEntity.saveWithoutMetadata(this.serverPlayer.registryAccess());
                    synchronized (this.sendingBlockEntities) {
                        this.sendingBlockEntities.put(blockEntityPos, CompressedBlockEntity.compress(tag, baos));
                    }
                }
            }
        }

        IntList sendSectionsInChunk = this.sendSectionsForPendingChunks.get(chunkPosLong);
        if (sendSectionsInChunk != null) {
            boolean hasNonAirSectionInChunk = false;

            IntIterator sectionIterator = sendSectionsInChunk.intIterator();
            while (sectionIterator.hasNext()) {
                int sy = sectionIterator.nextInt();

                int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
                if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) continue;
                LevelChunkSection section = chunk.getSection(sectionIndex);

                long key = BlockPos.asLong(chunk.locX, sy, chunk.locZ);
                if (section.hasOnlyAir()) {
                    synchronized (this.sendingSections) {
                        this.sendingSections.put(key, null);
                    }
                } else {
                    PalettedContainer<BlockState> container = section.getStates();
                    synchronized (this.sendingSections) {
                        this.sendingSections.put(key, container);
                    }
                    hasNonAirSectionInChunk = true;
                }
            }

            if (this.sendBlockEntitiesInChunks && hasNonAirSectionInChunk) {
                Iterator<Map.Entry<BlockPos, BlockEntity>> iterator = chunk.blockEntities.entrySet().iterator();

                while (iterator.hasNext()) {
                    Map.Entry<BlockPos, BlockEntity> entry = iterator.next();

                    BlockPos blockPos = entry.getKey();
                    int sectionY = blockPos.getY() >> 4;
                    if (!sendSectionsInChunk.contains(sectionY)) {
                        continue;
                    }

                    CompoundTag tag = entry.getValue().saveWithoutMetadata(this.serverPlayer.registryAccess());
                    synchronized (this.sendingBlockEntities) {
                        this.sendingBlockEntities.put(blockPos.asLong(), CompressedBlockEntity.compress(tag, baos));
                    }
                }
            }
        }
    }


}

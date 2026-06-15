package com.moulberry.axiom.packet.impl;

import com.moulberry.axiom.AxiomPaper;
import com.moulberry.axiom.WorldExtension;
import com.moulberry.axiom.buffer.BiomeBuffer;
import com.moulberry.axiom.buffer.BlockBuffer;
import com.moulberry.axiom.buffer.CompressedBlockEntity;
import com.moulberry.axiom.integration.Integration;
import com.moulberry.axiom.integration.SectionPermissionChecker;
import com.moulberry.axiom.integration.coreprotect.CoreProtectIntegration;
import com.moulberry.axiom.operations.SetBlockBufferOperation;
import com.moulberry.axiom.packet.PacketHandler;
import com.moulberry.axiom.restrictions.AxiomPermission;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.storage.TagValueInput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import xyz.jpenilla.reflectionremapper.ReflectionRemapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class SetBlockBufferPacketListener implements PacketHandler {

    private final AxiomPaper plugin;

    public SetBlockBufferPacketListener(AxiomPaper plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean handleAsync() {
        return true;
    }

    public void onReceive(Player player, RegistryFriendlyByteBuf friendlyByteBuf) {
        ServerPlayer serverPlayer = ((CraftPlayer)player).getHandle();
        MinecraftServer server = serverPlayer.level().getServer();
        if (server == null) return;

        ResourceKey<Level> worldKey = friendlyByteBuf.readResourceKey(Registries.DIMENSION);
        friendlyByteBuf.readUUID(); // Discard, we don't need to associate buffers

        byte type = friendlyByteBuf.readByte();
        if (type == 0) {
            BlockBuffer buffer = BlockBuffer.load(friendlyByteBuf, this.plugin.getBlockRegistry(serverPlayer.getUUID()), serverPlayer.getBukkitEntity());
            int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();

            applyBlockBuffer(serverPlayer, server, buffer, worldKey, clientAvailableDispatchSends);
        } else if (type == 1) {
            BiomeBuffer buffer = BiomeBuffer.load(friendlyByteBuf);
            int clientAvailableDispatchSends = friendlyByteBuf.readVarInt();

            applyBiomeBuffer(serverPlayer, server, buffer, worldKey, clientAvailableDispatchSends);
        } else {
            throw new RuntimeException("Unknown buffer type: " + type);
        }
    }

    private void applyBlockBuffer(ServerPlayer player, MinecraftServer server, BlockBuffer buffer, ResourceKey<Level> worldKey, int clientAvailableDispatchSends) {
        Runnable task = () -> {
            try {
                if (this.plugin.logLargeBlockBufferChanges()) {
                    this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + buffer.getSectionCount() + " chunk sections (blocks)");
                    if (buffer.getTotalBlockEntities() > 0) {
                        this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + buffer.getTotalBlockEntities() + " block entities, compressed bytes = " +
                            buffer.getTotalBlockEntityBytes());
                    }
                }

                if (!this.plugin.consumeDispatchSends(player.getBukkitEntity(), buffer.getSectionCount(), clientAvailableDispatchSends)) {
                    return;
                }

                if (!this.plugin.canUseAxiom(player.getBukkitEntity(), AxiomPermission.BUILD_SECTION)) {
                    return;
                }

                ServerLevel world = player.level();
                if (!world.dimension().equals(worldKey) || !this.plugin.canModifyWorld(player.getBukkitEntity(), world.getWorld())) {
                    return;
                }

                boolean allowNbt = this.plugin.hasPermission(player.getBukkitEntity(), AxiomPermission.BUILD_NBT);
                this.plugin.addPendingOperation(world, new SetBlockBufferOperation(player, buffer, allowNbt));
            } catch (Throwable t) {
                String errorMsg = t.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = t.getClass().getSimpleName();
                }
                plugin.getLogger().severe("Error processing set_buffer: " + errorMsg);
                t.printStackTrace();
                player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occurred while processing block change: " + errorMsg));
            }
        };
        org.bukkit.Bukkit.getRegionScheduler().run(this.plugin, player.getBukkitEntity().getLocation(), t -> task.run());
    }

    private record BiomeEntry(int x, int y, int z, ResourceKey<Biome> biome) {}

    private void applyBiomeBuffer(ServerPlayer player, MinecraftServer server, BiomeBuffer biomeBuffer, ResourceKey<Level> worldKey, int clientAvailableDispatchSends) {
        Runnable task = () -> {
            try {
                if (this.plugin.logLargeBlockBufferChanges()) {
                    this.plugin.getLogger().info("Player " + player.getUUID() + " modified " + biomeBuffer.getSectionCount() + " chunk sections (biomes)");
                }

                if (!this.plugin.consumeDispatchSends(player.getBukkitEntity(), biomeBuffer.getSectionCount(), clientAvailableDispatchSends)) {
                    return;
                }

                if (!this.plugin.canUseAxiom(player.getBukkitEntity(), AxiomPermission.BUILD_SECTION)) {
                    return;
                }

                ServerLevel world = player.level();
                if (!world.dimension().equals(worldKey) || !this.plugin.canModifyWorld(player.getBukkitEntity(), world.getWorld())) {
                    return;
                }

                int minSection = world.getMinSectionY();
                int maxSection = world.getMaxSectionY();

                Optional<Registry<Biome>> registryOptional = world.registryAccess().lookup(Registries.BIOME);
                if (registryOptional.isEmpty()) return;

                Registry<Biome> registry = registryOptional.get();

                Map<Long, List<BiomeEntry>> biomesByChunk = new HashMap<>();
                biomeBuffer.forEachEntry((x, y, z, biome) -> {
                    long chunkKey = ChunkPos.asLong(x >> 2, z >> 2);
                    biomesByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(new BiomeEntry(x, y, z, biome));
                });

                biomesByChunk.forEach((chunkKey, chunkBiomes) -> {
                    int cx = ChunkPos.getX(chunkKey);
                    int cz = ChunkPos.getZ(chunkKey);

                    Bukkit.getRegionScheduler().run(this.plugin, player.getBukkitEntity().getWorld(), cx, cz, t -> {
                        try {
                            Set<LevelChunk> changedChunks = new HashSet<>();

                            for (BiomeEntry entry : chunkBiomes) {
                                int cy = entry.y >> 2;
                                if (cy < minSection || cy > maxSection) {
                                    continue;
                                }

                                var holder = registry.get(entry.biome);
                                if (holder.isPresent()) {
                                    LevelChunk chunk = (LevelChunk) world.getChunk(cx, cz, ChunkStatus.FULL, false);
                                    if (chunk == null) continue;

                                    var section = chunk.getSection(cy - minSection);
                                    PalettedContainer<Holder<Biome>> container = (PalettedContainer<Holder<Biome>>) section.getBiomes();

                                    if (!Integration.canPlaceBlock(player.getBukkitEntity(),
                                        new Location(player.getBukkitEntity().getWorld(), (entry.x<<2)+1, (entry.y<<2)+1, (entry.z<<2)+1))) continue;

                                    container.set(entry.x & 3, entry.y & 3, entry.z & 3, holder.get());
                                    changedChunks.add(chunk);
                                }
                            }

                            if (!changedChunks.isEmpty()) {
                                var chunkMap = world.getChunkSource().chunkMap;
                                HashMap<ServerPlayer, List<LevelChunk>> clientUpdateMap = new HashMap<>();
                                for (LevelChunk chunk : changedChunks) {
                                    chunk.markUnsaved();
                                    ChunkPos chunkPos = chunk.getPos();
                                    for (ServerPlayer serverPlayer2 : chunkMap.getPlayers(chunkPos, false)) {
                                        clientUpdateMap.computeIfAbsent(serverPlayer2, serverPlayer -> new ArrayList<>()).add(chunk);
                                    }
                                }
                                clientUpdateMap.forEach((serverPlayer, list) -> serverPlayer.connection.send(ClientboundChunksBiomesPacket.forChunks(list)));
                            }
                        } catch (Throwable err) {
                            String errorMsg = err.getMessage();
                            if (errorMsg == null || errorMsg.isEmpty()) {
                                errorMsg = err.getClass().getSimpleName();
                            }
                            plugin.getLogger().severe("Error processing biome_buffer on region thread: " + errorMsg);
                            err.printStackTrace();
                            player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occurred while processing biome change: " + errorMsg));
                        }
                    });
                });
            } catch (Throwable t) {
                String errorMsg = t.getMessage();
                if (errorMsg == null || errorMsg.isEmpty()) {
                    errorMsg = t.getClass().getSimpleName();
                }
                plugin.getLogger().severe("Error processing biome_buffer: " + errorMsg);
                t.printStackTrace();
                player.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occurred while processing biome change: " + errorMsg));
            }
        };
        org.bukkit.Bukkit.getRegionScheduler().run(this.plugin, player.getBukkitEntity().getLocation(), t -> task.run());
    }

}

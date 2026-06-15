package com.moulberry.axiom.operations;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class OperationQueue {

    private final Lock queueLock = new ReentrantLock();
    private final Map<ServerLevel, List<PendingOperation>> newPendingOperations = new HashMap<>();
    private final Lock executionLock = new ReentrantLock();
    private final Map<ServerLevel, List<PendingOperation>> pendingOperations = new HashMap<>();

    /**
     * Called once per global-scheduler tick from AxiomPaper.tick().
     * In Folia there is no single "main thread", so we do NOT assert isSameThread().
     * The executionLock prevents re-entrant calls (e.g. if tick() is somehow called
     * from two global-scheduler slots simultaneously, which Folia does not do, but
     * it's cheap insurance).
     */
    public void tick() {
        this.executionLock.lock();
        try {
            // Drain newly-added operations into the active map.
            this.queueLock.lock();
            try {
                for (Map.Entry<ServerLevel, List<PendingOperation>> entry : this.newPendingOperations.entrySet()) {
                    List<PendingOperation> currentOperations = this.pendingOperations.get(entry.getKey());
                    if (currentOperations != null) {
                        currentOperations.addAll(entry.getValue());
                    } else {
                        this.pendingOperations.put(entry.getKey(), entry.getValue());
                    }
                }
                this.newPendingOperations.clear();
            } finally {
                this.queueLock.unlock();
            }

            var worldIterator = this.pendingOperations.entrySet().iterator();
            while (worldIterator.hasNext()) {
                Map.Entry<ServerLevel, List<PendingOperation>> perWorldOperations = worldIterator.next();

                var perWorldIterator = perWorldOperations.getValue().iterator();
                while (perWorldIterator.hasNext()) {
                    PendingOperation operation = perWorldIterator.next();

                    try {
                        operation.tick(perWorldOperations.getKey());
                        if (operation.isFinished()) {
                            perWorldIterator.remove();
                        } else {
                            break;
                        }
                    } catch (Throwable t) {
                        ServerPlayer executor = operation.executor();
                        executor.getBukkitEntity().kick(net.kyori.adventure.text.Component.text("An error occurred while processing operation: " + t.getMessage()));
                        perWorldIterator.remove();
                    }
                }

                if (perWorldOperations.getValue().isEmpty()) {
                    worldIterator.remove();
                }
            }
        } finally {
            this.executionLock.unlock();
        }

    }

    public void add(ServerLevel level, PendingOperation operation) {
        this.queueLock.lock();
        try {
            List<PendingOperation> operations = this.newPendingOperations.computeIfAbsent(level, k -> new ArrayList<>());

            // In Folia there is no concept of "the main thread" that we can fast-path on,
            // so we always queue the operation normally.
            operations.add(operation);
        } finally {
            this.queueLock.unlock();
        }
    }

}

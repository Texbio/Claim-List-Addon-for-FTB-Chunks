package com.t.claimlistftb.client;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Groups claim changes by adjacency - chunks must be touching (orthogonally or diagonally)
 */
public class ClaimChangeGrouper {

    /**
     * Groups claim changes that are adjacent (touching) to each other.
     * Chunks are considered adjacent if they differ by at most 1 in both X and Z.
     */
    public static List<ChangeGroup> groupChanges(List<ClaimChangeReader.ClaimChange> changes) {
        // Group by dimension first
        Map<ResourceKey<Level>, List<ClaimChangeReader.ClaimChange>> byDimension = new HashMap<>();
        for (ClaimChangeReader.ClaimChange change : changes) {
            byDimension.computeIfAbsent(change.dimension(), k -> new ArrayList<>()).add(change);
        }

        List<ChangeGroup> allGroups = new ArrayList<>();

        // For each dimension, group changes that are adjacent
        for (Map.Entry<ResourceKey<Level>, List<ClaimChangeReader.ClaimChange>> entry : byDimension.entrySet()) {
            List<ChangeGroup> dimensionGroups = groupChangesInDimension(entry.getKey(), entry.getValue());
            allGroups.addAll(dimensionGroups);
        }

        return allGroups;
    }

    /**
     * Groups changes within a single dimension using flood-fill adjacency
     */
    private static List<ChangeGroup> groupChangesInDimension(ResourceKey<Level> dimension,
                                                             List<ClaimChangeReader.ClaimChange> changes) {
        List<ChangeGroup> groups = new ArrayList<>();
        Set<Long> processed = new HashSet<>();
        
        // Build a map for quick lookup by chunk position
        Map<Long, ClaimChangeReader.ClaimChange> changeMap = new HashMap<>();
        for (ClaimChangeReader.ClaimChange change : changes) {
            long key = packChunkPos(change.chunkX(), change.chunkZ());
            changeMap.put(key, change);
        }

        for (ClaimChangeReader.ClaimChange change : changes) {
            long key = packChunkPos(change.chunkX(), change.chunkZ());
            if (processed.contains(key)) continue;

            // Start a new group using flood-fill
            ChangeGroup group = new ChangeGroup(dimension);
            floodFillAdjacent(change.chunkX(), change.chunkZ(), changeMap, processed, group);
            group.calculateCenter();

            groups.add(group);
        }

        return groups;
    }

    /**
     * Flood-fill to collect all adjacent chunks
     */
    private static void floodFillAdjacent(int startX, int startZ,
                                          Map<Long, ClaimChangeReader.ClaimChange> changeMap,
                                          Set<Long> processed,
                                          ChangeGroup group) {
        Queue<long[]> queue = new LinkedList<>();
        queue.add(new long[]{startX, startZ});

        while (!queue.isEmpty()) {
            long[] pos = queue.poll();
            int x = (int) pos[0];
            int z = (int) pos[1];
            long key = packChunkPos(x, z);

            if (processed.contains(key)) continue;
            
            ClaimChangeReader.ClaimChange change = changeMap.get(key);
            if (change == null) continue;

            processed.add(key);
            group.changes.add(change);

            // Check all 8 adjacent positions (orthogonal + diagonal)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    
                    int nx = x + dx;
                    int nz = z + dz;
                    long nkey = packChunkPos(nx, nz);
                    
                    if (!processed.contains(nkey) && changeMap.containsKey(nkey)) {
                        queue.add(new long[]{nx, nz});
                    }
                }
            }
        }
    }

    private static long packChunkPos(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    /**
     * A group of related claim changes
     */
    public static class ChangeGroup {
        public final ResourceKey<Level> dimension;
        public final List<ClaimChangeReader.ClaimChange> changes = new ArrayList<>();
        
        // Bounding box in block coordinates
        private int minBlockX, maxBlockX, minBlockZ, maxBlockZ;
        private int centerBlockX, centerBlockZ;

        public ChangeGroup(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }

        public void calculateCenter() {
            if (changes.isEmpty()) return;

            int minChunkX = Integer.MAX_VALUE;
            int maxChunkX = Integer.MIN_VALUE;
            int minChunkZ = Integer.MAX_VALUE;
            int maxChunkZ = Integer.MIN_VALUE;

            for (ClaimChangeReader.ClaimChange change : changes) {
                minChunkX = Math.min(minChunkX, change.chunkX());
                maxChunkX = Math.max(maxChunkX, change.chunkX());
                minChunkZ = Math.min(minChunkZ, change.chunkZ());
                maxChunkZ = Math.max(maxChunkZ, change.chunkZ());
            }

            // Calculate bounding box in block coordinates
            minBlockX = minChunkX * 16;
            maxBlockX = (maxChunkX + 1) * 16; // Exclusive end
            minBlockZ = minChunkZ * 16;
            maxBlockZ = (maxChunkZ + 1) * 16; // Exclusive end
            
            // True geometric center
            centerBlockX = (minBlockX + maxBlockX) / 2;
            centerBlockZ = (minBlockZ + maxBlockZ) / 2;
        }

        public int getBlockX() {
            return centerBlockX;
        }

        public int getBlockZ() {
            return centerBlockZ;
        }
        
        /**
         * Get all chunk positions in this group
         */
        public List<int[]> getChunkPositions() {
            List<int[]> positions = new ArrayList<>();
            for (ClaimChangeReader.ClaimChange change : changes) {
                positions.add(new int[]{change.chunkX(), change.chunkZ()});
            }
            return positions;
        }

        public ClaimChangeReader.ChangeCount getCounts() {
            int added = 0;
            int removed = 0;

            for (ClaimChangeReader.ClaimChange change : changes) {
                if (change.type() == ClaimChangeReader.ChangeType.ADD) {
                    added++;
                } else if (change.type() == ClaimChangeReader.ChangeType.REMOVE) {
                    removed++;
                }
                // Ignore BASELINE entries
            }

            return new ClaimChangeReader.ChangeCount(added, removed);
        }
    }
}

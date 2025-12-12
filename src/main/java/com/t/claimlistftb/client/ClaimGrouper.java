package com.t.claimlistftb.client;

import dev.ftb.mods.ftblibrary.math.XZ;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Groups claims by proximity for display.
 * Uses ±5 chunk radius from group centers.
 */
public class ClaimGrouper {

    private static final int GROUP_RADIUS = 5;

    /**
     * Groups claims for a specific owner that are within GROUP_RADIUS chunks of each other.
     *
     * @param owner The claim owner to group claims for
     * @param includeAllDimensions Whether to include claims from all dimensions
     * @return List of ChunkGroups sorted by size (largest first)
     */
    public static List<ChunkGroup> groupOwnerClaims(ClaimOwner owner, boolean includeAllDimensions) {
        List<PlayerClaimFinder.ClaimInfo> allClaims = PlayerClaimFinder.getClaimsForOwner(owner, includeAllDimensions);

        if (allClaims.isEmpty()) {
            return Collections.emptyList();
        }

        // Group by dimension first
        Map<ResourceKey<Level>, List<PlayerClaimFinder.ClaimInfo>> byDimension = new HashMap<>();
        for (PlayerClaimFinder.ClaimInfo claim : allClaims) {
            byDimension.computeIfAbsent(claim.dimension(), k -> new ArrayList<>()).add(claim);
        }

        List<ChunkGroup> allGroups = new ArrayList<>();

        // For each dimension, group claims that are close together
        for (Map.Entry<ResourceKey<Level>, List<PlayerClaimFinder.ClaimInfo>> entry : byDimension.entrySet()) {
            List<ChunkGroup> dimensionGroups = groupClaimsInDimension(entry.getKey(), entry.getValue());
            allGroups.addAll(dimensionGroups);
        }

        // Sort by size (largest first)
        allGroups.sort((a, b) -> Integer.compare(b.size(), a.size()));

        return allGroups;
    }

    /**
     * Groups claims within a single dimension using flood-fill algorithm.
     */
    private static List<ChunkGroup> groupClaimsInDimension(ResourceKey<Level> dimension,
                                                            List<PlayerClaimFinder.ClaimInfo> claims) {
        List<ChunkGroup> groups = new ArrayList<>();
        Set<PlayerClaimFinder.ClaimInfo> processed = new HashSet<>();

        for (PlayerClaimFinder.ClaimInfo claim : claims) {
            if (processed.contains(claim)) continue;

            // Start a new group
            ChunkGroup group = new ChunkGroup(dimension);
            Set<PlayerClaimFinder.ClaimInfo> groupMembers = new HashSet<>();

            // Find all claims within GROUP_RADIUS of this claim (flood fill)
            collectNearbyClaims(claim, claims, groupMembers, processed);

            // Add all group members to the ChunkGroup
            for (PlayerClaimFinder.ClaimInfo member : groupMembers) {
                group.chunks.add(XZ.of(member.chunkPos().x(), member.chunkPos().z()));
                if (member.isForceLoaded()) {
                    group.forceLoadedCount++;
                }
            }
            group.calculateCenter();

            groups.add(group);
        }

        return groups;
    }

    /**
     * Recursively collects all claims within GROUP_RADIUS of the seed claim.
     */
    private static void collectNearbyClaims(PlayerClaimFinder.ClaimInfo seed,
                                            List<PlayerClaimFinder.ClaimInfo> allClaims,
                                            Set<PlayerClaimFinder.ClaimInfo> group,
                                            Set<PlayerClaimFinder.ClaimInfo> processed) {
        if (processed.contains(seed)) return;

        processed.add(seed);
        group.add(seed);

        // Find all claims within GROUP_RADIUS
        for (PlayerClaimFinder.ClaimInfo other : allClaims) {
            if (processed.contains(other)) continue;
            if (!seed.dimension().equals(other.dimension())) continue;

            int dx = Math.abs(seed.chunkPos().x() - other.chunkPos().x());
            int dz = Math.abs(seed.chunkPos().z() - other.chunkPos().z());

            if (dx <= GROUP_RADIUS && dz <= GROUP_RADIUS) {
                collectNearbyClaims(other, allClaims, group, processed);
            }
        }
    }

    /**
     * A group of related claims.
     * Named ChunkGroup for compatibility with PlayerClaimListScreen.
     */
    public static class ChunkGroup {
        public final ResourceKey<Level> dimension;
        public final List<XZ> chunks = new ArrayList<>();
        public int forceLoadedCount = 0;
        
        // Bounding box in block coordinates
        private int minBlockX, maxBlockX, minBlockZ, maxBlockZ;
        private int centerBlockX, centerBlockZ;

        public ChunkGroup(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }

        /**
         * Calculate the center of this group based on bounding box in block coordinates.
         */
        public void calculateCenter() {
            if (chunks.isEmpty()) return;

            int minChunkX = Integer.MAX_VALUE;
            int maxChunkX = Integer.MIN_VALUE;
            int minChunkZ = Integer.MAX_VALUE;
            int maxChunkZ = Integer.MIN_VALUE;

            for (XZ chunk : chunks) {
                minChunkX = Math.min(minChunkX, chunk.x());
                maxChunkX = Math.max(maxChunkX, chunk.x());
                minChunkZ = Math.min(minChunkZ, chunk.z());
                maxChunkZ = Math.max(maxChunkZ, chunk.z());
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

        /**
         * Get center X in block coordinates.
         */
        public int getBlockX() {
            return centerBlockX;
        }

        /**
         * Get center Z in block coordinates.
         */
        public int getBlockZ() {
            return centerBlockZ;
        }
        
        /**
         * Get center chunk X (for compatibility).
         */
        public int getCenterChunkX() {
            return centerBlockX >> 4;
        }
        
        /**
         * Get center chunk Z (for compatibility).
         */
        public int getCenterChunkZ() {
            return centerBlockZ >> 4;
        }

        /**
         * Returns the number of chunks in this group.
         */
        public int size() {
            return chunks.size();
        }

        /**
         * Alias for size() for compatibility.
         */
        public int getChunkCount() {
            return chunks.size();
        }

        /**
         * Get count of force-loaded chunks.
         */
        public int getForceLoadedCount() {
            return forceLoadedCount;
        }

        /**
         * Get the dimension this group is in.
         */
        public ResourceKey<Level> getDimension() {
            return dimension;
        }

        /**
         * Get dimension (method form for compatibility).
         */
        public ResourceKey<Level> dimension() {
            return dimension;
        }

        /**
         * Get chunks list (method form for compatibility).
         */
        public List<XZ> chunks() {
            return chunks;
        }

        /**
         * Get the first chunk in this group.
         */
        public XZ getFirstChunk() {
            return chunks.isEmpty() ? null : chunks.get(0);
        }

        /**
         * Get the dimension name as a string.
         */
        public String getDimensionName() {
            return dimension.location().toString();
        }
    }
}

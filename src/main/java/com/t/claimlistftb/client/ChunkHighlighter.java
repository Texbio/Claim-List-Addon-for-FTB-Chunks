package com.t.claimlistftb.client;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages temporary chunk highlights for visualizing claim changes on the map.
 * Highlights fade out over time.
 */
public class ChunkHighlighter {
    
    private static final ChunkHighlighter INSTANCE = new ChunkHighlighter();
    
    // Duration in milliseconds for highlight to fully fade
    private static final long FADE_DURATION_MS = 10000; // 10 seconds
    
    // Highlighted chunks: dimension -> list of highlights
    private final Map<ResourceKey<Level>, List<ChunkHighlight>> highlights = new ConcurrentHashMap<>();
    
    private ChunkHighlighter() {}
    
    public static ChunkHighlighter getInstance() {
        return INSTANCE;
    }
    
    /**
     * Highlight chunks from a ChangeGroup with per-chunk change type tracking
     */
    public void highlightChangeGroup(ClaimChangeGrouper.ChangeGroup group) {
        long now = System.currentTimeMillis();
        
        // Track per-chunk: what types of changes occurred
        Map<Long, HighlightType> chunkTypes = new HashMap<>();
        
        for (ClaimChangeReader.ClaimChange change : group.changes) {
            long key = packChunkPos(change.chunkX(), change.chunkZ());
            HighlightType currentType = chunkTypes.get(key);
            
            if (change.type() == ClaimChangeReader.ChangeType.ADD) {
                if (currentType == null) {
                    chunkTypes.put(key, HighlightType.ADD);
                } else if (currentType == HighlightType.REMOVE) {
                    chunkTypes.put(key, HighlightType.BOTH);
                }
            } else if (change.type() == ClaimChangeReader.ChangeType.REMOVE) {
                if (currentType == null) {
                    chunkTypes.put(key, HighlightType.REMOVE);
                } else if (currentType == HighlightType.ADD) {
                    chunkTypes.put(key, HighlightType.BOTH);
                }
            }
        }
        
        List<ChunkHighlight> dimHighlights = highlights.computeIfAbsent(group.dimension, k -> new ArrayList<>());
        
        // Clear existing highlights for these chunks
        dimHighlights.removeIf(h -> chunkTypes.containsKey(packChunkPos(h.chunkX, h.chunkZ)));
        
        // Add new highlights with proper types
        for (ClaimChangeReader.ClaimChange change : group.changes) {
            long key = packChunkPos(change.chunkX(), change.chunkZ());
            HighlightType type = chunkTypes.get(key);
            if (type != null) {
                dimHighlights.add(new ChunkHighlight(change.chunkX(), change.chunkZ(), now, type));
                chunkTypes.remove(key); // Only add once per chunk
            }
        }
    }
    
    /**
     * Get all active highlights for a dimension, removing expired ones
     */
    public List<ChunkHighlight> getActiveHighlights(ResourceKey<Level> dimension) {
        List<ChunkHighlight> dimHighlights = highlights.get(dimension);
        if (dimHighlights == null || dimHighlights.isEmpty()) {
            return Collections.emptyList();
        }
        
        long now = System.currentTimeMillis();
        
        // Remove expired highlights
        dimHighlights.removeIf(h -> now - h.startTime > FADE_DURATION_MS);
        
        return new ArrayList<>(dimHighlights);
    }
    
    /**
     * Check if there are any active highlights
     */
    public boolean hasActiveHighlights() {
        long now = System.currentTimeMillis();
        for (List<ChunkHighlight> list : highlights.values()) {
            for (ChunkHighlight h : list) {
                if (now - h.startTime <= FADE_DURATION_MS) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Get list of dimensions that have highlights (for debugging)
     */
    public String getAvailableDimensions() {
        long now = System.currentTimeMillis();
        return highlights.entrySet().stream()
            .filter(e -> e.getValue().stream().anyMatch(h -> now - h.startTime <= FADE_DURATION_MS))
            .map(e -> e.getKey().location().toString() + "(" + e.getValue().size() + ")")
            .collect(Collectors.joining(", "));
    }
    
    /**
     * Clear all highlights
     */
    public void clearAll() {
        highlights.clear();
    }
    
    private static long packChunkPos(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }
    
    /**
     * Type of highlight based on what changes occurred at this chunk
     */
    public enum HighlightType {
        ADD,    // Only additions at this chunk
        REMOVE, // Only removals at this chunk
        BOTH    // Both additions and removals at this chunk
    }
    
    /**
     * A single chunk highlight
     */
    public static class ChunkHighlight {
        public final int chunkX;
        public final int chunkZ;
        public final long startTime;
        public final HighlightType type;
        
        public ChunkHighlight(int chunkX, int chunkZ, long startTime, HighlightType type) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.startTime = startTime;
            this.type = type;
        }
        
        /**
         * Get the current alpha (0.0 to 1.0) based on fade progress
         */
        public float getAlpha() {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= FADE_DURATION_MS) {
                return 0f;
            }
            // Start at 0.6 alpha, fade to 0
            float progress = 1f - (elapsed / (float) FADE_DURATION_MS);
            return 0.6f * progress;
        }
        
        /**
         * Get the color with current alpha
         * Green = ADD only, Red = REMOVE only, Gray = BOTH
         * @return ARGB color integer
         */
        public int getColor() {
            float alpha = getAlpha();
            int a = (int) (alpha * 255);
            
            switch (type) {
                case ADD:
                    // Green for additions
                    return (a << 24) | 0x40FF40;
                case REMOVE:
                    // Red for removals
                    return (a << 24) | 0xFF4040;
                case BOTH:
                default:
                    // Gray for mixed changes
                    return (a << 24) | 0xA0A0A0;
            }
        }
        
        /**
         * Check if this highlight is still visible
         */
        public boolean isVisible() {
            return System.currentTimeMillis() - startTime < FADE_DURATION_MS;
        }
    }
}

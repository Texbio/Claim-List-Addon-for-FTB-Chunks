package com.t.claimlistftb.client;

import dev.ftb.mods.ftbchunks.client.map.MapChunk;
import dev.ftb.mods.ftbchunks.client.map.MapDimension;
import dev.ftb.mods.ftbchunks.client.map.MapManager;
import dev.ftb.mods.ftbchunks.client.map.MapRegion;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import dev.ftb.mods.ftblibrary.math.XZ;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Finds and caches claim information from FTB Chunks client-side data.
 * Uses ClaimChangeTracker's cache as primary source (receives ALL claims via packets)
 * with fallback to map regions for additional data like force-loaded status.
 */
public class PlayerClaimFinder {

    // Cache duration in milliseconds (5 seconds)
    private static final long CACHE_DURATION_MS = 5000L;

    // Cached results
    private static volatile Map<ClaimOwner, List<ClaimInfo>> cachedClaimMap = null;
    private static final AtomicLong lastCacheTime = new AtomicLong(0);
    private static volatile boolean cacheRefreshInProgress = false;

    public record ChunkPos(int x, int z) {}

    public record ClaimInfo(
            ChunkPos chunkPos,
            ResourceKey<Level> dimension,
            boolean isForceLoaded
    ) {}

    /**
     * Get all claimed chunks grouped by owner.
     * Uses caching to prevent lag - returns cached data if available.
     *
     * @param includeAllDimensions if true, scan all dimensions; if false, only current
     * @return Map of ClaimOwner to their list of claims
     */
    public static Map<ClaimOwner, List<ClaimInfo>> getClaimOwnerMap(boolean includeAllDimensions) {
        long now = System.currentTimeMillis();

        // Return cached data if still valid
        if (cachedClaimMap != null && (now - lastCacheTime.get()) < CACHE_DURATION_MS) {
            return cachedClaimMap;
        }

        // If a refresh is already in progress, return stale cache or empty map
        if (cacheRefreshInProgress) {
            return cachedClaimMap != null ? cachedClaimMap : Collections.emptyMap();
        }

        // Perform synchronous refresh (but with minimal work on main thread)
        return refreshCache(includeAllDimensions);
    }

    /**
     * Force a cache refresh. Should be called sparingly.
     */
    public static void invalidateCache() {
        lastCacheTime.set(0);
        cachedClaimMap = null;
    }

    /**
     * Refresh the cache synchronously.
     */
    private static Map<ClaimOwner, List<ClaimInfo>> refreshCache(boolean includeAllDimensions) {
        cacheRefreshInProgress = true;

        try {
            Map<ClaimOwner, List<ClaimInfo>> result = buildClaimMap(includeAllDimensions);
            cachedClaimMap = result;
            lastCacheTime.set(System.currentTimeMillis());
            return result;
        } finally {
            cacheRefreshInProgress = false;
        }
    }

    /**
     * Build the claim map. 
     * Primary source: ClaimChangeTracker's cache (has ALL claims from server packets)
     * Fallback: Map regions (only has claims in loaded regions)
     */
    private static Map<ClaimOwner, List<ClaimInfo>> buildClaimMap(boolean includeAllDimensions) {
        Map<ClaimOwner, List<ClaimInfo>> claimsByOwner = new LinkedHashMap<>();
        
        // Try to get claims from tracker cache first (has ALL claims)
        ClaimChangeTracker tracker = ClaimChangeTracker.getInstance();
        if (tracker.isInitialized() && tracker.hasCachedClaims()) {
            claimsByOwner = buildClaimMapFromTracker(includeAllDimensions);
            if (!claimsByOwner.isEmpty()) {
                return claimsByOwner;
            }
        }
        
        // Fallback to map regions (only has loaded regions)
        return buildClaimMapFromRegions(includeAllDimensions);
    }
    
    /**
     * Build claim map from ClaimChangeTracker's cache.
     * This has ALL claims that the server sent us via packets.
     */
    private static Map<ClaimOwner, List<ClaimInfo>> buildClaimMapFromTracker(boolean includeAllDimensions) {
        Map<ClaimOwner, List<ClaimInfo>> claimsByOwner = new LinkedHashMap<>();
        
        ClaimChangeTracker tracker = ClaimChangeTracker.getInstance();
        Map<UUID, List<ClaimChangeTracker.CachedChunkClaim>> cachedClaims = tracker.getAllCachedClaims();
        
        if (cachedClaims.isEmpty()) {
            return claimsByOwner;
        }
        
        // Get current dimension for filtering
        ResourceKey<Level> currentDimension = null;
        if (!includeAllDimensions) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                currentDimension = mc.level.dimension();
            }
        }
        
        // Process each team's claims
        for (Map.Entry<UUID, List<ClaimChangeTracker.CachedChunkClaim>> entry : cachedClaims.entrySet()) {
            UUID teamId = entry.getKey();
            List<ClaimChangeTracker.CachedChunkClaim> claims = entry.getValue();
            
            // Filter by dimension if needed
            List<ClaimInfo> filteredClaims = new ArrayList<>();
            for (ClaimChangeTracker.CachedChunkClaim claim : claims) {
                if (includeAllDimensions || claim.dimension().equals(currentDimension)) {
                    // We don't have force-loaded info from tracker, default to false
                    // Could enhance later by checking map regions for this data
                    filteredClaims.add(new ClaimInfo(
                        new ChunkPos(claim.chunkX(), claim.chunkZ()),
                        claim.dimension(),
                        false // Force-loaded status not tracked
                    ));
                }
            }
            
            if (filteredClaims.isEmpty()) {
                continue;
            }
            
            // Create ClaimOwner from team info
            ClaimOwner owner = createClaimOwnerFromTeamId(teamId);
            if (owner != null) {
                claimsByOwner.put(owner, filteredClaims);
            }
        }

        // Sort by claim count descending
        return claimsByOwner.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }
    
    /**
     * Create a ClaimOwner from a team ID using FTB Teams API
     */
    private static ClaimOwner createClaimOwnerFromTeamId(UUID teamId) {
        if (teamId == null || Util.NIL_UUID.equals(teamId)) {
            return null;
        }
        
        try {
            return FTBTeamsAPI.api()
                .getClientManager()
                .getTeamByID(teamId)
                .map(team -> {
                    String teamName = team.getProperty(TeamProperties.DISPLAY_NAME);
                    Set<UUID> members = team.getMembers();
                    
                    if (members.size() == 1) {
                        return ClaimOwner.forPlayer(teamName, teamId);
                    } else {
                        return ClaimOwner.forTeam(teamName, teamId);
                    }
                })
                .orElseGet(() -> {
                    // Team not found in API, create placeholder
                    String shortId = teamId.toString().substring(0, 8);
                    return ClaimOwner.forPlayer(shortId, teamId);
                });
        } catch (Exception e) {
            String shortId = teamId.toString().substring(0, 8);
            return ClaimOwner.forPlayer(shortId, teamId);
        }
    }

    /**
     * Build the claim map by scanning all loaded regions.
     * Fallback method - only sees claims in regions that have been viewed on map.
     */
    private static Map<ClaimOwner, List<ClaimInfo>> buildClaimMapFromRegions(boolean includeAllDimensions) {
        Map<ClaimOwner, List<ClaimInfo>> claimsByOwner = new LinkedHashMap<>();

        MapManager manager = MapManager.getInstance().orElse(null);
        if (manager == null) {
            return claimsByOwner;
        }

        Collection<MapDimension> dimensions;
        if (includeAllDimensions) {
            dimensions = new ArrayList<>(manager.getDimensions().values());
        } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                return claimsByOwner;
            }
            MapDimension currentDim = manager.getDimension(mc.level.dimension());
            dimensions = currentDim != null ? Collections.singletonList(currentDim) : Collections.emptyList();
        }

        // Collect claims by team ID first
        Map<UUID, List<ClaimInfo>> claimsByTeamId = new HashMap<>();
        Map<UUID, Team> teamCache = new HashMap<>();

        for (MapDimension dimension : dimensions) {
            scanDimensionForClaims(dimension, claimsByTeamId, teamCache);
        }

        // Convert to ClaimOwner map
        for (Map.Entry<UUID, List<ClaimInfo>> entry : claimsByTeamId.entrySet()) {
            UUID teamId = entry.getKey();
            List<ClaimInfo> claims = entry.getValue();
            Team team = teamCache.get(teamId);

            if (team == null) continue;

            ClaimOwner owner = createClaimOwner(team);
            claimsByOwner.put(owner, claims);
        }

        // Sort by claim count descending
        return claimsByOwner.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    /**
     * Scan a single dimension for claims.
     * Only scans loaded regions to avoid blocking on I/O.
     */
    private static void scanDimensionForClaims(MapDimension dimension,
                                                Map<UUID, List<ClaimInfo>> claimsByTeamId,
                                                Map<UUID, Team> teamCache) {
        // Get all loaded regions - this doesn't trigger I/O
        Map<XZ, MapRegion> regions = dimension.getRegions();

        for (MapRegion region : regions.values()) {
            // Only process regions that have data loaded (non-blocking check)
            if (!region.isDataLoaded()) {
                continue;
            }

            // Scan all 32x32 chunks within this region
            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    MapChunk chunk = region.getMapChunk(XZ.of(cx, cz));
                    if (chunk == null) continue;

                    Optional<Team> teamOpt = chunk.getTeam();
                    if (teamOpt.isEmpty()) continue;

                    Team team = teamOpt.get();
                    UUID teamId = team.getTeamId();
                    if (Util.NIL_UUID.equals(teamId)) continue;

                    teamCache.putIfAbsent(teamId, team);

                    // Get absolute chunk position
                    XZ actualPos = chunk.getActualPos();
                    boolean forceLoaded = chunk.getForceLoadedDate().isPresent();

                    ClaimInfo info = new ClaimInfo(
                            new ChunkPos(actualPos.x(), actualPos.z()),
                            dimension.dimension,
                            forceLoaded
                    );

                    claimsByTeamId.computeIfAbsent(teamId, k -> new ArrayList<>()).add(info);
                }
            }
        }
    }

    /**
     * Create a ClaimOwner from a team.
     */
    private static ClaimOwner createClaimOwner(Team team) {
        UUID teamId = team.getTeamId();
        String teamName = team.getProperty(TeamProperties.DISPLAY_NAME);
        Set<UUID> members = team.getMembers();

        if (members.size() == 1) {
            // Single member team - treat as player
            return ClaimOwner.forPlayer(teamName, teamId);
        } else {
            // Multi-member team
            return ClaimOwner.forTeam(teamName, teamId);
        }
    }

    /**
     * Get all unique claim owners.
     */
    public static Set<ClaimOwner> getAllClaimOwners(boolean includeAllDimensions) {
        return getClaimOwnerMap(includeAllDimensions).keySet();
    }

    /**
     * Get claims for a specific owner.
     */
    public static List<ClaimInfo> getClaimsForOwner(ClaimOwner owner, boolean includeAllDimensions) {
        Map<ClaimOwner, List<ClaimInfo>> allClaims = getClaimOwnerMap(includeAllDimensions);
        return allClaims.getOrDefault(owner, Collections.emptyList());
    }

    /**
     * Get total claim count.
     */
    public static int getTotalClaimCount() {
        Map<ClaimOwner, List<ClaimInfo>> claims = getClaimOwnerMap(true);
        return claims.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Check if any claims exist.
     */
    public static boolean hasAnyClaims() {
        return !getClaimOwnerMap(true).isEmpty();
    }

    /**
     * Get the team that owns a specific chunk (if any).
     */
    public static Optional<Team> getChunkOwner(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        MapManager manager = MapManager.getInstance().orElse(null);
        if (manager == null) {
            return Optional.empty();
        }

        MapDimension mapDim = manager.getDimension(dimension);
        if (mapDim == null) {
            return Optional.empty();
        }

        // Calculate region and local chunk position
        XZ regionPos = XZ.regionFromChunk(chunkX, chunkZ);
        MapRegion region = mapDim.getRegions().get(regionPos);
        if (region == null || !region.isDataLoaded()) {
            return Optional.empty();
        }

        // Local chunk position within region (0-31)
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;

        MapChunk chunk = region.getMapChunk(XZ.of(localX, localZ));
        if (chunk == null) {
            return Optional.empty();
        }

        Optional<Team> teamOpt = chunk.getTeam();
        if (teamOpt.isPresent() && !Util.NIL_UUID.equals(teamOpt.get().getTeamId())) {
            return teamOpt;
        }

        return Optional.empty();
    }
}

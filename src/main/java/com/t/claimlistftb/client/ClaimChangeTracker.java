package com.t.claimlistftb.client;

import com.t.claimlistftb.client.config.ClaimTrackerConfig;
import com.t.claimlistftb.client.gui.ClaimChangeHistoryScreen;
import com.t.claimlistftb.client.gui.PlayerClaimListScreen;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks claim changes in real-time by intercepting server sync packets.
 * Persists cache to detect changes that occurred while offline.
 * Includes safety checks to prevent false mass deletions from packet loss.
 * Pauses tracking when player is AFK to avoid unnecessary processing.
 */
public class ClaimChangeTracker {

    private static final ClaimChangeTracker INSTANCE = new ClaimChangeTracker();
    private static final DateTimeFormatter CSV_TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // Cache of known chunk states: dimension -> (chunkPos -> teamId)
    private final Map<ResourceKey<Level>, Map<Long, UUID>> chunkStateCache = new ConcurrentHashMap<>();

    // Pending changes to write (batched for efficiency)
    private final List<PendingChange> pendingChanges = Collections.synchronizedList(new ArrayList<>());

    // Current server info
    private UUID currentServerId;
    private String currentServerName;
    private boolean isSingleplayer;
    private Path changesFile;
    private Path cacheFile;

    // Previous session cache (loaded from disk)
    private Map<ResourceKey<Level>, Map<Long, UUID>> previousCache = new HashMap<>();
    private int previousCacheSize = 0;
    
    // Track previous cache per dimension for smarter validation
    private Map<ResourceKey<Level>, Integer> previousCachePerDimension = new HashMap<>();

    // Track if we're in the initial sync phase
    private boolean initialSyncComplete = false;
    private long lastSyncTime = 0;
    private int chunksReceivedDuringSync = 0;
    
    // Dynamic sync window - extends if we're still receiving chunks
    private static final long BASE_SYNC_WINDOW_MS = 10000; // 10 seconds base
    private static final long EXTENDED_SYNC_WINDOW_MS = 20000; // 20 seconds if suspicious
    private long currentSyncWindowMs = BASE_SYNC_WINDOW_MS;
    
    // Safety threshold: if we receive less than this percentage of previous chunks, warn about possible data loss
    private static final double CHUNK_LOSS_THRESHOLD = 0.5; // 50%
    
    // Minimum previous chunks before we apply the safety threshold
    private static final int MIN_CHUNKS_FOR_SAFETY_CHECK = 10;
    
    // Dimension-specific thresholds (for 2+ teams with 50+ claims)
    private static final int DIMENSION_MIN_CLAIMS_FOR_CHECK = 50;
    
    // AFK detection
    private long lastPlayerActivityTime = 0;
    private double lastPlayerX = 0, lastPlayerY = 0, lastPlayerZ = 0;
    private float lastPlayerYaw = 0, lastPlayerPitch = 0;
    private boolean isAfk = false;
    private static final long AFK_THRESHOLD_MS = 15 * 60 * 1000; // 15 minutes
    private static final double MOVEMENT_THRESHOLD = 0.1; // Minimum movement to count as activity
    private long lastAfkCheckTime = 0;
    private static final long AFK_CHECK_INTERVAL_MS = 5000; // Check every 5 seconds

    private ClaimChangeTracker() {}

    public static ClaimChangeTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Called when joining a server/world
     */
    public void onServerJoin(UUID serverId, boolean singleplayer, String serverAddress) {
        this.currentServerId = serverId;
        this.isSingleplayer = singleplayer;
        this.initialSyncComplete = false;
        this.lastSyncTime = System.currentTimeMillis();
        this.chunksReceivedDuringSync = 0;
        this.currentSyncWindowMs = BASE_SYNC_WINDOW_MS;
        
        // Reset AFK state
        this.lastPlayerActivityTime = System.currentTimeMillis();
        this.isAfk = false;

        // Build server name for filenames
        if (singleplayer) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getSingleplayerServer() != null) {
                this.currentServerName = sanitizeFileName(mc.getSingleplayerServer().getWorldData().getLevelName());
            } else {
                this.currentServerName = "singleplayer";
            }
        } else if (serverAddress != null) {
            this.currentServerName = sanitizeFileName(serverAddress);
        } else {
            this.currentServerName = "unknown";
        }

        // Setup file paths (always do this so cache location is known)
        setupFilePaths();
        
        // Save current server to config
        ClaimTrackerConfig.setCurrentServerId(serverId.toString());
        
        // If tracking is disabled, stop here - don't create files or track changes
        if (!ClaimTrackerConfig.isTrackingEnabled()) {
            return;
        }
        
        // Create directories
        try {
            Files.createDirectories(changesFile.getParent());
        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to create changes folder: " + e.getMessage());
        }

        // Load previous session's cache for comparison
        loadPreviousCache();

        // Clear current cache for fresh data from server
        chunkStateCache.clear();
        pendingChanges.clear();
    }

    /**
     * Called when leaving a server/world
     */
    public void onServerLeave() {
        // Only save if tracking was enabled
        if (ClaimTrackerConfig.isTrackingEnabled() && currentServerId != null) {
            // Save current cache to disk for next session
            saveCacheToFile();
            
            // Flush any pending changes
            flushPendingChanges();
        }
        
        // Clear UI persistent state (search text, expanded lists, scroll positions)
        PlayerClaimListScreen.clearPersistentState();
        ClaimChangeHistoryScreen.clearPersistentState();

        this.currentServerId = null;
        this.currentServerName = null;
        this.changesFile = null;
        this.cacheFile = null;
        this.initialSyncComplete = false;
        this.isAfk = false;
        chunkStateCache.clear();
        pendingChanges.clear();
        previousCache.clear();
        previousCachePerDimension.clear();
        previousCacheSize = 0;
    }

    /**
     * Process a chunk update from the server.
     */
    public void processChunkUpdate(ResourceKey<Level> dimension, int chunkX, int chunkZ, boolean nowClaimed, UUID teamId) {
        if (!ClaimTrackerConfig.isTrackingEnabled() || currentServerId == null) {
            return;
        }
        
        // If player returns from AFK while receiving chunks, reset activity timer
        if (isAfk) {
            // Still process the chunk even if AFK, but don't change AFK state here
            // (that's handled in tick() based on player movement)
        }

        // Update last sync time
        lastSyncTime = System.currentTimeMillis();

        // Get or create dimension cache
        Map<Long, UUID> dimCache = chunkStateCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());

        // Create chunk key
        long chunkKey = chunkPosToLong(chunkX, chunkZ);

        // During initial sync, just build the cache
        if (!initialSyncComplete) {
            chunksReceivedDuringSync++;
            if (nowClaimed) {
                dimCache.put(chunkKey, teamId);
            } else {
                dimCache.remove(chunkKey);
            }
            return;
        }

        // After initial sync, detect changes
        UUID previousOwner = dimCache.get(chunkKey);
        boolean wasClaimed = previousOwner != null;

        // Detect state changes
        if (wasClaimed != nowClaimed) {
            ClaimChangeReader.ChangeType changeType = nowClaimed ? 
                ClaimChangeReader.ChangeType.ADD : 
                ClaimChangeReader.ChangeType.REMOVE;

            UUID ownerToRecord = nowClaimed ? teamId : previousOwner;
            String teamName = getTeamName(ownerToRecord);

            PendingChange change = new PendingChange(
                LocalDateTime.now(),
                ownerToRecord,
                teamName,
                dimension,
                chunkX,
                chunkZ,
                changeType
            );
            pendingChanges.add(change);

            flushPendingChanges();
        } else if (wasClaimed && nowClaimed && !Objects.equals(previousOwner, teamId)) {
            // Ownership transferred
            String oldTeamName = getTeamName(previousOwner);
            String newTeamName = getTeamName(teamId);

            pendingChanges.add(new PendingChange(
                LocalDateTime.now(), previousOwner, oldTeamName, dimension, chunkX, chunkZ,
                ClaimChangeReader.ChangeType.REMOVE
            ));
            pendingChanges.add(new PendingChange(
                LocalDateTime.now(), teamId, newTeamName, dimension, chunkX, chunkZ,
                ClaimChangeReader.ChangeType.ADD
            ));

            flushPendingChanges();
        }

        // Update cache with current state
        if (nowClaimed) {
            dimCache.put(chunkKey, teamId);
        } else {
            dimCache.remove(chunkKey);
        }
    }

    /**
     * Called periodically to check if initial sync is complete and handle AFK detection
     */
    public void tick() {
        if (currentServerId == null || !ClaimTrackerConfig.isTrackingEnabled()) {
            return;
        }
        
        long now = System.currentTimeMillis();
        
        // AFK detection (check every 5 seconds to avoid overhead)
        if (now - lastAfkCheckTime > AFK_CHECK_INTERVAL_MS) {
            lastAfkCheckTime = now;
            checkAfkStatus();
        }
        
        // If AFK, don't process sync completion (we'll do it when player returns)
        if (isAfk) {
            return;
        }
        
        if (!initialSyncComplete) {
            long timeSinceLastSync = now - lastSyncTime;
            
            // Check if we should extend the sync window
            if (timeSinceLastSync > BASE_SYNC_WINDOW_MS && timeSinceLastSync < EXTENDED_SYNC_WINDOW_MS) {
                // Check if any dimension looks suspiciously empty
                if (shouldExtendSyncWindow()) {
                    if (currentSyncWindowMs == BASE_SYNC_WINDOW_MS) {
                        currentSyncWindowMs = EXTENDED_SYNC_WINDOW_MS;
                        
                    }
                }
            }
            
            if (timeSinceLastSync > currentSyncWindowMs) {
                completeInitialSync();
            }
        }
    }
    
    /**
     * Check if player is AFK based on position/rotation changes
     */
    private void checkAfkStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        
        // Check if player has moved or rotated
        double dx = Math.abs(x - lastPlayerX);
        double dy = Math.abs(y - lastPlayerY);
        double dz = Math.abs(z - lastPlayerZ);
        float dyaw = Math.abs(yaw - lastPlayerYaw);
        float dpitch = Math.abs(pitch - lastPlayerPitch);
        
        boolean hasMoved = dx > MOVEMENT_THRESHOLD || dy > MOVEMENT_THRESHOLD || dz > MOVEMENT_THRESHOLD;
        boolean hasRotated = dyaw > 0.5f || dpitch > 0.5f;
        
        if (hasMoved || hasRotated) {
            lastPlayerActivityTime = System.currentTimeMillis();
            
            if (isAfk) {
                isAfk = false;
                
                
                // Reset sync state to re-validate current chunk data
                if (initialSyncComplete) {
                    // Don't need to re-sync, just resume normal tracking
                }
            }
        }
        
        // Update last known position
        lastPlayerX = x;
        lastPlayerY = y;
        lastPlayerZ = z;
        lastPlayerYaw = yaw;
        lastPlayerPitch = pitch;
        
        // Check if now AFK
        long timeSinceActivity = System.currentTimeMillis() - lastPlayerActivityTime;
        if (!isAfk && timeSinceActivity > AFK_THRESHOLD_MS) {
            isAfk = true;
            
        }
    }
    
    /**
     * Check if we should extend the sync window due to suspicious data
     */
    private boolean shouldExtendSyncWindow() {
        // Check each dimension that had significant claims before
        for (Map.Entry<ResourceKey<Level>, Integer> entry : previousCachePerDimension.entrySet()) {
            ResourceKey<Level> dim = entry.getKey();
            int previousCount = entry.getValue();
            
            // Only check dimensions with significant claims (50+)
            if (previousCount >= DIMENSION_MIN_CLAIMS_FOR_CHECK) {
                Map<Long, UUID> currentDimCache = chunkStateCache.get(dim);
                int currentCount = currentDimCache != null ? currentDimCache.size() : 0;
                
                // If we have 0 chunks but previously had 50+, something might be wrong
                if (currentCount == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // Callback for when sync completes
    private Runnable onSyncCompleteCallback = null;
    
    /**
     * Set a callback to be called when initial sync completes
     */
    public void setOnSyncCompleteCallback(Runnable callback) {
        this.onSyncCompleteCallback = callback;
    }

    /**
     * Complete initial sync and compare with previous session
     */
    private void completeInitialSync() {
        initialSyncComplete = true;
        int currentCacheSize = chunkStateCache.values().stream().mapToInt(Map::size).sum();
        
        // Ensure cache file exists (even if empty)
        ensureCacheFileExists();

        // Safety check: detect possible data loss (global)
        boolean suspectGlobalDataLoss = false;
        if (previousCacheSize >= MIN_CHUNKS_FOR_SAFETY_CHECK) {
            double ratio = (double) currentCacheSize / previousCacheSize;
            
            if (currentCacheSize == 0 && previousCacheSize > MIN_CHUNKS_FOR_SAFETY_CHECK) {
                suspectGlobalDataLoss = true;
            }
        }
        
        // Per-dimension safety check
        Set<ResourceKey<Level>> suspectDimensions = new HashSet<>();
        for (Map.Entry<ResourceKey<Level>, Integer> entry : previousCachePerDimension.entrySet()) {
            ResourceKey<Level> dim = entry.getKey();
            int previousCount = entry.getValue();
            
            if (previousCount >= DIMENSION_MIN_CLAIMS_FOR_CHECK) {
                Map<Long, UUID> currentDimCache = chunkStateCache.get(dim);
                int currentCount = currentDimCache != null ? currentDimCache.size() : 0;
                
                if (currentCount == 0) {
                    suspectDimensions.add(dim);
                }
            }
        }

        // Detect changes that occurred while offline (unless we suspect data loss)
        if (!suspectGlobalDataLoss) {
            detectOfflineChanges(suspectDimensions);
        }
        
        // Save initial cache state
        saveCacheToFile();
        
        // Notify callback that sync is complete
        if (onSyncCompleteCallback != null) {
            Minecraft.getInstance().execute(onSyncCompleteCallback);
        }
    }

    /**
     * Compare current state with previous session to detect offline changes
     * @param suspectDimensions Dimensions to skip due to suspected data loss
     */
    private void detectOfflineChanges(Set<ResourceKey<Level>> suspectDimensions) {
        if (previousCache.isEmpty()) {
            return;
        }

        int offlineAdds = 0;
        int offlineRemoves = 0;
        int offlineTransfers = 0;
        int skippedDimensions = 0;

        // Check for chunks that were unclaimed or transferred while offline
        for (Map.Entry<ResourceKey<Level>, Map<Long, UUID>> dimEntry : previousCache.entrySet()) {
            ResourceKey<Level> dimension = dimEntry.getKey();
            
            // Skip suspect dimensions
            if (suspectDimensions.contains(dimension)) {
                skippedDimensions++;
                continue;
            }
            
            Map<Long, UUID> prevDimCache = dimEntry.getValue();
            Map<Long, UUID> currDimCache = chunkStateCache.getOrDefault(dimension, Collections.emptyMap());

            for (Map.Entry<Long, UUID> chunkEntry : prevDimCache.entrySet()) {
                long chunkKey = chunkEntry.getKey();
                UUID prevOwner = chunkEntry.getValue();
                UUID currOwner = currDimCache.get(chunkKey);

                int[] coords = unpackChunkPos(chunkKey);

                if (currOwner == null) {
                    // Chunk was unclaimed while offline
                    String teamName = getTeamName(prevOwner);
                    pendingChanges.add(new PendingChange(
                        LocalDateTime.now(), prevOwner, teamName, dimension, coords[0], coords[1],
                        ClaimChangeReader.ChangeType.REMOVE
                    ));
                    offlineRemoves++;
                } else if (!currOwner.equals(prevOwner)) {
                    // Ownership changed while offline
                    String oldName = getTeamName(prevOwner);
                    String newName = getTeamName(currOwner);
                    pendingChanges.add(new PendingChange(
                        LocalDateTime.now(), prevOwner, oldName, dimension, coords[0], coords[1],
                        ClaimChangeReader.ChangeType.REMOVE
                    ));
                    pendingChanges.add(new PendingChange(
                        LocalDateTime.now(), currOwner, newName, dimension, coords[0], coords[1],
                        ClaimChangeReader.ChangeType.ADD
                    ));
                    offlineTransfers++;
                }
            }
        }

        // Check for new claims while offline
        for (Map.Entry<ResourceKey<Level>, Map<Long, UUID>> dimEntry : chunkStateCache.entrySet()) {
            ResourceKey<Level> dimension = dimEntry.getKey();
            
            // Skip suspect dimensions
            if (suspectDimensions.contains(dimension)) {
                continue;
            }
            
            Map<Long, UUID> currDimCache = dimEntry.getValue();
            Map<Long, UUID> prevDimCache = previousCache.getOrDefault(dimension, Collections.emptyMap());

            for (Map.Entry<Long, UUID> chunkEntry : currDimCache.entrySet()) {
                long chunkKey = chunkEntry.getKey();
                if (!prevDimCache.containsKey(chunkKey)) {
                    // New claim while offline
                    UUID owner = chunkEntry.getValue();
                    String teamName = getTeamName(owner);
                    int[] coords = unpackChunkPos(chunkKey);
                    pendingChanges.add(new PendingChange(
                        LocalDateTime.now(), owner, teamName, dimension, coords[0], coords[1],
                        ClaimChangeReader.ChangeType.ADD
                    ));
                    offlineAdds++;
                }
            }
        }

        if (offlineAdds > 0 || offlineRemoves > 0 || offlineTransfers > 0) {
            flushPendingChanges();
        }
    }

    /**
     * Force flush pending changes to disk
     */
    public void forceCheck() {
        flushPendingChanges();
    }

    /**
     * Write pending changes to CSV
     */
    private void flushPendingChanges() {
        if (pendingChanges.isEmpty() || changesFile == null) {
            return;
        }

        List<PendingChange> toWrite;
        synchronized (pendingChanges) {
            toWrite = new ArrayList<>(pendingChanges);
            pendingChanges.clear();
        }

        try {
            Files.createDirectories(changesFile.getParent());

            boolean needsHeader = !Files.exists(changesFile) || Files.size(changesFile) == 0;

            StringBuilder sb = new StringBuilder();
            if (needsHeader) {
                sb.append("timestamp,team_id,team_name,dimension,chunk_x,chunk_z,type\n");
            }

            for (PendingChange change : toWrite) {
                sb.append(change.timestamp.format(CSV_TIMESTAMP)).append(",");
                sb.append(change.teamId).append(",");
                sb.append(escapeCsv(change.teamName)).append(",");
                sb.append(change.dimension.location().toString()).append(",");
                sb.append(change.chunkX).append(",");
                sb.append(change.chunkZ).append(",");
                sb.append(change.type.name()).append("\n");
            }

            Files.writeString(changesFile, sb.toString(), 
                StandardOpenOption.CREATE, 
                StandardOpenOption.APPEND);

        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to write changes: " + e.getMessage());
        }
    }

    /**
     * Save current cache to disk for next session
     * Creates the file even if empty (with just header) so the file exists
     */
    private void saveCacheToFile() {
        if (cacheFile == null) {
            return;
        }

        try {
            Files.createDirectories(cacheFile.getParent());

            StringBuilder sb = new StringBuilder();
            sb.append("dimension,chunk_x,chunk_z,team_id\n");

            for (Map.Entry<ResourceKey<Level>, Map<Long, UUID>> dimEntry : chunkStateCache.entrySet()) {
                String dimName = dimEntry.getKey().location().toString();
                for (Map.Entry<Long, UUID> chunkEntry : dimEntry.getValue().entrySet()) {
                    int[] coords = unpackChunkPos(chunkEntry.getKey());
                    sb.append(dimName).append(",");
                    sb.append(coords[0]).append(",");
                    sb.append(coords[1]).append(",");
                    sb.append(chunkEntry.getValue()).append("\n");
                }
            }

            Files.writeString(cacheFile, sb.toString());

        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to save cache: " + e.getMessage());
        }
    }
    
    /**
     * Ensure cache file exists (creates empty file with header if needed)
     * Called after initial sync to establish the file
     */
    private void ensureCacheFileExists() {
        if (cacheFile == null) {
            return;
        }
        
        try {
            Files.createDirectories(cacheFile.getParent());
            if (!Files.exists(cacheFile)) {
                // Create empty cache file with just header
                Files.writeString(cacheFile, "dimension,chunk_x,chunk_z,team_id\n");
            }
        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to create cache file: " + e.getMessage());
        }
    }

    /**
     * Load previous session's cache from disk
     */
    private void loadPreviousCache() {
        previousCache.clear();
        previousCachePerDimension.clear();
        previousCacheSize = 0;

        if (cacheFile == null || !Files.exists(cacheFile)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(cacheFile);
            if (lines.size() <= 1) return; // Only header or empty

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length < 4) continue;

                try {
                    String dimStr = parts[0];
                    int chunkX = Integer.parseInt(parts[1]);
                    int chunkZ = Integer.parseInt(parts[2]);
                    UUID teamId = UUID.fromString(parts[3]);

                    // Parse dimension
                    ResourceKey<Level> dimension = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(dimStr)
                    );

                    previousCache.computeIfAbsent(dimension, k -> new HashMap<>())
                        .put(chunkPosToLong(chunkX, chunkZ), teamId);
                    previousCacheSize++;
                    
                    // Track per-dimension count
                    previousCachePerDimension.merge(dimension, 1, Integer::sum);

                } catch (Exception e) {
                    // Skip malformed lines
                }
            }

        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to load previous cache: " + e.getMessage());
        }
    }

    /**
     * Setup file paths for changes and cache
     */
    private void setupFilePaths() {
        String shortId = currentServerId.toString().substring(0, 8);
        
        if (isSingleplayer) {
            // For singleplayer: store in world/data/claimlistftb/
            Path worldDataFolder = getSingleplayerWorldDataFolder();
            if (worldDataFolder != null) {
                changesFile = worldDataFolder.resolve(currentServerName + "-" + shortId + ".csv");
                cacheFile = worldDataFolder.resolve(currentServerName + "-" + shortId + ".cache");
            } else {
                // Fallback to multiplayer folder if can't get world folder
                Path baseFolder = getMultiplayerChangesFolder();
                changesFile = baseFolder.resolve(currentServerName + "-" + shortId + ".csv");
                cacheFile = baseFolder.resolve(currentServerName + "-" + shortId + ".cache");
            }
        } else {
            // For multiplayer: use %APPDATA% on Windows or .minecraft on other systems
            Path baseFolder = getMultiplayerChangesFolder();
            changesFile = baseFolder.resolve(currentServerName + "-" + shortId + ".csv");
            cacheFile = baseFolder.resolve(currentServerName + "-" + shortId + ".cache");
        }
    }
    
    /**
     * Get the world data folder for singleplayer worlds
     * Returns: saves/<worldname>/data/claimlistftb/
     */
    private Path getSingleplayerWorldDataFolder() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            try {
                // Get the world folder from the server
                Path worldFolder = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                return worldFolder.resolve("data").resolve("claimlistftb");
            } catch (Exception e) {
                System.err.println("[ClaimListFTB] Failed to get singleplayer world folder: " + e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * Get the folder where multiplayer change files are stored
     * On Windows with useAppData enabled: %APPDATA%/claimlistftb/
     * Otherwise: config/claimlistftb/multiplayer-data/
     */
    public Path getMultiplayerChangesFolder() {
        // Try APPDATA first on Windows if enabled
        if (ClaimTrackerConfig.useAppData()) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                String appData = System.getenv("APPDATA");
                if (appData != null) {
                    return Path.of(appData, "claimlistftb");
                }
            }
        }
        
        // Fallback to config folder
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("config").resolve("claimlistftb").resolve("multiplayer-data");
    }

    /**
     * Get the folder where change files are stored (for external access)
     */
    public Path getChangesFolder() {
        if (changesFile != null) {
            return changesFile.getParent();
        }
        // Fallback for when not connected
        return getMultiplayerChangesFolder();
    }
    
    /**
     * Get the cache file path (for external access)
     */
    public Path getCacheFile() {
        return cacheFile;
    }

    /**
     * Get the team name for a given team ID
     */
    public String getTeamName(UUID teamId) {
        if (teamId == null) return "Unknown";

        try {
            return FTBTeamsAPI.api()
                .getClientManager()
                .getTeamByID(teamId)
                .map(team -> {
                    var nameComponent = team.getName();
                    if (nameComponent != null) {
                        String name = nameComponent.getString();
                        return name != null && !name.isEmpty() ? name : teamId.toString().substring(0, 8);
                    }
                    return teamId.toString().substring(0, 8);
                })
                .orElse(teamId.toString().substring(0, 8));
        } catch (Exception e) {
            return teamId.toString().substring(0, 8);
        }
    }

    // === Utility methods ===

    private static long chunkPosToLong(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static int[] unpackChunkPos(long packed) {
        return new int[] { (int) packed, (int) (packed >> 32) };
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_").toLowerCase();
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // === Getters for UI ===

    public UUID getCurrentServerId() {
        return currentServerId;
    }

    public boolean isInitialized() {
        return currentServerId != null;
    }

    public boolean isInitializing() {
        return currentServerId != null && !initialSyncComplete;
    }

    public long getRemainingInitializationSeconds() {
        if (initialSyncComplete) return 0;
        long elapsed = System.currentTimeMillis() - lastSyncTime;
        long remaining = currentSyncWindowMs - elapsed;
        return Math.max(0, remaining / 1000);
    }
    
    /**
     * Check if player is currently AFK
     */
    public boolean isAfk() {
        return isAfk;
    }
    
    /**
     * Get all claimed chunks from the tracker's cache.
     * Returns: Map of teamId -> List of (dimension, chunkX, chunkZ)
     * This provides data from server packets, not lazy-loaded map regions.
     */
    public Map<UUID, List<CachedChunkClaim>> getAllCachedClaims() {
        Map<UUID, List<CachedChunkClaim>> result = new HashMap<>();
        
        for (Map.Entry<ResourceKey<Level>, Map<Long, UUID>> dimEntry : chunkStateCache.entrySet()) {
            ResourceKey<Level> dimension = dimEntry.getKey();
            
            for (Map.Entry<Long, UUID> chunkEntry : dimEntry.getValue().entrySet()) {
                UUID teamId = chunkEntry.getValue();
                int[] coords = unpackChunkPos(chunkEntry.getKey());
                
                result.computeIfAbsent(teamId, k -> new ArrayList<>())
                    .add(new CachedChunkClaim(dimension, coords[0], coords[1]));
            }
        }
        
        return result;
    }
    
    /**
     * Check if the tracker has cached claim data available
     */
    public boolean hasCachedClaims() {
        return !chunkStateCache.isEmpty();
    }
    
    /**
     * Record for cached chunk claim data
     */
    public record CachedChunkClaim(
        ResourceKey<Level> dimension,
        int chunkX,
        int chunkZ
    ) {}

    /**
     * Record for pending changes before writing to disk
     */
    private record PendingChange(
        LocalDateTime timestamp,
        UUID teamId,
        String teamName,
        ResourceKey<Level> dimension,
        int chunkX,
        int chunkZ,
        ClaimChangeReader.ChangeType type
    ) {}
}

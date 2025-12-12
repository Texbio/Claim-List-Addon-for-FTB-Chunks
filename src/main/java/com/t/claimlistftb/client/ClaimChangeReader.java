package com.t.claimlistftb.client;

import com.t.claimlistftb.client.config.ClaimTrackerConfig;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handles reading and writing claim changes to disk.
 * Stores changes in a CSV file per server.
 */
public class ClaimChangeReader {

    private final UUID serverId;
    private final String serverName;
    private final Path dataFile;

    // In-memory change list (thread-safe)
    private final List<ClaimChange> changes = new CopyOnWriteArrayList<>();

    // Track if we need to save
    private volatile boolean dirty = false;

    public enum ChangeType {
        BASELINE,  // Initial state when tracking starts
        ADD,
        REMOVE
    }

    /**
     * Time periods for grouping changes in UI.
     */
    public enum TimePeriod {
        PAST_24_HOURS("Past 24 Hours"),
        PAST_7_DAYS("Past 7 Days"),
        PAST_30_DAYS("Past 30 Days"),
        OLDER("Older");

        private final String displayName;

        TimePeriod(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Owner key for grouping - uses team ID as primary key.
     */
    public record OwnerKey(
            UUID teamId,
            String teamName,
            List<String> oldNames,
            boolean isTeam
    ) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OwnerKey ownerKey = (OwnerKey) o;
            return Objects.equals(teamId, ownerKey.teamId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(teamId);
        }

        public int getOldNamesCount() {
            return oldNames == null ? 0 : oldNames.size();
        }

        public String getOldNamesString() {
            if (oldNames == null || oldNames.isEmpty()) return "";
            return "(" + String.join(", ", oldNames) + ")";
        }
    }

    /**
     * Count of added and removed chunks.
     */
    public record ChangeCount(int added, int removed) {}

    /**
     * Server info for server selection.
     */
    public record ServerInfo(UUID serverId, String serverName, java.time.LocalDateTime lastModified) {}

    public record ClaimChange(
            java.time.LocalDateTime timestamp,
            UUID teamId,
            String teamName,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            int chunkX,
            int chunkZ,
            ChangeType type
    ) {
        /**
         * Convert to CSV line.
         */
        public String toCsvLine() {
            return String.format("%s,%s,%s,%s,%d,%d,%s",
                    timestamp.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    teamId != null ? teamId.toString() : "",
                    escapeCsv(teamName != null ? teamName : ""),
                    dimension.location().toString(),
                    chunkX,
                    chunkZ,
                    type.name()
            );
        }

        /**
         * Parse from CSV line.
         */
        public static ClaimChange fromCsvLine(String line) {
            String[] parts = line.split(",", 7);
            if (parts.length < 7) return null;

            try {
                java.time.LocalDateTime timestamp = java.time.LocalDateTime.parse(parts[0], java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                UUID teamId = parts[1].isEmpty() ? null : UUID.fromString(parts[1]);
                String teamName = unescapeCsv(parts[2]);
                net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension = parseDimension(parts[3]);
                int chunkX = Integer.parseInt(parts[4]);
                int chunkZ = Integer.parseInt(parts[5]);
                ChangeType type = ChangeType.valueOf(parts[6]);

                return new ClaimChange(timestamp, teamId, teamName, dimension, chunkX, chunkZ, type);
            } catch (Exception e) {
                return null;
            }
        }

        private static String escapeCsv(String value) {
            if (value == null) return "";
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }

        private static String unescapeCsv(String value) {
            if (value == null || value.isEmpty()) return "";
            if (value.startsWith("\"") && value.endsWith("\"")) {
                return value.substring(1, value.length() - 1).replace("\"\"", "\"");
            }
            return value;
        }

        private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> parseDimension(String str) {
            return switch (str.toLowerCase()) {
                case "overworld", "minecraft:overworld" -> net.minecraft.world.level.Level.OVERWORLD;
                case "the_nether", "minecraft:the_nether" -> net.minecraft.world.level.Level.NETHER;
                case "the_end", "minecraft:the_end" -> net.minecraft.world.level.Level.END;
                default -> net.minecraft.world.level.Level.OVERWORLD;
            };
        }
    }

    public ClaimChangeReader(UUID serverId, String serverName) {
        this.serverId = serverId;
        this.serverName = serverName;
        this.dataFile = getDataPath();

        load();
    }

    /**
     * Get the path for the data file.
     */
    private Path getDataPath() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        Path modDir = gameDir.resolve("claimlistftb-changes");

        try {
            if (!Files.exists(modDir)) {
                Files.createDirectories(modDir);
            }
        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to create data directory: " + e.getMessage());
        }

        // Use server ID as filename (sanitized)
        String filename = serverId.toString().replace("-", "") + ".csv";
        return modDir.resolve(filename);
    }

    /**
     * Load changes from disk.
     */
    private void load() {
        if (!Files.exists(dataFile)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            // Skip header
            reader.readLine();

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                ClaimChange change = ClaimChange.fromCsvLine(line);
                if (change != null) {
                    changes.add(change);
                }
            }

            System.out.println("[ClaimListFTB] Loaded " + changes.size() + " changes from " + dataFile.getFileName());
        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to load changes: " + e.getMessage());
        }

        // Prune old changes
        pruneOldChanges();
    }

    /**
     * Save changes to disk.
     */
    public void save() {
        if (!dirty) return;

        try (BufferedWriter writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
            // Write header
            writer.write("timestamp,dimension,chunkX,chunkZ,teamId,type");
            writer.newLine();

            // Write all changes
            for (ClaimChange change : changes) {
                writer.write(change.toCsvLine());
                writer.newLine();
            }

            dirty = false;
            System.out.println("[ClaimListFTB] Saved " + changes.size() + " changes to " + dataFile.getFileName());
        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to save changes: " + e.getMessage());
        }
    }

    /**
     * Add a new change.
     */
    public void addChange(ClaimChange change) {
        changes.add(change);
        dirty = true;
    }

    /**
     * Get all changes.
     */
    public List<ClaimChange> getAllChanges() {
        return new ArrayList<>(changes);
    }

    /**
     * Get changes for a specific time period.
     */
    public List<ClaimChange> getChangesForPeriod(ClaimTrackerConfig.TimePeriod period) {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusSeconds(period.getMillis() / 1000);

        List<ClaimChange> result = new ArrayList<>();
        for (ClaimChange change : changes) {
            if (change.timestamp().isAfter(cutoff)) {
                result.add(change);
            }
        }

        // Sort by timestamp descending (newest first)
        result.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));

        return result;
    }

    /**
     * Remove changes older than the max retention period (1 year).
     */
    private void pruneOldChanges() {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusDays(365);

        int sizeBefore = changes.size();
        changes.removeIf(change -> change.timestamp().isBefore(cutoff));

        if (changes.size() < sizeBefore) {
            dirty = true;
            System.out.println("[ClaimListFTB] Pruned " + (sizeBefore - changes.size()) + " old changes");
        }
    }

    /**
     * Get count of changes.
     */
    public int getChangeCount() {
        return changes.size();
    }

    // ==================== STATIC UTILITY METHODS ====================

    /**
     * Read changes from a CSV file (static utility method).
     */
    public static List<ClaimChange> readChanges(Path csvFile) {
        List<ClaimChange> result = new ArrayList<>();

        if (!Files.exists(csvFile)) {
            return result;
        }

        try (BufferedReader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
            String line;
            // Skip header
            reader.readLine();

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                ClaimChange change = ClaimChange.fromCsvLine(line);
                if (change != null) {
                    result.add(change);
                }
            }
        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to read changes from " + csvFile + ": " + e.getMessage());
        }

        return result;
    }

    /**
     * Group changes by time period.
     */
    public static Map<TimePeriod, List<ClaimChange>> groupByTimePeriod(List<ClaimChange> changes) {
        Map<TimePeriod, List<ClaimChange>> grouped = new java.util.EnumMap<>(TimePeriod.class);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        for (ClaimChange change : changes) {
            long hoursAgo = java.time.temporal.ChronoUnit.HOURS.between(change.timestamp(), now);
            long daysAgo = java.time.temporal.ChronoUnit.DAYS.between(change.timestamp(), now);

            TimePeriod period;
            if (hoursAgo < 24) {
                period = TimePeriod.PAST_24_HOURS;
            } else if (daysAgo < 7) {
                period = TimePeriod.PAST_7_DAYS;
            } else if (daysAgo < 30) {
                period = TimePeriod.PAST_30_DAYS;
            } else {
                period = TimePeriod.OLDER;
            }

            grouped.computeIfAbsent(period, k -> new ArrayList<>()).add(change);
        }

        return grouped;
    }

    /**
     * Group changes by owner, detecting team name changes.
     */
    public static Map<OwnerKey, List<ClaimChange>> groupByOwner(List<ClaimChange> changes) {
        Map<UUID, List<ClaimChange>> byTeamId = new HashMap<>();

        // First group by team ID
        for (ClaimChange change : changes) {
            if (change.teamId() != null) {
                byTeamId.computeIfAbsent(change.teamId(), k -> new ArrayList<>()).add(change);
            }
        }

        // Now create OwnerKeys with name history
        Map<OwnerKey, List<ClaimChange>> result = new java.util.LinkedHashMap<>();

        for (Map.Entry<UUID, List<ClaimChange>> entry : byTeamId.entrySet()) {
            UUID teamId = entry.getKey();
            List<ClaimChange> teamChanges = entry.getValue();

            // Sort by timestamp to get chronological order
            teamChanges.sort(Comparator.comparing(ClaimChange::timestamp));

            // Track all unique names used by this team
            java.util.LinkedHashSet<String> allNames = new java.util.LinkedHashSet<>();
            for (ClaimChange change : teamChanges) {
                if (change.teamName() != null && !change.teamName().isEmpty()) {
                    allNames.add(change.teamName());
                }
            }

            // Most recent name is the current name
            String currentName = teamChanges.get(teamChanges.size() - 1).teamName();
            if (currentName == null || currentName.isEmpty()) {
                currentName = "Unknown";
            }

            // Old names are all previous names
            List<String> oldNames = new ArrayList<>(allNames);
            oldNames.remove(currentName);

            OwnerKey key = new OwnerKey(teamId, currentName, oldNames, true);
            result.put(key, teamChanges);
        }

        // Sort by most recent change
        return result.entrySet().stream()
                .sorted((a, b) -> {
                    java.time.LocalDateTime aMax = a.getValue().stream()
                            .map(ClaimChange::timestamp)
                            .max(java.time.LocalDateTime::compareTo)
                            .orElse(java.time.LocalDateTime.MIN);
                    java.time.LocalDateTime bMax = b.getValue().stream()
                            .map(ClaimChange::timestamp)
                            .max(java.time.LocalDateTime::compareTo)
                            .orElse(java.time.LocalDateTime.MIN);
                    return bMax.compareTo(aMax);
                })
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        java.util.LinkedHashMap::new
                ));
    }

    /**
     * Count adds and removes in a list of changes.
     */
    public static ChangeCount countChanges(List<ClaimChange> changes) {
        int added = 0;
        int removed = 0;

        for (ClaimChange change : changes) {
            if (change.type() == ChangeType.ADD) {
                added++;
            } else if (change.type() == ChangeType.REMOVE) {
                removed++;
            }
            // BASELINE is not counted
        }

        return new ChangeCount(added, removed);
    }

    /**
     * Get list of available servers from the changes folder.
     */
    public static List<ServerInfo> getAvailableServers(Path changesFolder) {
        List<ServerInfo> servers = new ArrayList<>();

        if (!Files.exists(changesFolder)) {
            return servers;
        }

        try {
            Files.list(changesFolder)
                    .filter(path -> path.toString().endsWith(".csv"))
                    .forEach(path -> {
                        try {
                            String fileName = path.getFileName().toString();
                            // Try to parse server ID from filename
                            String baseName = fileName.replace(".csv", "");

                            // Handle format: "servername-shortid.csv" or "uuid.csv"
                            UUID serverId;
                            String serverName;

                            if (baseName.contains("-")) {
                                int lastDash = baseName.lastIndexOf("-");
                                serverName = baseName.substring(0, lastDash);
                                String shortId = baseName.substring(lastDash + 1);
                                // Reconstruct UUID (may not be exact, but good enough for display)
                                serverId = UUID.nameUUIDFromBytes(shortId.getBytes());
                            } else {
                                try {
                                    serverId = UUID.fromString(baseName);
                                    serverName = "Unknown Server";
                                } catch (Exception e) {
                                    serverId = UUID.nameUUIDFromBytes(baseName.getBytes());
                                    serverName = baseName;
                                }
                            }

                            java.time.LocalDateTime lastModified = java.time.LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(path).toInstant(),
                                    java.time.ZoneId.systemDefault()
                            );

                            servers.add(new ServerInfo(serverId, serverName, lastModified));
                        } catch (Exception e) {
                            System.err.println("[ClaimListFTB] Error parsing server file: " + path);
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Sort by most recently modified
        servers.sort(Comparator.comparing(ServerInfo::lastModified).reversed());
        return servers;
    }
}

package com.t.claimlistftb.client.config;

import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for claim change tracking
 */
public class ClaimTrackerConfig {
    private static final String MOD_FOLDER = "claimlistftb";
    private static Path configFile = null;

    private static SNBTCompoundTag config = new SNBTCompoundTag();
    private static boolean loaded = false;

    // Default values
    private static final long DEFAULT_CHECK_INTERVAL = 60; // seconds
    private static final boolean DEFAULT_TRACKING_ENABLED = true;
    private static final boolean DEFAULT_USE_24_HOUR_TIME = false;
    private static final boolean DEFAULT_USE_DD_MM_FORMAT = false;
    private static final boolean DEFAULT_SHOW_HISTORY_BUTTON = true;
    private static final boolean DEFAULT_SHOW_COPY_ALL_BUTTON = true;
    private static final boolean DEFAULT_USE_APPDATA = true; // Use APPDATA on Windows for multiplayer data

    /**
     * Time periods for filtering claim changes.
     */
    public enum TimePeriod {
        LAST_HOUR("Last Hour", 60 * 60 * 1000L),
        LAST_24_HOURS("Last 24 Hours", 24 * 60 * 60 * 1000L),
        LAST_7_DAYS("Last 7 Days", 7 * 24 * 60 * 60 * 1000L),
        LAST_30_DAYS("Last 30 Days", 30L * 24 * 60 * 60 * 1000L),
        ALL_TIME("All Time", 365L * 24 * 60 * 60 * 1000L); // 1 year max

        private final String displayName;
        private final long millis;

        TimePeriod(String displayName, long millis) {
            this.displayName = displayName;
            this.millis = millis;
        }

        public String getDisplayName() {
            return displayName;
        }

        public long getMillis() {
            return millis;
        }

        public TimePeriod next() {
            TimePeriod[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }

        public TimePeriod previous() {
            TimePeriod[] values = values();
            return values[(this.ordinal() - 1 + values.length) % values.length];
        }
    }

    static {
        // Create config directory on class load
        ensureConfigDirectoryExists();
        load();
    }

    /**
     * Gets the base directory for the mod
     * Located in config/claimlistftb/
     */
    private static Path getModDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(MOD_FOLDER);
    }

    /**
     * Gets the data file path (for tracker state data)
     * Located in config/claimlistftb/data/
     */
    private static Path getConfigFile() {
        if (configFile == null) {
            configFile = getModDirectory().resolve("data").resolve("claimlistftb-tracker.snbt");
        }
        return configFile;
    }
    
    /**
     * Ensures the config directory structure exists
     * Call this early to create config/claimlistftb/ folder
     */
    public static void ensureConfigDirectoryExists() {
        try {
            // Create both the main folder and data subfolder
            Files.createDirectories(getModDirectory().resolve("data"));
        } catch (IOException e) {
            System.err.println("[ClaimListFTB] Failed to create config directory: " + e.getMessage());
        }
    }

    private static void load() {
        if (loaded) return;

        try {
            Path configPath = getConfigFile();

            if (Files.exists(configPath)) {
                config = SNBT.read(configPath);
            }
        } catch (Exception e) {
            System.err.println("[ClaimListFTB] Failed to load tracker config: " + e.getMessage());
            config = new SNBTCompoundTag();
        }

        loaded = true;
    }

    public static void save() {
        try {
            Path configPath = getConfigFile();
            Files.createDirectories(configPath.getParent());
            SNBT.write(configPath, config);
        } catch (Exception e) {
            System.err.println("[ClaimListFTB] Failed to save tracker config: " + e.getMessage());
        }
    }

    public static long getCheckIntervalSeconds() {
        if (config.contains("check_interval")) {
            return config.getLong("check_interval");
        }
        return DEFAULT_CHECK_INTERVAL;
    }

    public static void setCheckIntervalSeconds(long seconds) {
        config.putLong("check_interval", seconds);
        save();
    }

    public static boolean isTrackingEnabled() {
        if (config.contains("tracking_enabled")) {
            return config.getBoolean("tracking_enabled");
        }
        return DEFAULT_TRACKING_ENABLED;
    }

    public static void setTrackingEnabled(boolean enabled) {
        config.putBoolean("tracking_enabled", enabled);
        save();
    }

    public static boolean use24HourTime() {
        if (config.contains("use_24_hour_time")) {
            return config.getBoolean("use_24_hour_time");
        }
        return DEFAULT_USE_24_HOUR_TIME;
    }

    public static void setUse24HourTime(boolean use24Hour) {
        config.putBoolean("use_24_hour_time", use24Hour);
        save();
    }

    public static boolean useDDMMFormat() {
        if (config.contains("use_dd_mm_format")) {
            return config.getBoolean("use_dd_mm_format");
        }
        return DEFAULT_USE_DD_MM_FORMAT;
    }

    public static void setUseDDMMFormat(boolean useDDMM) {
        config.putBoolean("use_dd_mm_format", useDDMM);
        save();
    }

    public static String getCurrentServerId() {
        if (config.contains("current_server_id")) {
            return config.getString("current_server_id");
        }
        return null;
    }

    public static void setCurrentServerId(String serverId) {
        if (serverId != null) {
            config.putString("current_server_id", serverId);
        } else {
            config.remove("current_server_id");
        }
        save();
    }
    
    /**
     * Whether to show the History button in the claim list screen
     */
    public static boolean showHistoryButton() {
        if (config.contains("show_history_button")) {
            return config.getBoolean("show_history_button");
        }
        return DEFAULT_SHOW_HISTORY_BUTTON;
    }

    public static void setShowHistoryButton(boolean show) {
        config.putBoolean("show_history_button", show);
        save();
    }
    
    /**
     * Whether to show the Copy All Claims button in the claim list screen
     */
    public static boolean showCopyAllButton() {
        if (config.contains("show_copy_all_button")) {
            return config.getBoolean("show_copy_all_button");
        }
        return DEFAULT_SHOW_COPY_ALL_BUTTON;
    }

    public static void setShowCopyAllButton(boolean show) {
        config.putBoolean("show_copy_all_button", show);
        save();
    }
    
    /**
     * Whether to use APPDATA for multiplayer data storage on Windows
     * If false, uses config/claimlistftb/multiplayer-data/ instead
     */
    public static boolean useAppData() {
        if (config.contains("use_appdata")) {
            return config.getBoolean("use_appdata");
        }
        return DEFAULT_USE_APPDATA;
    }

    public static void setUseAppData(boolean use) {
        config.putBoolean("use_appdata", use);
        save();
    }
}

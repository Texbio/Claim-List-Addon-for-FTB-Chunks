package com.t.claimlistftb.client.gui;

import com.t.claimlistftb.client.ClaimChangeTracker;
import com.t.claimlistftb.client.config.ClaimTrackerConfig;
import com.t.claimlistftb.client.ClaimChangeReader;
import com.t.claimlistftb.client.ClaimChangeGrouper;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.*;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ClaimChangeHistoryScreen extends BaseScreen {

    private final BaseScreen parentScreen;
    private SimpleButton backButton;
    private SimpleButton refreshButton;
    private SimpleButton settingsButton;
    private SimpleButton serverSelectButton;
    private SimpleButton closeButton;
    private Panel scrollContent;

    // Persistent state across screen opens/closes - stored per server
    private static final Map<UUID, Set<String>> SERVER_EXPANDED_PERIODS = new HashMap<>();
    private static final Map<UUID, Set<String>> SERVER_EXPANDED_OWNERS = new HashMap<>();
    private static final Map<UUID, Double> SERVER_SCROLL_POSITIONS = new HashMap<>();
    
    /**
     * Clear all persistent state (call when leaving server/world)
     */
    public static void clearPersistentState() {
        SERVER_EXPANDED_PERIODS.clear();
        SERVER_EXPANDED_OWNERS.clear();
        SERVER_SCROLL_POSITIONS.clear();
    }

    private Set<String> expandedPeriods = new HashSet<>();
    private Set<String> expandedOwners = new HashSet<>();

    // Current server being viewed
    private UUID currentServerId;
    private List<ClaimChangeReader.ClaimChange> allChanges = new ArrayList<>();

    // Track last loaded data for change detection
    private int lastChangeCount = 0;
    private long lastRefreshTime = 0;

    // Cache for grouped changes to prevent lag
    private Map<String, List<ClaimChangeGrouper.ChangeGroup>> groupedChangesCache = new HashMap<>();

    // OPTIMIZED: Increased from 5 seconds to 30 seconds to reduce lag
    private static final long AUTO_REFRESH_INTERVAL_MS = 30000; // 30 seconds

    public ClaimChangeHistoryScreen(BaseScreen parent) {
        this.parentScreen = parent;
        setWidth(260);
    }
    
    @Override
    public void addWidgets() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Set height to 80% of screen height
        int calculatedHeight = (int)(screenHeight * 0.8);
        setHeight(calculatedHeight);

        setPos((screenWidth - width) / 2, (screenHeight - height) / 2);
        
        // Register callback for when sync completes so we can refresh
        ClaimChangeTracker.getInstance().setOnSyncCompleteCallback(() -> {
            if (scrollContent != null) {
                loadServerChanges();
                scrollContent.refreshWidgets();
            }
        });

        // Back button - top left (returns to previous screen)
        backButton = new SimpleButton(this, Component.empty(), Icons.BACK, (btn, mouse) -> closeGui());
        add(backButton);

        // Refresh button - top right area, left of server select
        refreshButton = new SimpleButton(this,
                Arrays.asList(
                        Component.literal("Refresh Now"),
                        Component.literal("Force update claim cache").withStyle(net.minecraft.ChatFormatting.GRAY)
                ),
                Icons.REFRESH,
                (btn, mouse) -> {
                    // Check if still initializing
                    ClaimChangeTracker tracker = ClaimChangeTracker.getInstance();
                    if (tracker.isInitializing()) {
                        long remaining = tracker.getRemainingInitializationSeconds();
                        mc.player.displayClientMessage(
                                Component.literal("Please wait " + remaining + " more seconds for initialization to complete")
                                        .withStyle(net.minecraft.ChatFormatting.YELLOW),
                                false
                        );
                        return;
                    }

                    // Force a check now
                    tracker.forceCheck();
                    // Reload changes and check if anything changed
                    int oldCount = allChanges.size();
                    loadServerChanges();
                    int newCount = allChanges.size();

                    // Only refresh widgets if there was actually a change
                    if (oldCount != newCount && scrollContent != null) {
                        scrollContent.refreshWidgets();
                    }
                }) {
            @Override
            public void addMouseOverText(dev.ftb.mods.ftblibrary.util.TooltipList list) {
                ClaimChangeTracker tracker = ClaimChangeTracker.getInstance();
                if (tracker.isInitializing()) {
                    long remaining = tracker.getRemainingInitializationSeconds();
                    list.add(Component.literal("Initializing...").withStyle(net.minecraft.ChatFormatting.YELLOW));
                    list.add(Component.literal("Wait " + remaining + "s").withStyle(net.minecraft.ChatFormatting.GRAY));
                } else {
                    super.addMouseOverText(list);
                }
            }
        };
        add(refreshButton);

        // Server select button (controller icon) - top right, left of settings
        serverSelectButton = new SimpleButton(this,
                Arrays.asList(
                        Component.literal("Select Server"),
                        Component.literal("Choose which server's changes to view").withStyle(net.minecraft.ChatFormatting.GRAY)
                ),
                Icons.CONTROLLER,
                (btn, mouse) -> openServerSelector());
        add(serverSelectButton);

        // Settings button - top right, left of close
        settingsButton = new SimpleButton(this,
                Arrays.asList(
                        Component.literal("Settings"),
                        Component.literal("Configure tracking options").withStyle(net.minecraft.ChatFormatting.GRAY)
                ),
                Icons.SETTINGS,
                (btn, mouse) -> openSettingsMenu());
        add(settingsButton);

        // Close button - top right (returns all the way to map)
        closeButton = new SimpleButton(this,
                Arrays.asList(
                        Component.literal("Close"),
                        Component.literal("Return to map").withStyle(net.minecraft.ChatFormatting.GRAY)
                ),
                Icons.CANCEL,
                (btn, mouse) -> {
                    // Get map screen reference before closing
                    LargeMapScreen mapScreen = null;
                    if (parentScreen instanceof PlayerClaimListScreen claimList) {
                        mapScreen = claimList.getParentMapScreen();
                    }
                    
                    // Close this screen (History) - screen becomes null
                    closeGui(false);
                    
                    // Open the map directly
                    if (mapScreen != null) {
                        mapScreen.openGui();
                    }
                });
        add(closeButton);

        // Expand "Past 7 days" by default
        expandedPeriods.add("PAST_7_DAYS");

        // Determine which server to show
        loadServerChanges();
        lastChangeCount = allChanges.size();

        // Scrollable content area
        scrollContent = new Panel(this) {
            @Override
            public void addWidgets() {
                // Check if tracking is disabled
                if (!ClaimTrackerConfig.isTrackingEnabled()) {
                    add(new Widget(this) {
                        @Override
                        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
                            String text = "Claim tracking is disabled";
                            int textWidth = theme.getStringWidth(text);
                            theme.drawString(graphics, text, x + (w - textWidth) / 2, y + 10, Color4I.rgb(0xFF5555), 0);
                            
                            String text2 = "Enable it in Settings to track changes";
                            int textWidth2 = theme.getStringWidth(text2);
                            theme.drawString(graphics, text2, x + (w - textWidth2) / 2, y + 25, Color4I.rgb(0x888888), 0);
                        }
                    });
                    return;
                }
                
                // Check if still initializing
                ClaimChangeTracker tracker = ClaimChangeTracker.getInstance();
                if (tracker.isInitializing()) {
                    // Show initialization message instead of content
                    add(new Widget(this) {
                        @Override
                        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
                            long remaining = tracker.getRemainingInitializationSeconds();
                            String text = "Waiting for initialization (" + remaining + "s)";
                            int textWidth = theme.getStringWidth(text);
                            theme.drawString(graphics, text, x + (w - textWidth) / 2, y + 10, Color4I.rgb(0x888888), 0);
                        }
                    });
                    return;
                }

                // Build widgets here - don't call buildChangeWidgets which tries to add to 'this'
                if (allChanges == null || allChanges.isEmpty()) {
                    // Show "no changes" message
                    add(new Widget(this) {
                        @Override
                        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
                            String text = "No claim changes found";
                            int textWidth = theme.getStringWidth(text);
                            theme.drawString(graphics, text, x + (w - textWidth) / 2, y + 10, Color4I.rgb(0x888888), 0);
                        }
                    });
                    return;
                }

                // Group changes by time period
                Map<ClaimChangeReader.TimePeriod, List<ClaimChangeReader.ClaimChange>> byPeriod =
                        ClaimChangeReader.groupByTimePeriod(allChanges);

                if (byPeriod == null || byPeriod.isEmpty()) {
                    return;
                }

                // Sort periods (most recent first) - create a copy to avoid modification issues
                List<ClaimChangeReader.TimePeriod> periods = new ArrayList<>(byPeriod.keySet());

                // For each period, create an expandable section
                for (ClaimChangeReader.TimePeriod period : periods) {
                    List<ClaimChangeReader.ClaimChange> periodChanges = byPeriod.get(period);
                    if (periodChanges == null || periodChanges.isEmpty()) continue;

                    add(new TimePeriodWidget(this, period, periodChanges));
                }
            }

            @Override
            public void alignWidgets() {
                int y = 10;
                for (Widget widget : getWidgets()) {
                    if (widget instanceof TimePeriodWidget periodWidget) {
                        int requiredHeight = periodWidget.getRequiredHeight();
                        widget.setPosAndSize(0, y, width, requiredHeight);
                        y += requiredHeight + 2;
                    } else {
                        widget.setPosAndSize(0, y, width, 20);
                        y += 20 + 2;
                    }
                }
                setHeight(Math.max(y, 22));
            }

            @Override
            public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
                int allocatedHeight = ClaimChangeHistoryScreen.this.height - 65;
                graphics.enableScissor(x, y, x + w, y + allocatedHeight);
                super.draw(graphics, theme, x, y, w, allocatedHeight);
                graphics.disableScissor();
            }

            @Override
            public boolean mouseScrolled(double scroll) {
                double scrollMultiplier = (isShiftKeyDown() || isCtrlKeyDown()) ? 5.0 : 1.0;
                int maxScroll = Math.max(0, height - (ClaimChangeHistoryScreen.this.height - 65));
                setScrollY(Math.max(0, Math.min(maxScroll, (int)(getScrollY() - scroll * 20 * scrollMultiplier))));
                return true;
            }
        };

        add(scrollContent);
        scrollContent.refreshWidgets();

        // Restore scroll position after widgets are refreshed
        if (currentServerId != null) {
            Double savedScroll = SERVER_SCROLL_POSITIONS.get(currentServerId);
            if (savedScroll != null) {
                scrollContent.setScrollY(savedScroll);
            }
        }
    }

    @Override
    public void alignWidgets() {
        backButton.setPosAndSize(5, 5, 20, 20);
        refreshButton.setPosAndSize(width - 97, 5, 20, 20);
        serverSelectButton.setPosAndSize(width - 73, 5, 20, 20);
        settingsButton.setPosAndSize(width - 49, 5, 20, 20);
        closeButton.setPosAndSize(width - 25, 5, 20, 20);

        if (scrollContent != null) {
            scrollContent.setPosAndSize(10, 30, width - 20, height - 40);
            scrollContent.alignWidgets();
        }
    }

    @Override
    public void onClosed() {
        // Save scroll position before closing
        if (currentServerId != null && scrollContent != null) {
            SERVER_SCROLL_POSITIONS.put(currentServerId, scrollContent.getScrollY());
        }
        // Clear sync callback when screen closes
        ClaimChangeTracker.getInstance().setOnSyncCompleteCallback(null);
        super.onClosed();
    }

    @Override
    public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
        Color4I.rgb(0x1E1E1E).withAlpha(220).draw(graphics, x, y, w, h);
        theme.drawString(graphics, "History", x + w / 2, y + 10, Color4I.WHITE, Theme.CENTERED);
    }

    @Override
    public void tick() {
        super.tick();

        ClaimChangeTracker tracker = ClaimChangeTracker.getInstance();

        // If initializing, refresh widgets every second to update countdown
        if (tracker.isInitializing()) {
            if (System.currentTimeMillis() - lastRefreshTime > 1000) { // Refresh every second
                lastRefreshTime = System.currentTimeMillis();
                if (scrollContent != null) {
                    scrollContent.refreshWidgets();
                }
            }
            return; // Skip normal auto-update logic during initialization
        }

        // OPTIMIZED: Reduced auto-update frequency from 5s to 30s
        // Auto-update if changes are detected and only "past 7 days" is expanded
        if (System.currentTimeMillis() - lastRefreshTime > AUTO_REFRESH_INTERVAL_MS) {
            lastRefreshTime = System.currentTimeMillis();

            // Only auto-update if exactly one period is expanded and it's "past_7_days"
            if (expandedPeriods.size() == 1 && expandedPeriods.contains("PAST_7_DAYS")) {
                int oldCount = allChanges.size();
                loadServerChanges();
                int newCount = allChanges.size();

                // If there was a change, refresh
                if (oldCount != newCount && scrollContent != null) {
                    scrollContent.refreshWidgets();
                    lastChangeCount = newCount;
                }
            }
        }
    }

    private void openSettingsMenu() {
        List<ContextMenuItem> items = new ArrayList<>();

        // Toggle 24-hour time format
        String timeFormat = ClaimTrackerConfig.use24HourTime() ? "Use 12-hour Time" : "Use 24-hour Time";
        items.add(new ContextMenuItem(Component.literal(timeFormat), Icons.TIME, b -> {
            ClaimTrackerConfig.setUse24HourTime(!ClaimTrackerConfig.use24HourTime());
            // Don't close context menu
            // Refresh display
            if (scrollContent != null) {
                scrollContent.refreshWidgets();
            }
        }));

        // Toggle DD-MM vs MM-DD format
        String dateFormat = ClaimTrackerConfig.useDDMMFormat() ? "Use mm-dd Format" : "Use dd-mm Format";
        items.add(new ContextMenuItem(Component.literal(dateFormat), Icons.BOOK, b -> {
            ClaimTrackerConfig.setUseDDMMFormat(!ClaimTrackerConfig.useDDMMFormat());
            // Don't close context menu
            // Refresh display
            if (scrollContent != null) {
                scrollContent.refreshWidgets();
            }
        }));

        // Check interval submenu (only show if tracking is enabled)
        if (ClaimTrackerConfig.isTrackingEnabled()) {
            long currentInterval = ClaimTrackerConfig.getCheckIntervalSeconds();
            items.add(new ContextMenuItem(Component.literal("Check Interval (" + currentInterval + "s)"), Icons.SETTINGS, b -> {
                openCheckIntervalMenu();
            }));
        }

        // Enable/Disable tracking toggle
        if (ClaimTrackerConfig.isTrackingEnabled()) {
            // Disable tracking (with confirmation)
            items.add(new ContextMenuItem(Component.literal("Disable Tracking"), Icons.CANCEL, b -> {
                openYesNo(
                        Component.literal("Disable Claim Tracking?"),
                        Component.literal("Are you sure you want to disable claim change tracking?"),
                        () -> {
                            ClaimTrackerConfig.setTrackingEnabled(false);
                            closeContextMenu();
                            // Refresh the content to show disabled message
                            if (scrollContent != null) {
                                scrollContent.refreshWidgets();
                            }
                        }
                );
            }));
        } else {
            // Enable tracking
            items.add(new ContextMenuItem(Component.literal("Enable Tracking"), Icons.ACCEPT, b -> {
                ClaimTrackerConfig.setTrackingEnabled(true);
                closeContextMenu();
                // Refresh the content
                if (scrollContent != null) {
                    scrollContent.refreshWidgets();
                }
            }));
        }

        // Open multiplayer cache folder
        items.add(new ContextMenuItem(Component.literal("Multiplayer Cache Folder"), Icons.COMPASS, b -> {
            try {
                Path folder = ClaimChangeTracker.getInstance().getMultiplayerChangesFolder();

                // Create folder if it doesn't exist
                if (!Files.exists(folder)) {
                    Files.createDirectories(folder);
                }

                // Use Desktop API - more reliable across platforms
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(folder.toFile());
                } else {
                    // Fallback to platform-specific commands with ProcessBuilder
                    String os = System.getProperty("os.name").toLowerCase();
                    ProcessBuilder pb;
                    if (os.contains("win")) {
                        pb = new ProcessBuilder("explorer", folder.toString());
                    } else if (os.contains("mac")) {
                        pb = new ProcessBuilder("open", folder.toString());
                    } else {
                        pb = new ProcessBuilder("xdg-open", folder.toString());
                    }
                    pb.start();
                }
            } catch (IOException e) {
                System.err.println("Failed to open multiplayer cache folder: " + e.getMessage());
                e.printStackTrace();
            }
            // Don't close context menu
        }));

        // Open cache file location
        items.add(new ContextMenuItem(Component.literal("Open Cache File Location"), Icons.BOOK, b -> {
            try {
                Path cacheFile = ClaimChangeTracker.getInstance().getCacheFile();

                if (cacheFile != null && Files.exists(cacheFile)) {
                    // Open the folder containing the file and select it
                    Path parentFolder = cacheFile.getParent();
                    String os = System.getProperty("os.name").toLowerCase();

                    ProcessBuilder pb;
                    if (os.contains("win")) {
                        // Windows: explorer /select,filepath - need to quote the path
                        pb = new ProcessBuilder("explorer", "/select,", cacheFile.toString());
                    } else if (os.contains("mac")) {
                        // Mac: open -R filepath
                        pb = new ProcessBuilder("open", "-R", cacheFile.toString());
                    } else {
                        // Linux: just open the folder
                        if (java.awt.Desktop.isDesktopSupported()) {
                            java.awt.Desktop.getDesktop().open(parentFolder.toFile());
                            return;
                        }
                        pb = new ProcessBuilder("xdg-open", parentFolder.toString());
                    }
                    pb.start();
                } else {
                    // Show message to player
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                            Component.literal("No cache file found for this server yet").withStyle(net.minecraft.ChatFormatting.YELLOW),
                            false
                        );
                    }
                }
            } catch (IOException e) {
                System.err.println("[ClaimListFTB] Failed to open cache file location: " + e.getMessage());
            }
            // Don't close context menu
        }));

        ContextMenu menu = new ContextMenu(this, items);
        openContextMenu(menu);
    }

    private void openCheckIntervalMenu() {
        List<ContextMenuItem> items = new ArrayList<>();

        long current = ClaimTrackerConfig.getCheckIntervalSeconds();

        // Add options: 10s, 30s, 60s, 120s, 300s
        long[] intervals = {10, 30, 60, 120, 300};
        String[] labels = {"10s", "30s", "1m", "2m", "5m"};

        for (int i = 0; i < intervals.length; i++) {
            long interval = intervals[i];
            String label = labels[i];
            Icon icon = (interval == current) ? Icons.ACCEPT : Icons.ACCEPT_GRAY;

            items.add(new ContextMenuItem(Component.literal(label), icon, b -> {
                ClaimTrackerConfig.setCheckIntervalSeconds(interval);
                // Don't close menu
            }));
        }

        ContextMenu menu = new ContextMenu(this, items);
        openContextMenu(menu);
    }

    private void openServerSelector() {
        // TODO: Implement server selector
        // For now, just close any open menu
        closeContextMenu();
    }

    /**
     * Loads changes for the current server
     */
    private void loadServerChanges() {
        // Try to use saved server ID from config, or current server, or most recent
        String savedServerId = ClaimTrackerConfig.getCurrentServerId();
        ClaimChangeTracker tracker = ClaimChangeTracker.getInstance();

        if (savedServerId != null) {
            try {
                currentServerId = UUID.fromString(savedServerId);
            } catch (Exception e) {
                currentServerId = null;
            }
        }

        // If no saved server or invalid, try current
        if (currentServerId == null && tracker.getCurrentServerId() != null) {
            currentServerId = tracker.getCurrentServerId();
        }

        // If still no server, use most recent
        if (currentServerId == null) {
            List<ClaimChangeReader.ServerInfo> servers = ClaimChangeReader.getAvailableServers(tracker.getChangesFolder());
            if (!servers.isEmpty()) {
                currentServerId = servers.get(0).serverId();
            }
        }

        // Load changes for this server
        if (currentServerId != null) {
            // Load persistent expanded state and scroll position for this server
            expandedPeriods = SERVER_EXPANDED_PERIODS.computeIfAbsent(currentServerId, k -> new HashSet<>());
            expandedOwners = SERVER_EXPANDED_OWNERS.computeIfAbsent(currentServerId, k -> new HashSet<>());

            Path csvFile = findServerChangesFile(tracker.getChangesFolder(), currentServerId);

            if (csvFile != null && Files.exists(csvFile)) {
                List<ClaimChangeReader.ClaimChange> loadedChanges = ClaimChangeReader.readChanges(csvFile);

                // Filter out BASELINE entries - we only want actual changes
                // Create mutable ArrayList for sorting
                allChanges = new ArrayList<>(loadedChanges.stream()
                        .filter(change -> change.type() != ClaimChangeReader.ChangeType.BASELINE)
                        .toList());

                // Sort by timestamp descending (most recent first)
                allChanges.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));
            } else {
                allChanges = new ArrayList<>();
            }

            // Clear the group cache when loading new data
            groupedChangesCache.clear();
        } else {
            allChanges = new ArrayList<>();
            expandedPeriods = new HashSet<>();
            expandedOwners = new HashSet<>();
        }
    }

    /**
     * Finds the changes.csv file for a given server ID
     */
    private Path findServerChangesFile(Path changesFolder, UUID serverId) {
        try {
            if (!Files.exists(changesFolder)) {
                System.err.println("[ClaimListFTB] Changes folder does not exist: " + changesFolder);
                return null;
            }

            // Get short UUID (first 8 chars)
            String shortUuid = serverId.toString().substring(0, 8);

            // Search for files ending with "-<shortUuid>.csv"
            java.util.stream.Stream<Path> files = Files.list(changesFolder);
            for (Path file : files.filter(p -> p.toString().endsWith(".csv")).toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith("-" + shortUuid + ".csv")) {
                    return file;
                }
            }

        } catch (Exception e) {
            System.err.println("[ClaimListFTB] Error finding server changes: " + e.getMessage());
        }

        return null;
    }

    /**
     * Widget for a time period (e.g., "Past 7 Days")
     */
    private class TimePeriodWidget extends Widget {
        private final ClaimChangeReader.TimePeriod period;
        private final List<ClaimChangeReader.ClaimChange> changes;
        private final List<OwnerChangeWidget> ownerWidgets = new ArrayList<>();

        public TimePeriodWidget(Panel panel, ClaimChangeReader.TimePeriod period, List<ClaimChangeReader.ClaimChange> changes) {
            super(panel);
            this.period = period;
            this.changes = changes;

            // Group by owner
            Map<ClaimChangeReader.OwnerKey, List<ClaimChangeReader.ClaimChange>> byOwner =
                    ClaimChangeReader.groupByOwner(changes);

            // Sort owners: teams first, then alphabetically
            List<ClaimChangeReader.OwnerKey> owners = new ArrayList<>(byOwner.keySet());
            owners.sort((a, b) -> {
                if (a.isTeam() && !b.isTeam()) return -1;
                if (!a.isTeam() && b.isTeam()) return 1;
                return a.teamName().compareToIgnoreCase(b.teamName());
            });

            // Create widgets for each owner
            for (ClaimChangeReader.OwnerKey owner : owners) {
                ownerWidgets.add(new OwnerChangeWidget(this, owner, byOwner.get(owner)));
            }
        }

        public int getRequiredHeight() {
            boolean expanded = expandedPeriods.contains(period.name());
            if (!expanded) {
                return 20;
            }

            int height = 20;
            for (OwnerChangeWidget widget : ownerWidgets) {
                height += widget.getRequiredHeight() + 2;
            }
            return height;
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            boolean expanded = expandedPeriods.contains(period.name());
            boolean mouseOver = getMouseY() >= y && getMouseY() < y + 20;

            // Background
            Color4I bg = mouseOver ? Color4I.rgb(0x3A3A3A) : Color4I.rgb(0x2A2A2A);
            bg.draw(graphics, x, y, w, 20);

            // Arrow
            Icon arrow = expanded ? Icons.DOWN : Icons.RIGHT;
            arrow.draw(graphics, x + 2, y + 2, 16, 16);

            // Period name
            theme.drawString(graphics, period.getDisplayName(), x + 20, y + 6, Color4I.WHITE, 0);

            // Draw child widgets if expanded
            if (expanded) {
                int childY = y + 20;
                for (OwnerChangeWidget widget : ownerWidgets) {
                    int widgetHeight = widget.getRequiredHeight();
                    widget.draw(graphics, theme, x + 10, childY, w - 10, widgetHeight);
                    childY += widgetHeight + 2;
                }
            }
        }

        @Override
        public boolean mousePressed(MouseButton button) {
            if (!isMouseOver()) return false;

            int mouseY = getMouseY();
            int widgetY = getY();

            // Check if clicked on header
            if (mouseY >= widgetY && mouseY < widgetY + 20) {
                if (button.isLeft()) {
                    // Toggle expansion
                    if (expandedPeriods.contains(period.name())) {
                        expandedPeriods.remove(period.name());
                    } else {
                        expandedPeriods.add(period.name());
                    }
                    if (scrollContent != null) {
                        scrollContent.refreshWidgets();
                    }
                    return true;
                }
            }

            // Check child widgets if expanded
            boolean expanded = expandedPeriods.contains(period.name());
            if (expanded) {
                int childY = widgetY + 20;
                for (OwnerChangeWidget widget : ownerWidgets) {
                    int widgetHeight = widget.getRequiredHeight();
                    if (mouseY >= childY && mouseY < childY + widgetHeight) {
                        // Pass click to child widget - need to set position first
                        widget.setPosAndSize(getX() + 10, childY, width - 10, widgetHeight);
                        boolean result = widget.mousePressed(button);
                        return result;
                    }
                    childY += widgetHeight + 2;
                }
            }

            return false;
        }
    }

    /**
     * Widget for an owner's changes (team or player)
     */
    private class OwnerChangeWidget extends Widget {
        private final ClaimChangeReader.OwnerKey owner;
        private final List<ClaimChangeReader.ClaimChange> changes;
        private final List<ClaimChangeGrouper.ChangeGroup> groups;
        private final ClaimChangeReader.ChangeCount totalCounts;

        public OwnerChangeWidget(Widget parent, ClaimChangeReader.OwnerKey owner, List<ClaimChangeReader.ClaimChange> changes) {
            super(parent.getParent());
            this.owner = owner;
            this.changes = changes;

            // Use cached groups or compute and cache them
            String cacheKey = owner.teamId().toString();
            this.groups = groupedChangesCache.computeIfAbsent(cacheKey, k -> ClaimChangeGrouper.groupChanges(changes));
            this.totalCounts = ClaimChangeReader.countChanges(changes);
        }

        public int getRequiredHeight() {
            boolean expanded = expandedOwners.contains(owner.teamId().toString());
            if (!expanded) {
                return 20;
            }

            return 20 + (groups.size() * 18);
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            boolean expanded = expandedOwners.contains(owner.teamId().toString());
            boolean mouseOver = getMouseY() >= y && getMouseY() < y + 20;

            // Background
            Color4I bg = mouseOver ? Color4I.rgb(0x353535) : Color4I.rgb(0x252525);
            bg.draw(graphics, x, y, w, 20);

            // Arrow
            Icon arrow = expanded ? Icons.DOWN : Icons.RIGHT;
            arrow.draw(graphics, x + 2, y + 2, 16, 16);

            // Owner name (purple for teams, white for players)
            Color4I nameColor = owner.isTeam() ? Color4I.rgb(0xDD99FF) : Color4I.WHITE;
            theme.drawString(graphics, owner.teamName(), x + 20, y + 6, nameColor, 0);

            // Show old names count if any
            int nameWidth = theme.getStringWidth(owner.teamName());
            int countX = x + 20 + nameWidth;

            if (owner.getOldNamesCount() > 0) {
                String oldNamesText = " (+" + owner.getOldNamesCount() + " old name" +
                        (owner.getOldNamesCount() > 1 ? "s" : "") + ")";
                theme.drawString(graphics, oldNamesText, countX, y + 6, Color4I.rgb(0xAAAA00), 0);
                countX += theme.getStringWidth(oldNamesText);
            }

            // Change counts: "(+X) (-Y)" format showing both adds and removes
            if (totalCounts.added() > 0 || totalCounts.removed() > 0) {
                StringBuilder countText = new StringBuilder(" ");
                if (totalCounts.added() > 0) {
                    theme.drawString(graphics, countText.toString(), countX, y + 6, Color4I.WHITE, 0);
                    countX += theme.getStringWidth(countText.toString());
                    String addText = "(+" + totalCounts.added() + ")";
                    theme.drawString(graphics, addText, countX, y + 6, Color4I.rgb(0x55FF55), 0);
                    countX += theme.getStringWidth(addText);
                }
                if (totalCounts.removed() > 0) {
                    if (totalCounts.added() > 0) {
                        theme.drawString(graphics, " ", countX, y + 6, Color4I.WHITE, 0);
                        countX += theme.getStringWidth(" ");
                    }
                    String removeText = "(-" + totalCounts.removed() + ")";
                    theme.drawString(graphics, removeText, countX, y + 6, Color4I.rgb(0xFF5555), 0);
                }
            }

            // Draw expanded groups
            if (expanded) {
                int subY = 20;
                for (ClaimChangeGrouper.ChangeGroup group : groups) {
                    boolean subMouseOver = getMouseY() >= y + subY && getMouseY() < y + subY + 16;
                    Color4I subBg = subMouseOver ? Color4I.rgb(0x3A3A3A) : Color4I.rgb(0x1E1E1E);
                    subBg.draw(graphics, x, y + subY, w, 16);

                    // Dimension name
                    String dimName = group.dimension.location().getPath();
                    if (dimName.equals("the_end")) dimName = "end";

                    // Get most recent timestamp from this group
                    String timestamp = getMostRecentTimestamp(group);

                    // Draw: "  dimension (+X) (-Y) timestamp"
                    int textX = x + 4;
                    theme.drawString(graphics, "  " + dimName, textX, y + subY + 4, Color4I.rgb(0xAAAAFF), 0);
                    textX += theme.getStringWidth("  " + dimName);

                    // Draw counts: "(+X) (-Y)" format
                    ClaimChangeReader.ChangeCount groupCounts = group.getCounts();
                    if (groupCounts.added() > 0 || groupCounts.removed() > 0) {
                        theme.drawString(graphics, " ", textX, y + subY + 4, Color4I.WHITE, 0);
                        textX += theme.getStringWidth(" ");
                        
                        if (groupCounts.added() > 0) {
                            String addText = "(+" + groupCounts.added() + ")";
                            theme.drawString(graphics, addText, textX, y + subY + 4, Color4I.rgb(0x55FF55), 0);
                            textX += theme.getStringWidth(addText);
                        }
                        if (groupCounts.removed() > 0) {
                            if (groupCounts.added() > 0) {
                                theme.drawString(graphics, " ", textX, y + subY + 4, Color4I.WHITE, 0);
                                textX += theme.getStringWidth(" ");
                            }
                            String removeText = "(-" + groupCounts.removed() + ")";
                            theme.drawString(graphics, removeText, textX, y + subY + 4, Color4I.rgb(0xFF5555), 0);
                            textX += theme.getStringWidth(removeText);
                        }
                    }

                    // Draw timestamp in light gray
                    if (!timestamp.isEmpty()) {
                        theme.drawString(graphics, " " + timestamp, textX, y + subY + 4, Color4I.rgb(0xAAAAAA), 0);
                    }

                    // Coordinates (right-aligned)
                    String coords = group.getBlockX() + ", " + group.getBlockZ();
                    int coordsX = x + w - theme.getStringWidth(coords) - 4;
                    theme.drawString(graphics, coords, coordsX, y + subY + 4, Color4I.rgb(0xFFFF88), 0);

                    subY += 18;
                }
            }
        }

        private String getMostRecentTimestamp(ClaimChangeGrouper.ChangeGroup group) {
            if (group.changes.isEmpty()) return "";

            // Get the most recent change in this group
            ClaimChangeReader.ClaimChange mostRecent = group.changes.stream()
                    .max((a, b) -> a.timestamp().compareTo(b.timestamp()))
                    .orElse(null);

            if (mostRecent == null) return "";

            // Format timestamp based on config
            java.time.format.DateTimeFormatter formatter;
            boolean use24Hour = ClaimTrackerConfig.use24HourTime();
            boolean useDDMM = ClaimTrackerConfig.useDDMMFormat();

            if (useDDMM) {
                formatter = use24Hour ?
                        java.time.format.DateTimeFormatter.ofPattern("dd-MM HH:mm") :
                        java.time.format.DateTimeFormatter.ofPattern("dd-MM h:mm a");
            } else {
                formatter = use24Hour ?
                        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm") :
                        java.time.format.DateTimeFormatter.ofPattern("MM-dd h:mm a");
            }

            return mostRecent.timestamp().format(formatter);
        }

        @Override
        public void addMouseOverText(dev.ftb.mods.ftblibrary.util.TooltipList list) {
            int mouseY = getMouseY();
            int widgetY = getY();

            // Check if mouse is over the owner header (first 20 pixels)
            if (mouseY >= widgetY && mouseY < widgetY + 20) {
                // Show old names tooltip if any exist
                if (owner.getOldNamesCount() > 0) {
                    list.add(Component.literal("Previous names:").withStyle(net.minecraft.ChatFormatting.GRAY));

                    // Show one name per line, max 10, newest to oldest
                    List<String> oldNames = owner.oldNames();
                    if (oldNames != null && !oldNames.isEmpty()) {
                        // oldNames are already in order from oldest to newest
                        // We want newest to oldest, so reverse
                        List<String> reversedNames = new ArrayList<>(oldNames);
                        Collections.reverse(reversedNames);

                        int count = 0;
                        for (String name : reversedNames) {
                            if (count >= 10) break;
                            list.add(Component.literal(name).withStyle(net.minecraft.ChatFormatting.YELLOW));
                            count++;
                        }
                    }
                }
            }
        }

        @Override
        public boolean mousePressed(MouseButton button) {
            int mouseY = getMouseY();
            boolean expanded = expandedOwners.contains(owner.teamId().toString());

            // Check if clicking on a group item (only when expanded)
            if (expanded) {
                // Use posY which is the widget's position in the panel's coordinate space
                int subY = 20; // Start at 20 (same as in draw)

                for (int i = 0; i < groups.size(); i++) {
                    ClaimChangeGrouper.ChangeGroup group = groups.get(i);
                    int itemTop = posY + subY;
                    int itemBottom = posY + subY + 16;

                    // Check if mouse is over this sub-item
                    if (mouseY >= itemTop && mouseY < itemBottom) {
                        // Click is on a group item
                        if (button.isLeft()) {
                            // Navigate to this group on the map
                            navigateToGroup(group);
                            return true;
                        } else if (button.isRight()) {
                            // Open context menu
                            openContextMenuForGroup(group);
                            return true;
                        }
                    }
                    subY += 18;
                }
            }

            // Click is on the owner header (or empty space) - toggle expansion on left click only
            if (button.isLeft()) {
                String key = owner.teamId().toString();
                if (expandedOwners.contains(key)) {
                    expandedOwners.remove(key);
                } else {
                    expandedOwners.add(key);
                }
                if (scrollContent != null) {
                    scrollContent.refreshWidgets();
                }
                return true;
            }

            return false;
        }

        private void navigateToGroup(ClaimChangeGrouper.ChangeGroup group) {
            // Convert block coords to region coords (regions are 512 blocks)
            double regionX = group.getBlockX() / 512.0;
            double regionZ = group.getBlockZ() / 512.0;
            
            // Store target dimension for use in callbacks
            ResourceKey<Level> targetDimension = group.dimension;
            int blockX = group.getBlockX();
            int blockZ = group.getBlockZ();
            
            // Trigger chunk highlight for this group
            com.t.claimlistftb.client.ChunkHighlighter.getInstance().highlightChangeGroup(group);

            // Close ALL screens first
            Minecraft.getInstance().setScreen(null);

            // Re-open the map screen fresh, then set dimension and scroll
            Minecraft.getInstance().execute(() -> {
                // Open a fresh map screen
                LargeMapScreen.openMap();
                
                // After opening, set dimension and scroll
                Minecraft.getInstance().execute(() -> {
                    // Screen is wrapped in ScreenWrapper by FTB Library
                    net.minecraft.client.gui.screens.Screen currentScreen = Minecraft.getInstance().screen;
                    if (currentScreen instanceof dev.ftb.mods.ftblibrary.ui.ScreenWrapper sw) {
                        if (sw.getGui() instanceof LargeMapScreen newMapScreen) {
                            com.t.claimlistftb.mixin.LargeMapScreenAccessor newAccessor = 
                                (com.t.claimlistftb.mixin.LargeMapScreenAccessor) newMapScreen;
                            
                            // Prevent auto-scroll to player position
                            newAccessor.setMovedToPlayer(true);
                            
                            // Get current dimension and check if switch needed
                            dev.ftb.mods.ftbchunks.client.map.MapDimension currentDim = newAccessor.getDimension();
                            
                            if (!currentDim.dimension.equals(targetDimension)) {
                                // Switch dimension on the NEW screen
                                dev.ftb.mods.ftbchunks.client.map.MapManager.getInstance().ifPresent(manager -> {
                                    dev.ftb.mods.ftbchunks.client.map.MapDimension newDim = manager.getDimension(targetDimension);
                                    newAccessor.setDimension(newDim);
                                    
                                    // Pre-load target region
                                    dev.ftb.mods.ftblibrary.math.XZ regionPos = dev.ftb.mods.ftblibrary.math.XZ.regionFromBlock(blockX, blockZ);
                                    dev.ftb.mods.ftbchunks.client.map.MapRegion targetRegion = newDim.getRegion(regionPos);
                                    targetRegion.getData();
                                    targetRegion.getRenderedMapImage();
                                });
                                
                                // Refresh and scroll after dimension change
                                Minecraft.getInstance().execute(() -> {
                                    newMapScreen.refreshWidgets();
                                    dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel panel = newAccessor.getRegionPanel();
                                    panel.resetScroll();
                                    panel.scrollTo(regionX, regionZ);
                                });
                            } else {
                                // Same dimension, just scroll
                                dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel panel = newAccessor.getRegionPanel();
                                panel.resetScroll();
                                panel.scrollTo(regionX, regionZ);
                            }
                        }
                    }
                });
            });
        }

        private void openContextMenuForGroup(ClaimChangeGrouper.ChangeGroup group) {
            Minecraft mc = Minecraft.getInstance();

            List<ContextMenuItem> items = new ArrayList<>();

            // Copy coordinates
            items.add(new ContextMenuItem(Component.literal("Copy"), Icons.ACCEPT, b -> {
                String surfaceY = getSurfaceYForCopy(group);
                String coords = group.getBlockX() + " " + surfaceY + " " + group.getBlockZ();
                mc.keyboardHandler.setClipboard(coords);
                mc.player.displayClientMessage(
                        Component.literal("Copied: " + coords).withStyle(net.minecraft.ChatFormatting.GREEN),
                        true
                );
                // Don't close context menu
            }));

            // Teleport command
            items.add(new ContextMenuItem(Component.literal("Teleport"), Icons.PLAYER, b -> {
                String dimId = group.dimension.location().toString();
                int surfaceY = getSurfaceY(group);
                int playerHeight = mc.player != null ? (int)Math.ceil(mc.player.getBbHeight()) : 2;
                int teleportY = surfaceY + playerHeight - 1;
                String tpCommand = "/execute in " + dimId + " run tp @s " + group.getBlockX() + " " + teleportY + " " + group.getBlockZ();
                ClaimChangeHistoryScreen.this.closeGui();
                mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(tpCommand));
            }));

            ContextMenu menu = new ContextMenu(ClaimChangeHistoryScreen.this, items);
            ClaimChangeHistoryScreen.this.openContextMenu(menu);
        }

        private int getRawSurfaceY(ClaimChangeGrouper.ChangeGroup group) {
            return dev.ftb.mods.ftbchunks.client.map.MapManager.getInstance()
                    .map(manager -> {
                        dev.ftb.mods.ftbchunks.client.map.MapDimension dim = manager.getDimension(group.dimension);
                        dev.ftb.mods.ftbchunks.client.map.MapRegion region = dim.getRegion(dev.ftb.mods.ftblibrary.math.XZ.regionFromBlock(group.getBlockX(), group.getBlockZ()));
                        dev.ftb.mods.ftbchunks.client.map.MapRegionData data = region.getData();

                        if (data != null) {
                            int localX = group.getBlockX() & 511;
                            int localZ = group.getBlockZ() & 511;
                            return (int) data.height[localX + localZ * 512];
                        }
                        return Integer.MIN_VALUE; // No data available
                    })
                    .orElse(Integer.MIN_VALUE);
        }
        
        private boolean isValidY(int y) {
            return y > -64 && y < 400 && y != Integer.MIN_VALUE;
        }

        private int getSurfaceY(ClaimChangeGrouper.ChangeGroup group) {
            int y = getRawSurfaceY(group);
            if (!isValidY(y)) {
                return 300; // Safe default for void worlds / unloaded chunks
            }
            return y;
        }
        
        private String getSurfaceYForCopy(ClaimChangeGrouper.ChangeGroup group) {
            int y = getRawSurfaceY(group);
            if (!isValidY(y)) {
                return "~"; // Use player's current Y
            }
            return String.valueOf(y);
        }
    }

    @Override
    public Theme getTheme() {
        return parentScreen.getTheme();
    }

    @Override
    public void onBack() {
        parentScreen.openGui();
    }
}

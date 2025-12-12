package com.t.claimlistftb.client.gui;

import com.t.claimlistftb.client.ClaimGrouper;
import com.t.claimlistftb.client.ClaimOwner;
import com.t.claimlistftb.client.PlayerClaimFinder;
import com.t.claimlistftb.client.config.ClaimTrackerConfig;
import com.t.claimlistftb.mixin.LargeMapScreenAccessor;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel;
import dev.ftb.mods.ftbchunks.client.map.MapRegionData;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.math.XZ;
import dev.ftb.mods.ftblibrary.ui.*;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.*;

public class PlayerClaimListScreen extends BaseScreen {

    private final LargeMapScreen parentMapScreen;
    private TextBox searchField;
    private List<ClaimOwner> allOwners;
    private List<ClaimOwner> filteredOwners;
    private Button closeButton;
    private Button copyAllButton;
    private Button historyButton;
    private Button expandAllButton;
    private Button collapseAllButton;
    private Panel scrollContent;

    // Persistent state
    private static Set<ClaimOwner> persistentExpandedOwners = new HashSet<>();
    private static double persistentScrollPosition = 0;
    private static String persistentSearchText = "";
    private Set<ClaimOwner> expandedOwners = persistentExpandedOwners;

    private ContextMenu activeContextMenu = null;
    private boolean groupByTeam = true;  // Group by team instead of individual players

    // Copy button feedback
    private long copyButtonResetTime = 0;

    // Cache for grouped claims to prevent lag
    private Map<ClaimOwner, List<ClaimGrouper.ChunkGroup>> groupedClaimsCache = new HashMap<>();
    
    /**
     * Clear all persistent state (call when leaving server/world)
     */
    public static void clearPersistentState() {
        persistentExpandedOwners.clear();
        persistentScrollPosition = 0;
        persistentSearchText = "";
    }

    public PlayerClaimListScreen(LargeMapScreen parent) {
        this.parentMapScreen = parent;
        this.allOwners = new ArrayList<>();
        this.filteredOwners = new ArrayList<>();

        setWidth(260);
        // Height will be set dynamically in addWidgets based on screen size
        
        // Clear any active highlights when opening this screen
        com.t.claimlistftb.client.ChunkHighlighter.getInstance().clearAll();
    }
    
    /**
     * Get the parent map screen
     */
    public LargeMapScreen getParentMapScreen() {
        return parentMapScreen;
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

        Set<ClaimOwner> owners = PlayerClaimFinder.getAllClaimOwners(groupByTeam);
        allOwners = new ArrayList<>(owners);
        // Sort: teams first (A-Z), then players (A-Z)
        allOwners.sort((a, b) -> {
            // Teams come before players
            if (a.isTeam() && !b.isTeam()) return -1;
            if (!a.isTeam() && b.isTeam()) return 1;
            // Within same type, sort alphabetically
            return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
        });
        filteredOwners = new ArrayList<>(allOwners);

        searchField = new TextBox(this);
        searchField.setText(persistentSearchText);
        add(searchField);
        
        // Apply initial filter if there's persistent search text
        if (!persistentSearchText.isEmpty()) {
            filterOwners(persistentSearchText);
        }

        scrollContent = new Panel(this) {
            @Override
            public void addWidgets() {
                for (ClaimOwner owner : filteredOwners) {
                    add(new OwnerEntryWidget(this, owner));
                }
            }

            @Override
            public void alignWidgets() {
                int y = 0;
                for (Widget widget : getWidgets()) {
                    int widgetHeight = 20;
                    if (widget instanceof OwnerEntryWidget ownerWidget) {
                        widgetHeight = ownerWidget.getRequiredHeight(width);
                    }
                    widget.setPosAndSize(0, y, width, widgetHeight);
                    y += widgetHeight + 2;
                }
                setHeight(Math.max(y, 22));
            }

            @Override
            public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
                // Calculate the visible area height
                int allocatedHeight = PlayerClaimListScreen.this.height - 65;

                // Use GuiGraphics.enableScissor which properly handles coordinates
                graphics.enableScissor(x, y, x + w, y + allocatedHeight);

                // Draw the panel content
                super.draw(graphics, theme, x, y, w, allocatedHeight);

                graphics.disableScissor();
            }

            @Override
            public boolean mouseScrolled(double scroll) {
                // Scroll 5x faster when shift or ctrl is held
                double scrollMultiplier = (isShiftKeyDown() || isCtrlKeyDown()) ? 5.0 : 1.0;

                // Clamp scroll to valid bounds
                int maxScroll = Math.max(0, height - (PlayerClaimListScreen.this.height - 65));
                setScrollY(Math.max(0, Math.min(maxScroll, (int)(getScrollY() - scroll * 20 * scrollMultiplier))));
                return true;
            }
        };


        add(scrollContent);
        scrollContent.refreshWidgets();

        // Restore scroll position
        scrollContent.setScrollY(persistentScrollPosition);

        closeButton = new SimpleButton(this,
                Arrays.asList(
                        Component.literal("Close"),
                        Component.literal("Return to map").withStyle(net.minecraft.ChatFormatting.GRAY)
                ),
                Icons.CANCEL,
                (btn, mouse) -> closeGui());
        add(closeButton);

        // Copy all claims button - top left with tooltip (if enabled in config)
        if (ClaimTrackerConfig.showCopyAllButton()) {
            copyAllButton = new SimpleButton(this,
                    Arrays.asList(
                            Component.literal("Copy All Claims"),
                            Component.literal("Copies all player claims to clipboard").withStyle(net.minecraft.ChatFormatting.GRAY)
                    ),
                    Icons.GLOBE,
                    (btn, mouse) -> {
                        copyAllClaimsToClipboard();
                        // Change icon to checkmark for 3 seconds
                        if (btn instanceof SimpleButton simpleBtn) {
                            simpleBtn.setIcon(Icons.ACCEPT);
                            copyButtonResetTime = System.currentTimeMillis() + 3000;
                        }
                    });
            add(copyAllButton);
        }

        // Claim Change History button (if enabled in config)
        if (ClaimTrackerConfig.showHistoryButton()) {
            historyButton = new SimpleButton(this,
                    Arrays.asList(
                            Component.literal("Claim Change History"),
                            Component.literal("View claim additions and removals over time").withStyle(net.minecraft.ChatFormatting.GRAY)
                    ),
                    Icons.INFO_GRAY,
                    (btn, mouse) -> openClaimHistory());
            add(historyButton);
        }

        // Expand all button
        expandAllButton = new SimpleButton(this,
                Arrays.asList(
                        Component.literal("Expand All"),
                        Component.literal("Expand all claim lists").withStyle(net.minecraft.ChatFormatting.GRAY)
                ),
                Icons.ADD,
                (btn, mouse) -> {
                    expandedOwners.addAll(allOwners);
                    scrollContent.refreshWidgets();
                });
        add(expandAllButton);

        // Collapse all button
        collapseAllButton = new SimpleButton(this,
                Arrays.asList(
                        Component.literal("Collapse All"),
                        Component.literal("Collapse all claim lists").withStyle(net.minecraft.ChatFormatting.GRAY)
                ),
                Icons.REMOVE,
                (btn, mouse) -> {
                    expandedOwners.clear();
                    scrollContent.refreshWidgets();
                });
        add(collapseAllButton);
    }

    private void openClaimHistory() {
        ClaimChangeHistoryScreen historyScreen = new ClaimChangeHistoryScreen(this);
        historyScreen.openGui();
    }

    @Override
    public void alignWidgets() {
        closeButton.setPosAndSize(width - 25, 5, 20, 20);
        expandAllButton.setPosAndSize(width - 49, 5, 20, 20);
        collapseAllButton.setPosAndSize(width - 73, 5, 20, 20);
        
        // Position buttons based on which are enabled
        int nextButtonX = 5;
        
        if (copyAllButton != null && ClaimTrackerConfig.showCopyAllButton()) {
            copyAllButton.setPosAndSize(nextButtonX, 5, 20, 20);
            nextButtonX += 24;
        }
        
        if (historyButton != null && ClaimTrackerConfig.showHistoryButton()) {
            historyButton.setPosAndSize(nextButtonX, 5, 20, 20);
        }
        
        searchField.setPosAndSize(10, 30, width - 20, 20);
        scrollContent.setPosAndSize(10, 55, width - 20, height - 65);

        // Force scrollContent to layout its children
        scrollContent.alignWidgets();
    }

    private void filterOwners(String search) {
        if (search.isEmpty()) {
            filteredOwners = new ArrayList<>(allOwners);
        } else {
            filteredOwners = allOwners.stream()
                    .filter(owner -> {
                        // Search in display name
                        if (owner.getDisplayName().toLowerCase().contains(search.toLowerCase())) {
                            return true;
                        }
                        // For teams, also search in member names
                        if (owner.isTeam()) {
                            for (String member : owner.getMembers()) {
                                if (member.toLowerCase().contains(search.toLowerCase())) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    })
                    .toList();
        }
        if (scrollContent != null) {
            scrollContent.refreshWidgets();
        }
    }

    @Override
    public void drawBackground(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
        Color4I.rgb(0x1E1E1E).withAlpha(220).draw(graphics, x, y, w, h);
        theme.drawString(graphics, "Claim List", x + w / 2, y + 10, Color4I.WHITE, Theme.CENTERED);
    }

    @Override
    public void tick() {
        super.tick();
        String currentText = searchField.getText();
        if (!currentText.equals(persistentSearchText)) {
            filterOwners(currentText);
            persistentSearchText = currentText;
        }

        // Reset copy button icon after 3 seconds
        if (copyButtonResetTime > 0 && System.currentTimeMillis() >= copyButtonResetTime) {
            if (copyAllButton instanceof SimpleButton simpleBtn) {
                simpleBtn.setIcon(Icons.GLOBE);
            }
            copyButtonResetTime = 0;
        }
    }

    private class OwnerEntryWidget extends Widget {
        private final ClaimOwner owner;
        private final List<ClaimGrouper.ChunkGroup> groups;
        private final int totalChunks;
        private final boolean hasMultipleGroups;

        public OwnerEntryWidget(Panel panel, ClaimOwner owner) {
            super(panel);
            this.owner = owner;
            // Use cached groups or compute and cache them
            this.groups = groupedClaimsCache.computeIfAbsent(owner,
                    k -> ClaimGrouper.groupOwnerClaims(owner, groupByTeam));
            this.totalChunks = groups.stream().mapToInt(ClaimGrouper.ChunkGroup::size).sum();
            this.hasMultipleGroups = true;  // Always require expansion, even for single groups
        }

        public int getRequiredHeight(int availableWidth) {
            boolean expanded = expandedOwners.contains(owner);
            int baseHeight = calculateMainHeight(availableWidth);

            if (expanded) {
                return baseHeight + 2 + (groups.size() * 18);  // +2 for spacing before sub-items
            }
            return baseHeight;
        }

        private int calculateMainHeight(int availableWidth) {
            int baseHeight = 20;  // Base height for name and count

            // Add extra height for team member names
            if (owner.isTeam() && !owner.getMembers().isEmpty()) {
                // Calculate how many lines the member list will take
                Theme theme = getTheme();
                String fullMemberList = owner.getMemberListString();
                int maxWidth = availableWidth - 20 - 8;  // availableWidth minus textX offset minus right padding
                List<String> memberLines = wrapMemberListContinuous(theme, fullMemberList, maxWidth);
                // 12px per line (font is ~8-9px + spacing) + 6px top padding
                baseHeight += 2 + (memberLines.size() * 12);
            } else if (owner.isTeam()) {
                baseHeight += 14;  // Space for "[no members]"
            }

            return baseHeight;
        }

        private List<String> wrapMemberListContinuous(Theme theme, String memberList, int maxWidth) {
            List<String> lines = new ArrayList<>();

            if (memberList == null || memberList.isEmpty()) {
                return lines;
            }

            // Remove brackets from the input
            String content = memberList;
            if (content.startsWith("(") && content.endsWith(")")) {
                content = content.substring(1, content.length() - 1);
            }

            // Check if it fits on one line without brackets
            if (theme.getStringWidth(content) <= maxWidth) {
                lines.add(content);
                return lines;
            }

            // Split by ", " to get individual member names
            String[] members = content.split(", ");

            StringBuilder currentLine = new StringBuilder();

            for (int i = 0; i < members.length; i++) {
                String member = members[i];
                boolean needsSeparator = currentLine.length() > 0;
                String separator = needsSeparator ? ", " : "";
                String testLine = currentLine.toString() + separator + member;

                // Check if adding this member would exceed width
                if (theme.getStringWidth(testLine) > maxWidth && currentLine.length() > 0) {
                    // Save current line and start new one
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(member);
                } else {
                    // Add to current line
                    if (needsSeparator) {
                        currentLine.append(separator);
                    }
                    currentLine.append(member);
                }
            }

            // Add the last line
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }

            return lines;
        }

        @Override
        public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
            boolean mouseOver = isMouseOver();
            boolean expanded = expandedOwners.contains(owner);

            // Calculate main height dynamically
            int mainHeight = calculateMainHeight(w);

            // Main row background
            Color4I bg = mouseOver ? Color4I.rgb(0x4A4A4A) : Color4I.rgb(0x2A2A2A);
            bg.draw(graphics, x, y, w, mainHeight);

            // Always show arrow icon
            Icon arrow = expanded ? Icons.DOWN : Icons.RIGHT;
            arrow.draw(graphics, x + 2, y + 2, 16, 16);

            // Owner name (light purple for teams, white for players)
            int textX = x + 20;
            Color4I nameColor = owner.isTeam() ? Color4I.rgb(0xDD99FF) : Color4I.WHITE;
            theme.drawString(graphics, owner.getDisplayName(), textX, y + 6, nameColor, 0);

            // Chunk count
            String countText = "(" + totalChunks + ")";
            int nameWidth = theme.getStringWidth(owner.getDisplayName());
            theme.drawString(graphics, countText, textX + nameWidth + 4, y + 6, Color4I.rgb(0x88FF88), 0);

            // For teams, show member list below name (handle empty member lists)
            if (owner.isTeam()) {
                if (owner.getMembers().isEmpty()) {
                    // Show "no members" message for teams with no members
                    theme.drawString(graphics, "[no members]", textX, y + 20, Color4I.rgb(0x888888), 0);
                } else {
                    // Get the member list and wrap it into multiple lines if needed
                    String fullMemberList = owner.getMemberListString();
                    int maxWidth = w - 20 - 8;  // w minus textX offset minus right padding

                    // Split member list into multiple lines (wrapping naturally, not at brackets)
                    List<String> memberLines = wrapMemberListContinuous(theme, fullMemberList, maxWidth);

                    // Draw each line
                    int lineY = y + 22;  // Start a bit lower for better spacing
                    for (String line : memberLines) {
                        theme.drawString(graphics, line, textX, lineY, Color4I.rgb(0xAAAAAA), 0);
                        lineY += 12;  // 12 pixels per line to match height calculation
                    }
                }
            }

            // Draw sub-items if expanded
            if (expanded && !groups.isEmpty()) {
                int subY = mainHeight + 2;
                for (ClaimGrouper.ChunkGroup group : groups) {
                    boolean subMouseOver = getMouseY() >= y + subY && getMouseY() < y + subY + 16;
                    Color4I subBg = subMouseOver ? Color4I.rgb(0x3A3A3A) : Color4I.rgb(0x252525);
                    subBg.draw(graphics, x + 10, y + subY, w - 20, 16);

                    String dimName = group.dimension().location().getPath();
                    if (dimName.equals("the_end")) dimName = "end";
                    theme.drawString(graphics, "  " + dimName, x + 14, y + subY + 4, Color4I.rgb(0xAAAAFF), 0);

                    String subCount = "(" + group.size() + ")";
                    int dimWidth = theme.getStringWidth("  " + dimName);
                    theme.drawString(graphics, subCount, x + 14 + dimWidth + 4, y + subY + 4, Color4I.rgb(0x88FF88), 0);

                    // Show coordinates using block coordinates directly
                    int[] centerBlocks = getGroupCenterBlocks(group);
                    String coords = centerBlocks[0] + ", " + centerBlocks[1];
                    int coordsX = x + w - theme.getStringWidth(coords) - 20;
                    theme.drawString(graphics, coords, coordsX, y + subY + 4, Color4I.rgb(0xFFFF88), 0);

                    subY += 18;
                }
            }
        }

        @Override
        public boolean mousePressed(MouseButton button) {
            if (!isMouseOver()) {
                return false;
            }

            int mouseY = getMouseY();
            int widgetY = getY();
            boolean expanded = expandedOwners.contains(owner);

            // Calculate main height dynamically
            int mainHeight = calculateMainHeight(width);

            // Check if clicked on main row (the header with name/arrow)
            if (mouseY >= widgetY && mouseY < widgetY + mainHeight) {
                if (button.isLeft()) {
                    // Check if shift or ctrl is held
                    if (isShiftKeyDown() || isCtrlKeyDown()) {
                        // Calculate height of all widgets ABOVE this one BEFORE expansion
                        int heightAboveBefore = 0;
                        int widgetIndex = 0;
                        int clickedIndex = -1;
                        for (Widget w : scrollContent.getWidgets()) {
                            if (w == this) {
                                clickedIndex = widgetIndex;
                                break;
                            }
                            heightAboveBefore += w.height + 2;
                            widgetIndex++;
                        }

                        // Pre-calculate height of all widgets AFTER expansion/collapse
                        int heightAboveAfter = 0;
                        boolean willExpand = !expanded;

                        widgetIndex = 0;
                        for (Widget w : scrollContent.getWidgets()) {
                            if (widgetIndex >= clickedIndex) break;

                            if (w instanceof OwnerEntryWidget ownerWidget) {
                                int calculatedHeight;
                                if (willExpand) {
                                    // Calculate expanded height
                                    calculatedHeight = ownerWidget.getRequiredHeight(scrollContent.width);
                                } else {
                                    // Calculate collapsed height
                                    calculatedHeight = ownerWidget.calculateMainHeight(scrollContent.width);
                                }
                                heightAboveAfter += calculatedHeight + 2;
                            } else {
                                heightAboveAfter += w.height + 2;
                            }
                            widgetIndex++;
                        }

                        // Calculate scroll adjustment needed
                        int heightDelta = heightAboveAfter - heightAboveBefore;
                        double currentScroll = scrollContent.getScrollY();

                        // Apply expand/collapse to all
                        if (expanded) {
                            expandedOwners.clear();
                        } else {
                            expandedOwners.addAll(allOwners);
                        }

                        scrollContent.refreshWidgets();

                        // Adjust scroll to compensate for height changes above
                        int maxScroll = Math.max(0, scrollContent.height - (PlayerClaimListScreen.this.height - 65));
                        scrollContent.setScrollY(Math.max(0, Math.min(maxScroll, currentScroll + heightDelta)));
                    } else {
                        // Normal toggle for just this owner
                        if (expanded) {
                            expandedOwners.remove(owner);
                        } else {
                            expandedOwners.add(owner);
                        }
                        scrollContent.refreshWidgets();
                    }
                    return true;
                }
                return true;
            }

            // Check if clicked on sub-item (expanded claim groups)
            if (expanded && !groups.isEmpty()) {
                int subY = widgetY + mainHeight + 2;
                for (ClaimGrouper.ChunkGroup group : groups) {
                    if (mouseY >= subY && mouseY < subY + 16) {
                        if (button.isLeft()) {
                            navigateToGroup(group);
                            return true;
                        } else if (button.isRight()) {
                            openContextMenu(group);
                            return true;
                        }
                    }
                    subY += 18;
                }
            }

            return false;
        }

        private void openContextMenu(ClaimGrouper.ChunkGroup group) {
            // Close any existing context menu
            if (activeContextMenu != null) {
                activeContextMenu.setPos(-10000, -10000);
                activeContextMenu = null;
            }

            // Use block coordinates directly for accurate positioning
            int[] centerBlocks = getGroupCenterBlocks(group);
            int blockX = centerBlocks[0];
            int blockZ = centerBlocks[1];

            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

            // Use FTBLibrary's ContextMenu
            List<ContextMenuItem> items = new ArrayList<>();

            // Copy button - use surface height (or ~ if unknown)
            items.add(new ContextMenuItem(Component.literal("Copy"), Icons.ACCEPT, b -> {
                String surfaceY = getSurfaceYForCopy(group);
                String coords = blockX + " " + surfaceY + " " + blockZ;
                mc.keyboardHandler.setClipboard(coords);
                mc.player.displayClientMessage(
                        Component.literal("Copied: " + coords).withStyle(net.minecraft.ChatFormatting.GREEN),
                        true
                );
            }));

            // Teleport button - add player height offset minus 1 (so feet are on surface)
            items.add(new ContextMenuItem(Component.literal("Teleport"), Icons.PLAYER, b -> {
                String dimId = group.dimension().location().toString();
                int surfaceY = getSurfaceY(group);
                int playerHeight = mc.player != null ? (int)Math.ceil(mc.player.getBbHeight()) : 2;
                int teleportY = surfaceY + playerHeight - 1;
                String tpCommand = "/execute in " + dimId + " run tp @s " + blockX + " " + teleportY + " " + blockZ;
                closeGui();
                mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen(tpCommand));
            }));

            ContextMenu menu = new ContextMenu(PlayerClaimListScreen.this, items);
            menu.setPos(getGui().getMouseX(), getGui().getMouseY());
            getGui().openContextMenu(menu);
            activeContextMenu = menu;
        }
    }

    private void navigateToGroup(ClaimGrouper.ChunkGroup group) {
        if (group.getFirstChunk() == null) return;

        // Get center in block coordinates
        int[] centerBlocks = getGroupCenterBlocks(group);

        // Convert block coords to region coords (regions are 512 blocks)
        double regionX = (centerBlocks[0] / 512.0);
        double regionZ = (centerBlocks[1] / 512.0);
        
        // Store target values for use in callbacks
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> targetDimension = group.dimension();
        int blockX = centerBlocks[0];
        int blockZ = centerBlocks[1];

        // Close ALL screens first
        net.minecraft.client.Minecraft.getInstance().setScreen(null);

        // Re-open the map screen fresh, then set dimension and scroll
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            // Open a fresh map screen
            LargeMapScreen.openMap();
            
            // After opening, set dimension and scroll
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                // Screen is wrapped in ScreenWrapper by FTB Library
                net.minecraft.client.gui.screens.Screen currentScreen = net.minecraft.client.Minecraft.getInstance().screen;
                if (currentScreen instanceof dev.ftb.mods.ftblibrary.ui.ScreenWrapper sw) {
                    if (sw.getGui() instanceof LargeMapScreen newMapScreen) {
                        LargeMapScreenAccessor newAccessor = (LargeMapScreenAccessor) newMapScreen;
                        
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
                                int regionGridX = Math.floorDiv(blockX, 512);
                                int regionGridZ = Math.floorDiv(blockZ, 512);
                                dev.ftb.mods.ftbchunks.client.map.MapRegion targetRegion = newDim.getRegion(XZ.of(regionGridX, regionGridZ));
                                targetRegion.getData();
                                targetRegion.getRenderedMapImage();
                            });
                            
                            // Refresh and scroll after dimension change
                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                newMapScreen.refreshWidgets();
                                RegionMapPanel panel = newAccessor.getRegionPanel();
                                panel.resetScroll();
                                panel.scrollTo(regionX, regionZ);
                            });
                        } else {
                            // Same dimension, just scroll
                            RegionMapPanel panel = newAccessor.getRegionPanel();
                            panel.resetScroll();
                            panel.scrollTo(regionX, regionZ);
                        }
                    }
                }
            });
        });
    }

    private int[] getGroupCenterBlocks(ClaimGrouper.ChunkGroup group) {
        int minBlockX = Integer.MAX_VALUE;
        int minBlockZ = Integer.MAX_VALUE;
        int maxBlockX = Integer.MIN_VALUE;
        int maxBlockZ = Integer.MIN_VALUE;

        // Calculate bounding box in block coordinates
        for (XZ chunk : group.chunks()) {
            int chunkStartX = chunk.x() * 16;
            int chunkStartZ = chunk.z() * 16;
            int chunkEndX = chunkStartX + 15;
            int chunkEndZ = chunkStartZ + 15;

            minBlockX = Math.min(minBlockX, chunkStartX);
            minBlockZ = Math.min(minBlockZ, chunkStartZ);
            maxBlockX = Math.max(maxBlockX, chunkEndX);
            maxBlockZ = Math.max(maxBlockZ, chunkEndZ);
        }

        // Return center in block coordinates
        return new int[]{(minBlockX + maxBlockX) / 2, (minBlockZ + maxBlockZ) / 2};
    }

    private int[] getGroupCenter(ClaimGrouper.ChunkGroup group) {
        // Get block coordinates
        int[] blockCoords = getGroupCenterBlocks(group);
        // Return as chunk coordinates for backwards compatibility
        return new int[]{blockCoords[0] / 16, blockCoords[1] / 16};
    }

    /**
     * Get the raw surface Y from the map data.
     * Returns the actual value, or Integer.MIN_VALUE if no data available.
     */
    private int getRawSurfaceY(ClaimGrouper.ChunkGroup group) {
        int[] center = getGroupCenter(group);
        int blockX = center[0] * 16 + 8;  // Center of chunk
        int blockZ = center[1] * 16 + 8;

        return dev.ftb.mods.ftbchunks.client.map.MapManager.getInstance()
                .map(manager -> {
                    dev.ftb.mods.ftbchunks.client.map.MapDimension dim = manager.getDimension(group.dimension());
                    dev.ftb.mods.ftbchunks.client.map.MapRegion region = dim.getRegion(XZ.regionFromBlock(blockX, blockZ));
                    MapRegionData data = region.getData();

                    if (data != null) {
                        int localX = blockX & 511;
                        int localZ = blockZ & 511;
                        return (int) data.height[localX + localZ * 512];
                    }
                    return Integer.MIN_VALUE; // No data available
                })
                .orElse(Integer.MIN_VALUE);
    }
    
    /**
     * Check if a Y value is valid (not void, not unloaded)
     */
    private boolean isValidY(int y) {
        return y > -64 && y < 400 && y != Integer.MIN_VALUE;
    }

    /**
     * Get surface Y for teleport - returns safe height (300) if invalid
     */
    private int getSurfaceY(ClaimGrouper.ChunkGroup group) {
        int y = getRawSurfaceY(group);
        if (!isValidY(y)) {
            return 300; // Safe default for void worlds / unloaded chunks
        }
        return y;
    }
    
    /**
     * Get surface Y string for copy - returns "~" if invalid
     */
    private String getSurfaceYForCopy(ClaimGrouper.ChunkGroup group) {
        int y = getRawSurfaceY(group);
        if (!isValidY(y)) {
            return "~"; // Use player's current Y
        }
        return String.valueOf(y);
    }

    private void copyAllClaimsToClipboard() {
        StringBuilder sb = new StringBuilder();

        // Sort owners: teams first (A-Z), then players (A-Z)
        List<ClaimOwner> sortedOwners = new ArrayList<>(allOwners);
        sortedOwners.sort((a, b) -> {
            // Teams come before players
            if (a.isTeam() && !b.isTeam()) return -1;
            if (!a.isTeam() && b.isTeam()) return 1;
            // Within same type, sort alphabetically
            return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
        });

        for (ClaimOwner owner : sortedOwners) {
            List<ClaimGrouper.ChunkGroup> groups = ClaimGrouper.groupOwnerClaims(owner, groupByTeam);

            if (groups.isEmpty()) continue;

            // Show "Team:" or "Player:" prefix
            if (owner.isTeam()) {
                sb.append("Team: ").append(owner.getDisplayName());
                if (!owner.getMembers().isEmpty()) {
                    sb.append(" ").append(owner.getMemberListString());
                }
                sb.append("\n");
            } else {
                sb.append("Player: ").append(owner.getDisplayName()).append("\n");
            }

            // Group by dimension
            Map<String, List<ClaimGrouper.ChunkGroup>> byDimension = new LinkedHashMap<>();
            for (ClaimGrouper.ChunkGroup group : groups) {
                String dimName = group.dimension().location().getPath();
                // Change the_end to end
                if (dimName.equals("the_end")) {
                    dimName = "end";
                }
                byDimension.computeIfAbsent(dimName, k -> new ArrayList<>()).add(group);
            }

            // Sort dimensions: overworld, nether, end, then alphabetically
            List<String> sortedDims = new ArrayList<>(byDimension.keySet());
            sortedDims.sort((a, b) -> {
                int orderA = getDimensionOrder(a);
                int orderB = getDimensionOrder(b);
                if (orderA != orderB) return Integer.compare(orderA, orderB);
                return a.compareToIgnoreCase(b);
            });

            // Calculate max chunk count digits for padding
            int maxChunkDigits = groups.stream()
                    .mapToInt(ClaimGrouper.ChunkGroup::size)
                    .map(n -> String.valueOf(n).length())
                    .max().orElse(1);

            // Calculate max dimension name length for tab alignment
            int maxDimLength = sortedDims.stream()
                    .mapToInt(dim -> {
                        return String.format("(%s) %s", " ".repeat(maxChunkDigits), dim).length();
                    })
                    .max().orElse(0);

            for (String dimName : sortedDims) {
                List<ClaimGrouper.ChunkGroup> dimGroups = byDimension.get(dimName);
                // Sort by chunk count descending
                dimGroups.sort((a, b) -> Integer.compare(b.size(), a.size()));

                for (ClaimGrouper.ChunkGroup group : dimGroups) {
                    int[] center = getGroupCenter(group);
                    int blockX = center[0] * 16 + 8;
                    int blockZ = center[1] * 16 + 8;
                    String yStr = getSurfaceYForCopy(group);

                    // Pad after parentheses to align dimension names
                    String chunkCount = String.valueOf(group.size());
                    String padding = " ".repeat(maxChunkDigits - chunkCount.length());

                    String prefix = "(" + chunkCount + ") " + padding + dimName;
                    int tabs = (maxDimLength - prefix.length()) / 4 + 1;
                    String tabbing = "\t".repeat(Math.max(1, tabs));

                    sb.append(prefix).append(tabbing).append(blockX).append(" ").append(yStr).append(" ").append(blockZ).append("\n");
                }
            }

            sb.append("\n");
        }

        // Copy to clipboard
        net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(sb.toString().trim());

        // Show confirmation
        net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                Component.literal("Copied all claims to clipboard!").withStyle(net.minecraft.ChatFormatting.GREEN),
                true
        );
    }

    private int getDimensionOrder(String dimName) {
        return switch (dimName) {
            case "overworld" -> 0;
            case "nether", "the_nether" -> 1;
            case "end", "the_end" -> 2;
            default -> 3;
        };
    }

    @Override
    public void onClosed() {
        // Save scroll position
        if (scrollContent != null) {
            persistentScrollPosition = scrollContent.getScrollY();
        }
        super.onClosed();
    }

    @Override
    public Theme getTheme() {
        return parentMapScreen.getTheme();
    }
}

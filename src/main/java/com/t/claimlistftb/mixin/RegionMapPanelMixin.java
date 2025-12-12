package com.t.claimlistftb.mixin;

import com.t.claimlistftb.client.ChunkHighlighter;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel;
import dev.ftb.mods.ftbchunks.client.map.MapDimension;
import dev.ftb.mods.ftblibrary.ui.Panel;
import dev.ftb.mods.ftblibrary.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Mixin to render chunk highlights on the large map
 */
@Mixin(value = RegionMapPanel.class, remap = false)
public abstract class RegionMapPanelMixin {
    
    @Shadow @Final
    LargeMapScreen largeMap;
    
    @Shadow
    int regionMinX;
    
    @Shadow
    int regionMinZ;
    
    /**
     * Inject at the end of draw to add our highlights on top
     */
    @Inject(method = "draw", at = @At("TAIL"))
    private void claimlistftb$drawHighlights(GuiGraphics graphics, Theme theme, int x, int y, int w, int h, CallbackInfo ci) {
        ChunkHighlighter highlighter = ChunkHighlighter.getInstance();
        
        if (!highlighter.hasActiveHighlights()) {
            return;
        }
        
        if (largeMap == null) {
            return;
        }
        
        LargeMapScreenAccessor accessor = (LargeMapScreenAccessor) largeMap;
        MapDimension dimension = accessor.getDimension();
        if (dimension == null) {
            return;
        }
        
        List<ChunkHighlighter.ChunkHighlight> highlights = highlighter.getActiveHighlights(dimension.dimension);
        if (highlights.isEmpty()) {
            return;
        }
        
        // Get the tile size (pixels per region)
        int tileSize = largeMap.getRegionTileSize();
        
        // Pixels per chunk (regions are 32x32 chunks)
        double pixelsPerChunk = tileSize / 32.0;
        
        // Get current scroll - cast to Panel to access inherited methods
        Panel self = (Panel) (Object) this;
        double scrollX = self.getScrollX();
        double scrollY = self.getScrollY();
        
        // Draw each highlight
        for (ChunkHighlighter.ChunkHighlight highlight : highlights) {
            if (!highlight.isVisible()) {
                continue;
            }
            
            // Calculate region position of this chunk
            int regionOfChunkX = highlight.chunkX >> 5; // divide by 32
            int regionOfChunkZ = highlight.chunkZ >> 5;
            
            // Local position within region (0-31)
            int localChunkX = highlight.chunkX & 31;
            int localChunkZ = highlight.chunkZ & 31;
            
            // Calculate pixel position using same logic as alignWidgets()
            // Region pixel position: (regionPos - regionMin) * buttonSize
            double regionPixelX = (regionOfChunkX - regionMinX) * tileSize;
            double regionPixelZ = (regionOfChunkZ - regionMinZ) * tileSize;
            
            // Add local chunk offset within region
            double chunkPixelX = regionPixelX + (localChunkX * pixelsPerChunk);
            double chunkPixelZ = regionPixelZ + (localChunkZ * pixelsPerChunk);
            
            // Apply scroll offset and panel position
            double pixelX = x + chunkPixelX - scrollX;
            double pixelY = y + chunkPixelZ - scrollY;
            
            // Check if visible on screen (with some margin)
            if (pixelX + pixelsPerChunk < x || pixelX > x + w ||
                pixelY + pixelsPerChunk < y || pixelY > y + h) {
                continue;
            }
            
            // Draw the highlight rectangle
            int color = highlight.getColor();
            graphics.fill(
                (int) pixelX, 
                (int) pixelY, 
                (int) (pixelX + pixelsPerChunk), 
                (int) (pixelY + pixelsPerChunk), 
                color
            );
        }
    }
}

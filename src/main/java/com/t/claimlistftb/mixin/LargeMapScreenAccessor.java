package com.t.claimlistftb.mixin;

import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = LargeMapScreen.class, remap = false)
public interface LargeMapScreenAccessor {

    @Accessor("regionPanel")
    RegionMapPanel getRegionPanel();

    @Accessor("dimension")
    dev.ftb.mods.ftbchunks.client.map.MapDimension getDimension();

    @Accessor("dimension")
    void setDimension(dev.ftb.mods.ftbchunks.client.map.MapDimension dimension);
    
    @Accessor("movedToPlayer")
    boolean getMovedToPlayer();
    
    @Accessor("movedToPlayer")
    void setMovedToPlayer(boolean moved);
}

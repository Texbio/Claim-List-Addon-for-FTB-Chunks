package com.t.claimlistftb.mixin;

import com.t.claimlistftb.client.ClaimChangeTracker;
import dev.ftb.mods.ftbchunks.client.FTBChunksClient;
import dev.ftb.mods.ftbchunks.data.ChunkSyncInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.UUID;

@Mixin(value = FTBChunksClient.class, remap = false)
public class FTBChunksClientMixin {

    @Unique
    private static int claimlistftb$tickCounter = 0;

    /**
     * Called when player logs into a server/world.
     * Initialize our tracker with the server ID.
     */
    @Inject(method = "handlePlayerLogin", at = @At("RETURN"))
    private void onPlayerLogin(UUID serverId, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        String address = null;

        if (mc.getCurrentServer() != null) {
            address = mc.getCurrentServer().ip;
        }

        ClaimChangeTracker.getInstance().onServerJoin(serverId, mc.hasSingleplayerServer(), address);
    }

    /**
     * Called when player logs out.
     * Clean up our tracker.
     */
    @Inject(method = "loggedOut", at = @At("HEAD"))
    private void onLoggedOut(@Nullable LocalPlayer player, CallbackInfo ci) {
        ClaimChangeTracker.getInstance().onServerLeave();
    }

    /**
     * Called when the server sends chunk claim updates.
     * This is the KEY hook - we intercept claim changes in real-time!
     */
    @Inject(method = "updateChunksFromServer", at = @At("HEAD"))
    private void onUpdateChunksFromServer(ResourceKey<Level> dimId, UUID teamId, Collection<ChunkSyncInfo> chunkSyncInfoList, CallbackInfo ci) {
        ClaimChangeTracker tracker = ClaimChangeTracker.getInstance();
        
        // Process each chunk update and detect changes
        for (ChunkSyncInfo info : chunkSyncInfoList) {
            tracker.processChunkUpdate(dimId, info.x(), info.z(), info.claimed(), teamId);
        }
    }

    /**
     * Periodic tick - only used to detect when initial sync is complete.
     * Runs once per second (every 20 ticks).
     */
    @Inject(method = "clientTick", at = @At("RETURN"))
    private void onClientTick(Minecraft mc, CallbackInfo ci) {
        claimlistftb$tickCounter++;
        if (claimlistftb$tickCounter >= 20) {
            claimlistftb$tickCounter = 0;
            ClaimChangeTracker.getInstance().tick();
        }
    }
}

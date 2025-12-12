package com.t.claimlistftb.mixin;

import com.t.claimlistftb.client.ChunkHighlighter;
import com.t.claimlistftb.client.gui.PlayerClaimListScreen;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.Button;
import dev.ftb.mods.ftblibrary.ui.SimpleButton;
import dev.ftb.mods.ftblibrary.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LargeMapScreen.class, remap = false)
public class LargeMapScreenMixin {

    @Shadow
    private Button clearDeathpointsButton;

    private SimpleButton playerFinderButton;

    @Inject(method = "addWidgets", at = @At("RETURN"), remap = false)
    private void addPlayerFinderButton(CallbackInfo ci) {
        LargeMapScreen screen = (LargeMapScreen) (Object) this;

        // Create a custom button that crops and scales the player icon
        playerFinderButton = new SimpleButton(
                screen,
                Component.literal("Player Finder"),
                Icons.PLAYER,
                (btn, mouse) -> {
                    new PlayerClaimListScreen(screen).openGui();
                }
        ) {
            @Override
            public void draw(GuiGraphics graphics, Theme theme, int x, int y, int w, int h) {
                // Draw button background
                drawBackground(graphics, theme, x, y, w, h);

                // Crop 4 pixels from each side and expand to fill button
                // Player icon is 16x16 with 4px empty border on all sides
                // Center content is 8x8, we want to expand it by 1.5x to 12x12

                var pose = graphics.pose();
                pose.pushPose();

                // Translate to button position
                pose.translate(x, y, 0);

                // Center the 12x12 scaled result in the 16x16 button
                // 12x12 centered in 16x16 means offset by (16-12)/2 = 2 pixels
                pose.translate(2, 2, 0);

                // Scale by 1.5x to make the 8x8 content become 12x12
                pose.scale(1.5f, 1.5f, 1.0f);

                // Offset by -4 to crop out the 4px border (this happens after scale)
                pose.translate(-4, -4, 0);

                // Draw the icon at scaled position
                Icons.PLAYER.draw(graphics, 0, 0, 16, 16);

                pose.popPose();
            }
        };

        screen.add(playerFinderButton);
    }

    @Inject(method = "alignWidgets", at = @At("RETURN"), remap = false)
    private void alignPlayerFinderButton(CallbackInfo ci) {
        if (playerFinderButton != null) {
            // Take the position where clearDeathpointsButton was (y=73)
            playerFinderButton.setPosAndSize(1, 73, 16, 16);
        }

        if (clearDeathpointsButton != null) {
            // Move clearDeathpointsButton below playerFinderButton
            // 18 pixels below (73 + 18 = 91)
            clearDeathpointsButton.setPosAndSize(1, 91, 16, 16);
        }
    }
    
    @Inject(method = "onClosed", at = @At("HEAD"), remap = false)
    private void onMapScreenClosed(CallbackInfo ci) {
        // Clear any active highlights when the map screen is closed
        ChunkHighlighter.getInstance().clearAll();
    }
}

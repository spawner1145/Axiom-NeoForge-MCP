package dev.axiommcp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Isolated integration-test fixture; inactive during normal launcher use. */
final class TestWorldLauncher {
    private static int stage, ticks;
    static void tick() {
        if (!Boolean.getBoolean("axiom.mcp.testing") || stage == 2) return;
        var client=Minecraft.getInstance();client.options.pauseOnLostFocus=false;
        if(stage==0&&client.screen!=null&&client.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen"))client.setScreen(new TitleScreen());
        if(stage==0&&client.screen instanceof TitleScreen&&client.getOverlay()==null&&++ticks>30) {
            stage=1;
            client.createWorldOpenFlows().createFreshLevel("AxiomMcpTest-"+System.currentTimeMillis(),
                    new LevelSettings("Axiom MCP Integration",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(123456789L,false,false),
                    access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),client.screen);
        }
        if(stage==1&&client.level!=null&&client.player!=null&&com.moulberry.axiom.ClientEvents.processedServerSupportsAxiom) {
            client.setScreen(null);stage=2;AxiomMcp.LOGGER.info("AXIOM_MCP_TEST_WORLD_READY");
        }
    }
}

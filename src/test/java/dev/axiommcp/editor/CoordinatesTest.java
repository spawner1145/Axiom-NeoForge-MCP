package dev.axiommcp.editor;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoordinatesTest {
    @Test void fractionalOrOverflowCoordinatesDoNotSilentlyTruncate() {
        assertThrows(IllegalArgumentException.class, () -> EditorBridge.pos(JsonParser.parseString("{\"pos\":[1.5,2,3]}").getAsJsonObject(),"pos"));
        assertThrows(IllegalArgumentException.class, () -> EditorBridge.pos(JsonParser.parseString("{\"pos\":[2147483648,2,3]}").getAsJsonObject(),"pos"));
        assertEquals(new BlockPos(-5,-59,12),EditorBridge.pos(JsonParser.parseString("{\"pos\":[-5,-59,12]}").getAsJsonObject(),"pos"));
    }
    @Test void InclusiveBoundsWorkForReversedCornersAndRejectExcessiveVolumes() {
        var bounds=BuildApi.Bounds.of(new BlockPos(4,3,2),new BlockPos(2,1,0));
        assertEquals(27,bounds.volume());assertEquals(new BlockPos(2,1,0),bounds.min());
        assertThrows(IllegalArgumentException.class,() -> bounds.checkSize(26));
        assertThrows(ArithmeticException.class,() -> BuildApi.Bounds.of(new BlockPos(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE),new BlockPos(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE)).volume());
    }
}

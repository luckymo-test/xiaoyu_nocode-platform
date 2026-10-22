package com.mofanyi.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualRegressionServiceTest {

    @TempDir
    Path tempDir;

    private final VisualRegressionService service = new VisualRegressionService();

    @Test
    @DisplayName("相同截图变化率为 0，大面积颜色变化会被标出")
    void comparesPixelsDeterministically() throws Exception {
        Path black = tempDir.resolve("black.png");
        Path blackCopy = tempDir.resolve("black-copy.png");
        Path white = tempDir.resolve("white.png");
        write(black, 0x000000);
        write(blackCopy, 0x000000);
        write(white, 0xffffff);

        var same = service.compare(black.toFile(), blackCopy.toFile(), "desktop");
        var changed = service.compare(black.toFile(), white.toFile(), "desktop");

        assertEquals(0, same.changedPixels());
        assertTrue(same.passed());
        assertFalse(changed.passed());
        assertEquals(100, changed.changedPixels());
    }

    private void write(Path path, int rgb) throws Exception {
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 10; x++) {
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }
}

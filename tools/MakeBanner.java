import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the Android TV launcher banner (320x180 dp at xhdpi = 640x360 px).
 * Usage: java tools/MakeBanner.java app/src/main/res/drawable-xhdpi/banner.png
 */
public class MakeBanner {
    public static void main(String[] args) throws Exception {
        int w = 640, h = 360;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        g.setPaint(new GradientPaint(0, 0, new Color(0x1B2C5A), w, h, new Color(0x0E1116)));
        g.fillRect(0, 0, w, h);

        // Logo tile: rounded square with a screen and a play triangle.
        int lx = 48, ly = 108, ls = 144;
        g.setPaint(new GradientPaint(lx, ly, new Color(0x4F8CFF), lx + ls, ly + ls, new Color(0x39C6A5)));
        g.fill(new RoundRectangle2D.Float(lx, ly, ls, ls, 36, 36));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(lx + 26, ly + 40, ls - 52, 58, 8, 8));
        g.setColor(new Color(0x1D3B8F));
        g.fillRect(lx + 32, ly + 46, ls - 64, 46);
        g.setColor(Color.WHITE);
        Path2D play = new Path2D.Float();
        play.moveTo(lx + 64, ly + 55);
        play.lineTo(lx + 64, ly + 83);
        play.lineTo(lx + 86, ly + 69);
        play.closePath();
        g.fill(play);
        g.fillRect(lx + 52, ly + 106, 40, 6);

        g.setColor(new Color(0xEEF1F6));
        g.setFont(new Font("Noto Sans CJK SC", Font.BOLD, 64));
        g.drawString("LumaBeam", 222, 196);
        g.setColor(new Color(0x9AA3B2));
        g.setFont(new Font("Noto Sans CJK SC", Font.PLAIN, 28));
        g.drawString("光束投屏 · Cast & Mirror", 226, 246);
        g.dispose();
        ImageIO.write(img, "png", new File(args[0]));
    }
}

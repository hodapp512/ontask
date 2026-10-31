import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the OnTask logo — a blue-to-teal checkmark followed by an orange chevron ("done, onward") — and writes
 * every icon the apps need. Run from the repo root: java desktop/icons/MakeIcons.java
 * Then: iconutil -c icns desktop/icons/OnTask.iconset -o desktop/icons/OnTask.icns
 */
public class MakeIcons {
    static final Color BLUE = new Color(0x2563EB), TEAL = new Color(0x14B8A6), ORANGE = new Color(0xF97316), AMBER = new Color(0xFBBF24);

    /** The mark in a 100×100 box. */
    static void mark(Graphics2D g, double x, double y, double size) {
        double u = size / 100;
        g.setStroke(new BasicStroke((float) (13 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D check = new Path2D.Double();
        check.moveTo(x + 8 * u, y + 51 * u); check.lineTo(x + 30 * u, y + 73 * u); check.lineTo(x + 59 * u, y + 29 * u);
        g.setPaint(new GradientPaint((float) (x + 8 * u), (float) (y + 73 * u), BLUE, (float) (x + 59 * u), (float) (y + 29 * u), TEAL));
        g.draw(check);
        Path2D chevron = new Path2D.Double();
        chevron.moveTo(x + 75 * u, y + 33 * u); chevron.lineTo(x + 93 * u, y + 51 * u); chevron.lineTo(x + 75 * u, y + 69 * u);
        g.setPaint(new GradientPaint((float) (x + 75 * u), (float) (y + 69 * u), ORANGE, (float) (x + 93 * u), (float) (y + 33 * u), AMBER));
        g.draw(chevron);
    }

    static Graphics2D smooth(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** macOS app icon: white rounded square on Apple's grid (824 of 1024, with room for the shadow). */
    static BufferedImage macIcon(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        double s = px / 1024.0, body = 824 * s, off = 100 * s;
        g.setColor(new Color(0, 0, 0, 40));
        g.fill(new RoundRectangle2D.Double(off, off + 10 * s, body, body, 185 * s, 185 * s));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(off, off, body, body, 185 * s, 185 * s));
        mark(g, off + body * 0.09, off + body * 0.07, body * 0.82);
        g.dispose();
        return img;
    }

    /** Phone / web icon: full-bleed white square (the phone rounds or masks it). */
    static BufferedImage webIcon(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        g.setColor(Color.WHITE); g.fillRect(0, 0, px, px);
        mark(g, px * 0.13, px * 0.11, px * 0.72); // inside the maskable safe zone
        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        File set = new File("desktop/icons/OnTask.iconset"); set.mkdirs();
        for (int base : new int[]{16, 32, 128, 256, 512}) {
            ImageIO.write(macIcon(base), "png", new File(set, "icon_" + base + "x" + base + ".png"));
            ImageIO.write(macIcon(base * 2), "png", new File(set, "icon_" + base + "x" + base + "@2x.png"));
        }
        ImageIO.write(macIcon(512), "png", new File("desktop/src/main/resources/icon.png"));
        ImageIO.write(webIcon(192), "png", new File("web/public/icon-192.png"));
        ImageIO.write(webIcon(512), "png", new File("web/public/icon-512.png"));
        ImageIO.write(webIcon(180), "png", new File("web/public/apple-touch-icon.png"));
        ImageIO.write(webIcon(64), "png", new File("web/public/favicon.png"));
        System.out.println("Icons written.");
    }
}

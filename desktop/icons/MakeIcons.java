import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the OnTask logo — a shield (like BlockMe's) bearing a clock's two hands at a right angle, blue to teal with
 * white hands and hub — and writes every icon the apps need. Run from the repo root: java desktop/icons/MakeIcons.java
 * Then: iconutil -c icns desktop/icons/OnTask.iconset -o desktop/icons/OnTask.icns
 * The menu bar draws the same shapes in TrayGlyph (TrayMenu.kt); keep the two in step.
 */
public class MakeIcons {
    static final Color BLUE = new Color(0x2563EB), TEAL = new Color(0x14B8A6);

    /** The shield outline in a 100×100 box: flat shoulders, straight sides, curving to a point. */
    static Path2D shield(double x, double y, double u) {
        Path2D p = new Path2D.Double();
        p.moveTo(x + 50 * u, y + 4 * u);
        p.quadTo(x + 72 * u, y + 15 * u, x + 95 * u, y + 15 * u);
        p.lineTo(x + 95 * u, y + 44 * u);
        p.curveTo(x + 95 * u, y + 72 * u, x + 74 * u, y + 89 * u, x + 50 * u, y + 97 * u);
        p.curveTo(x + 26 * u, y + 89 * u, x + 5 * u, y + 72 * u, x + 5 * u, y + 44 * u);
        p.lineTo(x + 5 * u, y + 15 * u);
        p.quadTo(x + 28 * u, y + 15 * u, x + 50 * u, y + 4 * u);
        p.closePath();
        return p;
    }

    /** The mark in a 100×100 box. */
    static void mark(Graphics2D g, double x, double y, double size) {
        double u = size / 100;
        g.setPaint(new GradientPaint((float) (x + 5 * u), (float) (y + 4 * u), BLUE, (float) (x + 95 * u), (float) (y + 97 * u), TEAL));
        g.fill(shield(x, y, u));
        // Clock hands from the centre: the long one to 12, the short one to 3.
        double cx = x + 50 * u, cy = y + 55 * u;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (7 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D hands = new Path2D.Double();
        hands.moveTo(cx, y + 31 * u); hands.lineTo(cx, cy); hands.lineTo(x + 70 * u, cy);
        g.draw(hands);
        double r = 4.5 * u;
        g.setColor(Color.WHITE); // the hands' hub, in the hands' color
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
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
        mark(g, off + body * 0.1, off + body * 0.1, body * 0.8);
        g.dispose();
        return img;
    }

    /** Phone / web icon: full-bleed white square (the phone rounds or masks it). */
    static BufferedImage webIcon(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        g.setColor(Color.WHITE); g.fillRect(0, 0, px, px);
        mark(g, px * 0.15, px * 0.15, px * 0.7); // inside the maskable safe zone
        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true"); // draw off-screen; don't appear as an app ("SourceLauncher")
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

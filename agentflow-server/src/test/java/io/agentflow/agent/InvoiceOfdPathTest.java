package io.agentflow.agent;

import java.awt.Color;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 用解析几何和实际像素核对圆弧及子路径，避免图片已生成却丢掉图元。
 * @author owlzhangfq@gmail.com
 */
class InvoiceOfdPathTest {
    @Test
    void preservesEveryCommandAndRestoresTheSubpathOriginAfterClose() throws Exception {
        Path2D.Double path = parse(" S +0 0\nL 4 0 Q 6 0 6 2 B 6 4 4 4 4 6 C M 1e1 10 L 11 11 ");
        assertThat(types(path)).containsExactly(PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO,
                PathIterator.SEG_QUADTO, PathIterator.SEG_CUBICTO, PathIterator.SEG_CLOSE,
                PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO);
        assertThat(path.getCurrentPoint()).isEqualTo(new Point2D.Double(11, 11));
        Path2D.Double closed = parse("M 3 4 L 6 8 C");
        assertThat(closed.getCurrentPoint()).isEqualTo(new Point2D.Double(3, 4));
        assertThat(parse("M 3 4 L 6 8 C A 5 5 0 0 1 8 9").getCurrentPoint()).isEqualTo(new Point2D.Double(8, 9));
    }

    @Test
    void distinguishesSmallAndLargeArcsInBothDirections() throws Exception {
        double root = Math.sqrt(50);
        assertArcMiddle("M 10 0 A 10 10 0 0 1 0 10", root, root);
        assertArcMiddle("M 10 0 A 10 10 0 0 0 0 10", 10 - root, 10 - root);
        assertArcMiddle("M 10 0 A 10 10 0 1 0 0 10", -root, -root);
        assertArcMiddle("M 10 0 A 10 10 0 1 1 0 10", 10 + root, 10 + root);
    }

    @Test
    void rotatedEllipsesFollowIndependentCenterBasedGeometry() throws Exception {
        for (double rotation : new double[]{0, 30, 90, -45, 450}) {
            for (double sweep : new double[]{-270, -90, 90, 270}) {
                Point2D start = ellipsePoint(rotation, 20), end = ellipsePoint(rotation, 20 + sweep);
                String data = String.format(Locale.ROOT, "M %.12f %.12f A 20 7 %.12f %d %d %.12f %.12f",
                        start.getX(), start.getY(), rotation, Math.abs(sweep) > 180 ? 1 : 0,
                        sweep > 0 ? 1 : 0, end.getX(), end.getY());
                var path = parse(data);
                for (int i = 1; i < 20; i++) {
                    Point2D expected = ellipsePoint(rotation, 20 + sweep * i / 20);
                    assertThat(distance(path, expected.getX(), expected.getY())).as(data).isLessThan(.002);
                }
            }
        }
    }

    @Test
    void handlesZeroNegativeAndUndersizedRadiiWithoutDroppingTheEndpoint() throws Exception {
        assertThat(types(parse("M 0 0 A 0 8 0 0 1 4 0"))).containsExactly(PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO);
        assertArcMiddle("M 10 0 A -10 -10 0 0 1 0 10", Math.sqrt(50), Math.sqrt(50));
        var corrected = parse("M 0 0 A 1 1 0 0 1 4 0");
        assertThat(distance(corrected, 2, -2)).isLessThan(.002);
        assertThat(corrected.getCurrentPoint()).isEqualTo(new Point2D.Double(4, 0));
    }

    @Test
    void actualDrawingContainsTheArcAndHonorsBothFillRules() throws Exception {
        String ring = "M 10 10 L 90 10 L 90 90 L 10 90 C M 30 30 L 70 30 L 70 70 L 30 70 C";
        assertThat(pixel(InvoiceOfdPath.parse(ring, false), 50, 50)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(InvoiceOfdPath.parse(ring, true), 50, 50)).isEqualTo(Color.WHITE.getRGB());
        var circle = parse("M 80 50 A 30 30 0 0 1 20 50 A 30 30 0 0 1 80 50 C");
        assertThat(pixel(circle, 50, 22)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(circle, 50, 78)).isEqualTo(Color.BLACK.getRGB());
        assertThat(pixel(circle, 50, 18)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test
    void refusesInvalidTokensMissingParametersUnknownCommandsAndAmbiguousArcs() {
        for (String data : new String[]{null, "", "   ", "L 1 2", "C", "M 0 0 L 1", "M 0 0 1 1",
                "M 0 0 Q 1 2 3 4 5 6", "M 0 0 A 1 1 0 2 0 2 2", "M 0 0 A 1 1 0 0 -1 2 2",
                "M 0 0 A 1 1 0 0 1 0 0", "M 0 0 C 9", "M 0 0 Z", "M 0 0 CM 1 1",
                "M NaN 0", "M Infinity 0", "M 0x1p2 0", "M 1f 0", "M 1e999 0", "M 0 0 ; L 1 1"}) {
            assertThatThrownBy(() -> parse(data)).as(String.valueOf(data)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void boundsInputCommandsAndNumericMagnitudeBeforeReturningAnyPath() throws Exception {
        assertThatThrownBy(() -> parse(" ".repeat(1_048_577))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse("M 0 0 " + "L 1 1 ".repeat(25_000))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse("M 1000001 0")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> parse("M 0." + "0".repeat(70) + "1 0")).isInstanceOf(IOException.class);
        assertThat(parse("M -1000000 1000000").getCurrentPoint()).isEqualTo(new Point2D.Double(-1_000_000, 1_000_000));
    }

    private static Path2D.Double parse(String data) throws IOException { return InvoiceOfdPath.parse(data, false); }

    private static void assertArcMiddle(String data, double x, double y) throws IOException {
        var path = parse(data);
        assertThat(types(path)).contains(PathIterator.SEG_CUBICTO);
        assertThat(distance(path, x, y)).as(data).isLessThan(.002);
        assertThat(path.getCurrentPoint()).isEqualTo(new Point2D.Double(0, 10));
    }

    private static Point2D ellipsePoint(double rotation, double angle) {
        double phi = Math.toRadians(rotation), theta = Math.toRadians(angle);
        return new Point2D.Double(30 + 20 * Math.cos(theta) * Math.cos(phi) - 7 * Math.sin(theta) * Math.sin(phi),
                40 + 20 * Math.cos(theta) * Math.sin(phi) + 7 * Math.sin(theta) * Math.cos(phi));
    }

    private static double distance(Path2D path, double x, double y) {
        double shortest = Double.POSITIVE_INFINITY, previousX = 0, previousY = 0;
        double[] coordinates = new double[6];
        for (var iterator = path.getPathIterator(null, .0001); !iterator.isDone(); iterator.next()) {
            int type = iterator.currentSegment(coordinates);
            if (type == PathIterator.SEG_LINETO) shortest = Math.min(shortest, Line2D.ptSegDist(previousX, previousY, coordinates[0], coordinates[1], x, y));
            previousX = coordinates[0]; previousY = coordinates[1];
        }
        return shortest;
    }

    private static List<Integer> types(Path2D path) {
        var types = new ArrayList<Integer>(); var coordinates = new double[6];
        for (var iterator = path.getPathIterator(null); !iterator.isDone(); iterator.next()) types.add(iterator.currentSegment(coordinates));
        return types;
    }

    private static int pixel(Path2D path, int x, int y) {
        var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 100, 100); graphics.setColor(Color.BLACK); graphics.fill(path); return image.getRGB(x, y); }
        finally { graphics.dispose(); image.flush(); }
    }
}

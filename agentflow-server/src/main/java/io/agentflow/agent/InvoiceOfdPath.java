package io.agentflow.agent;

import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * OFD 紧缩路径转成可绘制轮廓，未知指令和不完整数据必须使整段失败。
 * @author owlzhangfq@gmail.com
 */
final class InvoiceOfdPath {
    private static final int MAX_CHARACTERS = 1_048_576;
    private static final int MAX_COMMANDS = 25_000;
    private static final int MAX_TOKEN_CHARACTERS = 64;
    private static final double MAX_MAGNITUDE = 1_000_000;
    private static final double FULL_TURN = Math.PI * 2;
    private static final double MAX_ARC_SEGMENT = Math.PI / 4;
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private InvoiceOfdPath() { }

    /** 读取完整路径，显式保留填充规则；不得返回忽略坏指令的部分轮廓。 */
    static Path2D.Double parse(String data, boolean evenOdd) throws IOException {
        if (data == null || data.length() > MAX_CHARACTERS) throw invalid();
        var input = new Tokens(data);
        var path = new Path2D.Double(evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
        int commands = 0;
        while (input.hasNext()) {
            if (++commands > MAX_COMMANDS) throw invalid();
            String command = input.next();
            if (path.getCurrentPoint() == null && !command.equals("S") && !command.equals("M")) throw invalid();
            switch (command) {
                case "S", "M" -> path.moveTo(input.number(), input.number());
                case "L" -> path.lineTo(input.number(), input.number());
                case "Q" -> path.quadTo(input.number(), input.number(), input.number(), input.number());
                case "B" -> path.curveTo(input.number(), input.number(), input.number(), input.number(), input.number(), input.number());
                case "A" -> arc(path, input.number(), input.number(), input.number(), input.flag(), input.flag(), input.number(), input.number());
                case "C" -> path.closePath();
                default -> throw invalid();
            }
        }
        if (commands == 0) throw invalid();
        return path;
    }

    /**
     * 端点参数转椭圆中心后分成不超过 45 度的三次曲线；屏幕坐标下正角度为顺时针。
     * 数学依据：https://www.w3.org/TR/SVG/implnote.html#ArcImplementationNotes 。
     * OFD 要求起止点不同，因此不能沿用 SVG 的同点省略规则。
     */
    private static void arc(Path2D.Double path, double radiusX, double radiusY, double degrees,
                            boolean large, boolean clockwise, double endX, double endY) throws IOException {
        Point2D start = path.getCurrentPoint();
        if (start.getX() == endX && start.getY() == endY) throw invalid();
        double rx = Math.abs(radiusX), ry = Math.abs(radiusY);
        if (rx == 0 || ry == 0) { path.lineTo(endX, endY); return; }
        double phi = Math.toRadians(degrees % 360), cos = Math.cos(phi), sin = Math.sin(phi);
        double halfX = (start.getX() - endX) / 2, halfY = (start.getY() - endY) / 2;
        double localX = cos * halfX + sin * halfY, localY = -sin * halfX + cos * halfY;
        double distance = Math.hypot(localX / rx, localY / ry);
        if (!Double.isFinite(distance) || distance == 0) throw invalid();
        // 两端点相距过远时按同一比例扩大两轴；不把无解圆弧当作直线。
        if (distance > 1) { rx *= distance; ry *= distance; bounded(rx); bounded(ry); }
        double unitX = localX / rx, unitY = localY / ry;
        double squared = unitX * unitX + unitY * unitY;
        double factor = (large == clockwise ? -1 : 1) * Math.sqrt(Math.max(0, 1 - squared) / squared);
        double centerX = factor * rx * unitY, centerY = -factor * ry * unitX;
        double worldX = cos * centerX - sin * centerY + (start.getX() + endX) / 2;
        double worldY = sin * centerX + cos * centerY + (start.getY() + endY) / 2;
        bounded(worldX); bounded(worldY);
        double first = Math.atan2((localY - centerY) / ry, (localX - centerX) / rx);
        double last = Math.atan2((-localY - centerY) / ry, (-localX - centerX) / rx);
        double angle = last - first;
        if (clockwise && angle < 0) angle += FULL_TURN;
        if (!clockwise && angle > 0) angle -= FULL_TURN;
        if (!Double.isFinite(angle) || angle == 0) throw invalid();
        int segments = (int) Math.ceil(Math.abs(angle) / MAX_ARC_SEGMENT);
        double step = angle / segments, tangent = 4.0 / 3 * Math.tan(step / 4);
        var ellipse = new AffineTransform(rx * cos, rx * sin, -ry * sin, ry * cos, worldX, worldY);
        for (int i = 0; i < segments; i++) {
            double from = first + i * step, to = first + (i + 1) * step;
            Point2D control1 = point(ellipse, Math.cos(from) - tangent * Math.sin(from), Math.sin(from) + tangent * Math.cos(from));
            Point2D control2 = point(ellipse, Math.cos(to) + tangent * Math.sin(to), Math.sin(to) - tangent * Math.cos(to));
            Point2D end = point(ellipse, Math.cos(to), Math.sin(to));
            // 最后一段使用原始终点，避免累计浮点误差造成下一条线或闭合边出现缝隙。
            path.curveTo(control1.getX(), control1.getY(), control2.getX(), control2.getY(),
                    i == segments - 1 ? endX : end.getX(), i == segments - 1 ? endY : end.getY());
        }
    }

    private static Point2D point(AffineTransform transform, double x, double y) throws IOException {
        Point2D result = transform.transform(new Point2D.Double(x, y), null);
        bounded(result.getX()); bounded(result.getY());
        return result;
    }

    private static void bounded(double value) throws IOException {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_MAGNITUDE) throw invalid();
    }

    private static IOException invalid() { return new IOException("OFD path is invalid, unsupported or exceeds rendering limits"); }

    /**
     * 顺序读取空白分隔的紧缩数据，不构造无界 token 列表，也不补齐或忽略参数。
     * @author owlzhangfq@gmail.com
     */
    private static final class Tokens {
        private final String data;
        private int position;
        private Tokens(String data) { this.data = data; }

        private boolean hasNext() {
            while (position < data.length() && Character.isWhitespace(data.charAt(position))) position++;
            return position < data.length();
        }

        private String next() throws IOException {
            if (!hasNext()) throw invalid();
            int start = position;
            while (position < data.length() && !Character.isWhitespace(data.charAt(position))) {
                if (++position - start > MAX_TOKEN_CHARACTERS) throw invalid();
            }
            return data.substring(start, position);
        }

        private double number() throws IOException {
            String token = next();
            if (!DECIMAL.matcher(token).matches()) throw invalid();
            double value = Double.parseDouble(token); bounded(value);
            return value;
        }

        private boolean flag() throws IOException {
            double value = number();
            if (value != 0 && value != 1) throw invalid();
            return value == 1;
        }
    }
}

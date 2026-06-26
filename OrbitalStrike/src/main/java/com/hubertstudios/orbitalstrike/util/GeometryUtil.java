package com.hubertstudios.orbitalstrike.util;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure math helpers for distributing impact points - no Bukkit world state is
 * touched here, which keeps this class trivially unit-testable and safe to
 * call from any thread (relevant under Folia, where region threads must not
 * cross into other regions' entity/world state without the scheduler).
 */
public final class GeometryUtil {

    private GeometryUtil() {
    }

    /**
     * Even grid of points across a disc of the given radius, centered at the origin
     * in the XZ plane (Y left at 0; callers add height separately). Points whose
     * planar distance exceeds {@code radius} are skipped, so the result is a
     * roughly circular, evenly-spaced point cloud rather than a square grid -
     * this is what gives the wither dome full, gapless, non-random coverage.
     */
    public static List<Vector> evenDiscGrid(int rows, int columns, double radius) {
        List<Vector> points = new ArrayList<>(rows * columns);
        if (rows <= 0 || columns <= 0 || radius <= 0) {
            points.add(new Vector(0, 0, 0));
            return points;
        }
        double stepX = (radius * 2.0) / Math.max(1, columns - 1 == 0 ? 1 : columns - 1);
        double stepZ = (radius * 2.0) / Math.max(1, rows - 1 == 0 ? 1 : rows - 1);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                double x = (columns == 1) ? 0 : (-radius + c * stepX);
                double z = (rows == 1) ? 0 : (-radius + r * stepZ);
                if (Math.sqrt(x * x + z * z) <= radius) {
                    points.add(new Vector(x, 0, z));
                }
            }
        }
        if (points.isEmpty()) {
            points.add(new Vector(0, 0, 0));
        }
        return points;
    }

    /**
     * Projects a flat disc point cloud onto a half-sphere (dome) of the given
     * height, so skulls visually arc above the impact plane rather than sitting
     * on a flat disc. Points farther from center are raised less; the center
     * point sits at the apex.
     */
    public static List<Vector> projectToDome(List<Vector> flatDiscPoints, double radius, double domeHeight) {
        List<Vector> result = new ArrayList<>(flatDiscPoints.size());
        for (Vector p : flatDiscPoints) {
            double dist = Math.sqrt(p.getX() * p.getX() + p.getZ() * p.getZ());
            double normalized = radius <= 0 ? 0 : Math.min(1.0, dist / radius);
            // Simple dome profile: height falls off as cos curve from apex to rim.
            double y = domeHeight * Math.cos(normalized * (Math.PI / 2.0));
            result.add(new Vector(p.getX(), y, p.getZ()));
        }
        return result;
    }

    public static List<Vector> ring(int count, double radius) {
        List<Vector> points = new ArrayList<>(count);
        if (count <= 1) {
            points.add(new Vector(0, 0, 0));
            return points;
        }
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI * i) / count;
            points.add(new Vector(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        }
        return points;
    }

    public static List<Vector> grid(int count, double spacing) {
        List<Vector> points = new ArrayList<>(count);
        int side = (int) Math.ceil(Math.sqrt(count));
        double offset = (side - 1) * spacing / 2.0;
        int placed = 0;
        for (int r = 0; r < side && placed < count; r++) {
            for (int c = 0; c < side && placed < count; c++) {
                points.add(new Vector(c * spacing - offset, 0, r * spacing - offset));
                placed++;
            }
        }
        return points;
    }

    public static List<Vector> line(int count, double spacing) {
        List<Vector> points = new ArrayList<>(count);
        double offset = (count - 1) * spacing / 2.0;
        for (int i = 0; i < count; i++) {
            points.add(new Vector(i * spacing - offset, 0, 0));
        }
        return points;
    }

    public static List<Vector> random(int count, double radius) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Vector> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = rnd.nextDouble(0, Math.PI * 2);
            double r = rnd.nextDouble(0, radius);
            points.add(new Vector(Math.cos(angle) * r, 0, Math.sin(angle) * r));
        }
        return points;
    }

    /** Random point uniformly inside a disc of the given radius, centered at origin. */
    public static Vector randomInDisc(double radius) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble(0, Math.PI * 2);
        double r = radius * Math.sqrt(rnd.nextDouble());
        return new Vector(Math.cos(angle) * r, 0, Math.sin(angle) * r);
    }

    /** Random point uniformly within an annulus (ring) between minRadius and maxRadius. */
    public static Vector randomInAnnulus(double minRadius, double maxRadius) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble(0, Math.PI * 2);
        double r = Math.sqrt(rnd.nextDouble(minRadius * minRadius, maxRadius * maxRadius));
        return new Vector(Math.cos(angle) * r, 0, Math.sin(angle) * r);
    }
}

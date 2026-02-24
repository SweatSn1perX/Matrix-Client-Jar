package net.matrix.utils;

public class Color {
    public int r, g, b, a;

    public Color(int r, int g, int b, int a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    public Color(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public int getPacked() {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static Color fromHex(String hex) {
        if (hex.startsWith("#"))
            hex = hex.substring(1);
        try {
            if (hex.length() == 6) {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                return new Color(r, g, b);
            } else if (hex.length() == 8) {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                int a = Integer.parseInt(hex.substring(6, 8), 16);
                return new Color(r, g, b, a);
            }
        } catch (Exception ignored) {
        }
        return new Color(255, 255, 255); // Fallback white
    }

    public static int hexToPacked(String hex) {
        return fromHex(hex).getPacked();
    }
}

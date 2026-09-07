package meridian.worldmap;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import meridian.core.api.MapTint;
import meridian.core.api.MapTints;

/**
 * What every layer says about a column, resolved into one colour, and that colour laid onto a
 * tile.
 *
 * <p>Two jobs and a rule between them. The layers are other modules' business - what they know
 * and when it changes; this is only the arithmetic of putting several opinions together, and it
 * has to be arithmetic rather than an order of precedence, or the map would look different
 * depending on which module loaded first.
 *
 * <h2>The arithmetic</h2>
 *
 * <p>A colour here is a direction to pull the ground in, not paint to cover it with. Hue and
 * saturation say where to pull it, brightness says whether to dim or lift it, alpha says how far.
 *
 * <p>Shared states are added <b>on the circle of hues</b> - each one a vector at its own angle,
 * as long as its saturation and its strength. Two reds make a stronger red; a red and a blue make
 * a purple; two opposite hues cancel into grey, which is the honest answer for a column two
 * layers disagree about. Adding them as plain numbers instead would send every mixture towards
 * the middle, and two bright facts would come out as one dull nothing.
 *
 * <p>Strengths compound rather than add - {@code 1 - (1-a)(1-b)} - so a column that four layers
 * have an opinion about is strongly coloured but never solid. Sums and products both commute, so
 * the answer does not depend on the order the layers are in.
 */
final class Tints implements MapTints {

    /** Below this, a state is not really a colour: it drains the ground rather than dyeing it. */
    private static final double COLOURED = 0.15;

    /** Two meanings closer than this on the circle cannot be told apart on a map. */
    private static final double TOO_CLOSE_DEGREES = 25;

    private final List<MapTint> layers = new CopyOnWriteArrayList<>();
    /** Told when a column changes, and when everything does. */
    private final BiConsumer<UUID, Long> onColumn;
    private final Consumer<UUID> onEverything;
    private final Consumer<String> say;

    Tints(BiConsumer<UUID, Long> onColumn, Consumer<UUID> onEverything, Consumer<String> say) {
        this.onColumn = onColumn;
        this.onEverything = onEverything;
        this.say = say;
    }

    /** An empty set of layers, for the window opened on a file with no proxy behind it. */
    static Tints none() {
        return new Tints((world, key) -> { }, world -> { }, message -> { });
    }

    @Override
    public AutoCloseable add(MapTint tint) {
        if (tint == null) {
            return () -> { };
        }
        warnAboutClashes(tint);
        layers.add(tint);
        say.accept("layer added: " + tint.name() + " (" + tint.states().size() + " state(s))");
        return () -> {
            layers.remove(tint);
            say.accept("layer removed: " + tint.name());
        };
    }

    @Override
    public void changed(UUID world, int chunkX, int chunkZ) {
        onColumn.accept(world, WorldMapStore.pack(chunkX, chunkZ));
    }

    @Override
    public void changedAll(UUID world) {
        onEverything.accept(world);
    }

    /** The layers, for the window's legend. */
    List<MapTint> layers() {
        return layers;
    }

    boolean any() {
        return !layers.isEmpty();
    }

    // ------------------------------------------------------------------
    // Working out the colour
    // ------------------------------------------------------------------

    /**
     * Everything the layers say about one column, as one colour: {@code 0} for "leave it alone",
     * otherwise ARGB whose alpha is how far to pull the ground towards it.
     */
    int resolve(UUID world, int chunkX, int chunkZ) {
        if (layers.isEmpty()) {
            return 0;
        }
        MapTint.Paint single = null;
        double singleWeight = 0;
        // The shared sum: hue as a vector, brightness and strength as running totals.
        double x = 0;
        double y = 0;
        double light = 0;
        double weightSum = 0;
        double clear = 1;                   // what is left uncoloured: the product of (1 - each)
        for (MapTint layer : layers) {
            MapTint.Paint paint = stateOf(layer, world, chunkX, chunkZ);
            if (paint == null) {
                continue;
            }
            double strength = ((paint.argb() >>> 24) & 0xFF) / 255.0;
            if (strength <= 0) {
                continue;
            }
            if (paint.mode() == MapTint.Paint.Mode.SINGLE) {
                if (single == null || paint.weight() > singleWeight) {
                    single = paint;
                    singleWeight = paint.weight();
                }
                continue;
            }
            double share = strength * Math.max(0, paint.weight());
            double hue = hueOf(paint.argb());
            double saturation = saturationOf(paint.argb());
            x += share * saturation * Math.cos(hue);
            y += share * saturation * Math.sin(hue);
            light += share * lightnessOf(paint.argb());
            weightSum += share;
            clear *= 1 - Math.min(1, share);
        }
        if (single != null) {
            return single.argb();           // it does not mix, and it does not share
        }
        if (weightSum <= 0) {
            return 0;
        }
        double strength = 1 - clear;
        double saturation = Math.min(1, Math.hypot(x, y) / weightSum);
        double hue = Math.atan2(y, x);
        return (clamp(strength * 255) << 24) | hsl(hue, saturation, light / weightSum);
    }

    private static MapTint.Paint stateOf(MapTint layer, UUID world, int chunkX, int chunkZ) {
        try {
            List<MapTint.Paint> states = layer.states();
            int state = layer.stateOf(world, chunkX, chunkZ);
            return state < 0 || state >= states.size() ? null : states.get(state);
        } catch (RuntimeException e) {
            return null;                    // a layer that throws is not a layer, this frame
        }
    }

    /**
     * A tile with a colour pulled through it.
     *
     * <p>Done once, when the tile is built, because doing it on every repaint would cost the
     * whole map on every pan. Three things happen to each pixel, and which of them show depends
     * on the colour: it is dragged towards the tint's hue at its own brightness (so the shape of
     * the land survives), the drag is a drain rather than a dye where the tint has no colour of
     * its own, and the whole thing is dimmed or lifted by how dark the tint is.
     */
    static BufferedImage paint(BufferedImage source, int argb) {
        int w = source.getWidth();
        int h = source.getHeight();
        int[] pixels = source.getRGB(0, 0, w, h, null, 0, w);
        double strength = ((argb >>> 24) & 0xFF) / 255.0;
        double saturation = saturationOf(argb);
        double hue = hueOf(argb);
        // 0x80 is neutral: darker dims the ground, lighter lifts it.
        double lift = 0.5 + lightnessOf(argb);
        double cos = Math.cos(hue);
        double sin = Math.sin(hue);
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int grey = (r * 30 + g * 59 + b * 11) / 100;
            // Where the tint is heading, at this pixel's own brightness.
            int tr = grey;
            int tg = grey;
            int tb = grey;
            if (saturation > 0) {
                int[] toned = tone(grey, saturation, cos, sin);
                tr = toned[0];
                tg = toned[1];
                tb = toned[2];
            }
            r = (int) ((r + (tr - r) * strength) * lift);
            g = (int) ((g + (tg - g) * strength) * lift);
            b = (int) ((b + (tb - b) * strength) * lift);
            pixels[i] = (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

    /** A colour of this hue and saturation, as bright as the grey it is standing in for. */
    private static int[] tone(int grey, double saturation, double cos, double sin) {
        // The hue circle as two axes across the greys, which is enough for a tint and costs no
        // conversion to and from a colour space nobody else here speaks.
        double chroma = saturation * grey;
        double r = grey + chroma * cos;
        double g = grey + chroma * (-0.5 * cos + 0.866 * sin);
        double b = grey + chroma * (-0.5 * cos - 0.866 * sin);
        return new int[] { clamp(r), clamp(g), clamp(b) };
    }

    // ------------------------------------------------------------------
    // Colours in and out
    // ------------------------------------------------------------------

    /** Where this colour sits on the circle, in radians. */
    private static double hueOf(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        double x = r - 0.5 * g - 0.5 * b;
        double y = 0.866 * g - 0.866 * b;
        return Math.atan2(y, x);
    }

    /** How colourful it is, 0 (a grey) to 1 (as colourful as it gets). */
    private static double saturationOf(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max == 0 ? 0 : (max - min) / (double) max;
    }

    /**
     * How light it is, 0 to 1 - what decides whether the ground is dimmed or lifted.
     *
     * <p>By what the eye reads as brightness, not by the brightest channel: a saturated red is a
     * middling brightness and has to count as one, or every strong colour would come out as an
     * instruction to make the ground half again as bright.
     */
    private static double lightnessOf(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 30 + g * 59 + b * 11) / 25500.0;
    }

    /** Back to a colour: the hue at that saturation, as light as asked. */
    private static int hsl(double hue, double saturation, double lightness) {
        int grey = clamp(lightness * 255);
        int[] toned = tone(grey, saturation, Math.cos(hue), Math.sin(hue));
        return (toned[0] << 16) | (toned[1] << 8) | toned[2];
    }

    private static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, value));
    }

    // ------------------------------------------------------------------
    // Keeping the meanings apart
    // ------------------------------------------------------------------

    /**
     * Says in the log when a new layer's colours cannot be told from what is already there.
     *
     * <p>Two meanings at the same hue are one meaning as far as anybody looking can tell. Worse
     * is a meaning sitting where two others' mixture lands: that square is then either the one
     * fact or the other two together, and nothing distinguishes them. Both are worth knowing
     * about and neither is worth refusing a layer over - a map with a badly chosen colour is
     * still a map, and only the person who wrote the layer can fix it.
     */
    private void warnAboutClashes(MapTint tint) {
        List<Double> taken = new ArrayList<>();
        for (MapTint layer : layers) {
            for (MapTint.Paint paint : layer.states()) {
                if (saturationOf(paint.argb()) >= COLOURED) {
                    taken.add(Math.toDegrees(hueOf(paint.argb())));
                }
            }
        }
        List<Double> mixtures = new ArrayList<>();
        for (int i = 0; i < taken.size(); i++) {
            for (int j = i + 1; j < taken.size(); j++) {
                mixtures.add(midpoint(taken.get(i), taken.get(j)));
            }
        }
        for (MapTint.Paint paint : tint.states()) {
            if (saturationOf(paint.argb()) < COLOURED) {
                continue;                   // a grey claims no place on the circle
            }
            double hue = Math.toDegrees(hueOf(paint.argb()));
            for (double other : taken) {
                if (apart(hue, other) < TOO_CLOSE_DEGREES) {
                    say.accept("layer " + tint.name() + ": \"" + paint.label() + "\" is the same"
                            + " colour as something already on the map - they will read as one");
                    break;
                }
            }
            for (double mixture : mixtures) {
                if (apart(hue, mixture) < TOO_CLOSE_DEGREES) {
                    say.accept("layer " + tint.name() + ": \"" + paint.label() + "\" sits where"
                            + " two other meanings mix - a square of it will be ambiguous");
                    break;
                }
            }
        }
    }

    /** The hue halfway between two, the short way round. */
    private static double midpoint(double a, double b) {
        double x = Math.cos(Math.toRadians(a)) + Math.cos(Math.toRadians(b));
        double y = Math.sin(Math.toRadians(a)) + Math.sin(Math.toRadians(b));
        return Math.toDegrees(Math.atan2(y, x));
    }

    /** How far apart two hues are, in degrees, the short way round. */
    private static double apart(double a, double b) {
        double difference = Math.abs(a - b) % 360;
        return difference > 180 ? 360 - difference : difference;
    }
}

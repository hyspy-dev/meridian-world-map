package meridian.worldmap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Markers read out of a file, for the window opened on its own.
 *
 * <p>Deliberately knows nothing about the proxy: the jar has to open with a double-click, and at
 * that moment there is no core, no server and no module - only whatever files somebody drops on
 * the window. So this reads markers the way a person would recognise them, and takes every shape
 * they actually come in:
 *
 * <ul>
 *   <li>the game's own {@code SharedUserMapMarkers.json}, an object with a {@code UserMarkers}
 *       list, which is what a downloaded world carries;</li>
 *   <li>the kept archive, which files its markers under the world they belong to;</li>
 *   <li>a plain list, for anything simpler.</li>
 * </ul>
 *
 * <p>Both spellings of every field are accepted, because the two files disagree about capitals
 * and neither of them is wrong.
 */
final class Places {

    /** What a marker is drawn in when its file says nothing: the map's own yellow. */
    private static final int DEFAULT_COLOUR = 0xFFD24D;

    private Places() {
    }

    /** Every marker in a file, or an empty list when it holds none we can read. */
    static List<Place> read(Path file) throws IOException {
        JsonElement root = JsonParser.parseString(Files.readString(file));
        List<Place> out = new ArrayList<>();
        for (JsonArray list : lists(root)) {
            for (JsonElement element : list) {
                if (element != null && element.isJsonObject()) {
                    Place place = one(element.getAsJsonObject());
                    if (place != null) {
                        out.add(place);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------

    /**
     * The lists of markers in a file - one, usually, and one per world in a kept archive.
     *
     * <p>Which world each came from is not carried through: this is a window over one map, and a
     * person dropping a file on it is asking to see what is in the file.
     */
    private static List<JsonArray> lists(JsonElement root) {
        List<JsonArray> out = new ArrayList<>();
        if (root.isJsonArray()) {
            out.add(root.getAsJsonArray());
            return out;
        }
        if (!root.isJsonObject()) {
            return out;
        }
        JsonObject object = root.getAsJsonObject();
        for (String field : new String[] { "UserMarkers", "markers", "Markers" }) {
            JsonElement found = object.get(field);
            if (found != null && found.isJsonArray()) {
                out.add(found.getAsJsonArray());
            }
        }
        JsonElement worlds = object.get("worlds");
        if (worlds != null && worlds.isJsonObject()) {
            for (var world : worlds.getAsJsonObject().entrySet()) {
                if (world.getValue() != null && world.getValue().isJsonObject()) {
                    out.addAll(lists(world.getValue()));
                }
            }
        }
        return out;
    }

    private static Place one(JsonObject marker) {
        Double x = number(marker, "X", "x");
        Double z = number(marker, "Z", "z");
        if (x == null || z == null) {
            return null;                    // a marker without a place on the map is not one
        }
        String name = string(marker, "Name", "name");
        String icon = string(marker, "Icon", "icon", "markerImage");
        if (name == null || name.isBlank()) {
            name = string(marker, "Id", "id");
        }
        String kind = string(marker, "Category", "category");
        boolean person = kind != null && kind.toUpperCase(java.util.Locale.ROOT).contains("PLAYER");
        Boolean online = bool(marker, "Online", "online");
        return new Place(x, z, name == null ? "" : name, icon == null ? "" : icon,
                colour(marker), person, online == null || online);
    }

    /**
     * The marker's tint.
     *
     * <p>Written as {@code #rrggbb} by the game and as a number by us; a marker with no tint at
     * all is drawn in the colour the window uses for everything untinted.
     */
    private static int colour(JsonObject marker) {
        JsonElement tint = first(marker, "ColorTint", "colorTint", "colourRgb", "tintRgb");
        if (tint == null || !tint.isJsonPrimitive()) {
            return DEFAULT_COLOUR;
        }
        try {
            if (tint.getAsJsonPrimitive().isNumber()) {
                int rgb = tint.getAsInt();
                return rgb < 0 ? DEFAULT_COLOUR : rgb & 0xFFFFFF;
            }
            String text = tint.getAsString().trim();
            if (text.startsWith("#")) {
                text = text.substring(1);
            }
            return text.isEmpty() ? DEFAULT_COLOUR : (int) (Long.parseLong(text, 16) & 0xFFFFFF);
        } catch (RuntimeException e) {
            return DEFAULT_COLOUR;
        }
    }

    private static JsonElement first(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement found = object.get(name);
            if (found != null && !found.isJsonNull()) {
                return found;
            }
        }
        return null;
    }

    private static Double number(JsonObject object, String... names) {
        JsonElement found = first(object, names);
        try {
            return found == null ? null : found.getAsDouble();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject object, String... names) {
        JsonElement found = first(object, names);
        try {
            return found == null ? null : found.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Boolean bool(JsonObject object, String... names) {
        JsonElement found = first(object, names);
        try {
            return found == null ? null : found.getAsBoolean();
        } catch (RuntimeException e) {
            return null;
        }
    }
}

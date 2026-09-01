package meridian.worldmap;

import java.awt.Image;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import meridian.api.service.ServiceRegistry;
import meridian.core.api.MapMarkers;
import meridian.core.api.Marker;
import meridian.core.api.MarkerCategory;
import meridian.core.api.MarkerSource;

/**
 * The markers the proxy knows about, as the window draws them.
 *
 * <p>Two places have them, and they are not the same thing: {@link MapMarkers} is what the server
 * is showing this minute, and {@link MarkerSource} - the markers module, when it is installed - is
 * everything that has ever been kept for a world, including from sessions long past. This prefers
 * what is kept, because a map of collected ground is a record rather than a live view, and falls
 * back to the live markers when nobody is keeping any.
 *
 * <p>Kept apart from {@link Places} on purpose: this is the only marker code that touches core, so
 * the window opened by a double-click - where core does not exist - never reaches it.
 */
final class CoreMarkers {

    /** What a marker is drawn in when the server gave it no tint. */
    private static final int DEFAULT_COLOUR = 0xFFD24D;

    private final ServiceRegistry services;
    /** Icon name to picture, so the map does not decode the same PNG for every repaint. */
    private final Map<String, Optional<Image>> icons = new ConcurrentHashMap<>();
    private volatile MarkerSource keptCache;

    CoreMarkers(ServiceRegistry services) {
        this.services = services;
    }

    /**
     * The markers module, if it is here - asked for again until it answers.
     *
     * <p>Modules load in whatever order their dependencies allow, and this one deliberately does
     * not depend on the markers module, so at startup it may simply not be there yet. Looking it
     * up when it is wanted rather than once at enable is what makes the order stop mattering.
     */
    private MarkerSource kept() {
        MarkerSource found = keptCache;
        if (found == null) {
            found = services.get(MarkerSource.class).orElse(null);
            keptCache = found;
        }
        return found;
    }

    private MapMarkers live() {
        return services.get(MapMarkers.class).orElse(null);
    }

    /** Everything worth drawing on this world's map. */
    List<Place> places(UUID world) {
        List<Marker> markers = null;
        MarkerSource kept = kept();
        if (kept != null && world != null) {
            try {
                markers = kept.markers(world);
            } catch (RuntimeException e) {
                markers = null;             // a module that misbehaves does not blank the map
            }
        }
        if (markers == null || markers.isEmpty()) {
            MapMarkers live = live();
            markers = live == null ? null : live.all();
        }
        if (markers == null) {
            return List.of();
        }
        List<Place> out = new ArrayList<>(markers.size());
        for (Marker marker : markers) {
            out.add(place(marker));
        }
        return out;
    }

    /**
     * The picture a marker asks for, or null when nobody has it.
     *
     * <p>The icons live in the markers module - it is the one that ships the game's marker art -
     * so without it installed the map draws its own shapes, which is what it did before.
     */
    Image icon(String name) {
        MarkerSource kept = kept();
        if (kept == null || name == null || name.isEmpty()) {
            return null;
        }
        return icons.computeIfAbsent(name, wanted -> {
            try {
                return kept.icon(wanted).map(bytes -> Toolkit.getDefaultToolkit()
                        .createImage(bytes));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static Place place(Marker marker) {
        int colour = marker.colourRgb() < 0 ? DEFAULT_COLOUR : marker.colourRgb() & 0xFFFFFF;
        return new Place(marker.position().x(), marker.position().z(),
                marker.displayName(), marker.icon() == null ? "" : marker.icon(),
                colour, marker.category() == MarkerCategory.PLAYER, marker.online());
    }
}

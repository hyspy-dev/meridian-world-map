package meridian.worldmap;

/**
 * Something worth drawing on the map: a marked place, or a person standing somewhere.
 *
 * <p>The window's own idea of a marker, deliberately not core's. The jar has to open on its own,
 * with no proxy behind it, so nothing the window draws may depend on classes that only exist
 * inside one. Markers coming from core - or read out of a file - are turned into these at the
 * door.
 *
 * @param name   what to write beside it, or empty for nothing
 * @param icon   the picture it asks for, if anything has one to give
 * @param person whether this is somebody rather than somewhere
 * @param online whether that somebody is still here; a person who has left is drawn faded
 */
record Place(double x, double z, String name, String icon, int colourRgb,
             boolean person, boolean online) {
}

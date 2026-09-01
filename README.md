# Meridian World Map

Keeps the map of the world you are playing — every tile the server has drawn for you — and gives
you a window to look at it in. The in-game map only shows what is around you and forgets the rest;
this remembers all of it, across worlds and across sessions, and lets you pan and zoom over
everything you have ever seen.

It is two things in one file:

- **A module for the Meridian proxy.** Drop the jar in with your other modules and it starts
  keeping the map. A button in its settings page opens the window, and the map fills in live as
  you explore.
- **A program on its own.** Double-click the jar and the window opens over what was collected
  earlier — no proxy, no server, nothing to connect to. If it cannot find the map beside itself,
  it asks: drop a `worldmap.dat` on the window, and a markers `.json` with it if you have one.

Nothing here reads or sends a packet, so one build works with every version of the game.

---

## Using it

**In the proxy.** The module appears as *Meridian World Map* in the settings, with three controls:

| Setting | What it does |
|---------|--------------|
| Keep the map across sessions | writes the map down. Turn it off and the map still works while you play, but nothing is saved |
| Keep every tile the server draws | on: everything the server shows you. Off: only the tiles whose ground you have actually collected — see below |
| Open map | opens the window. Tiles appear in it as they arrive |

**On its own.** Double-click the jar, or run it from a terminal:

```
java -jar meridian-world-map.jar [path/to/worldmap.dat]
```

Without a path it looks for the map in its own folder beside the jar. The window is read-only:
drag to pan, wheel to zoom, *Follow player* keeps it centred while you play, *Reset view* fits
everything on screen. The status line shows the block coordinates under the cursor.

**The map file** lives in `<modules>/Meridian World Map/data/worldmap.dat`. It is yours: copy it,
back it up, or hand it to the standalone window on another machine.

---

## What gets kept

The server draws a tile for ground it merely shows you, which is a good deal more than the ground
it sends you in full. Both are worth having, and they are different maps:

- **Every tile** gives a wide, thin map — everywhere you have glimpsed, including across water and
  from hilltops.
- **Only collected ground** gives a map that matches the world you actually hold, tile for chunk.

The second one needs something else running that collects the world and can say which ground it
has; with nothing to ask, the setting makes no difference and every tile is kept. The map is
worth running on its own either way — it needs no companion module and asks for none.

Tiles from earlier sessions are kept whichever way the setting is turned. They passed the same
test when they were written, and a map does not un-remember.

---

## Markers

If the **Markers** module is installed, the window draws what it keeps: the world's own points of
interest, what players have shared, and where people were last seen — each with the icon the game
draws it with. Without it, the window falls back to the markers the server is showing right now and
draws its own shapes: a diamond for a place, a circle for a person. Somebody who has logged out is
drawn faded rather than removed, so the map says plainly that their position is old.

The standalone window has nobody to ask, so it takes a file instead. Drop one on it and it reads
whichever shape it is in: a world's own `SharedUserMapMarkers.json`, the markers file this
ecosystem keeps, or a plain list of markers.

---

## How the map is stored

Tiles are kept at the size the server draws them (96 pixels a side in the current build) and
**decoded** — plain RGB, not the form they arrived in. The wire format belongs to one version of
the game and a map you keep for months should outlive that. Maps written by earlier builds held the
wire form; they are still read, and rewritten decoded, so nothing collected is lost.

The window draws through a pyramid of pre-scaled levels built in the background, so zooming out
over a large map stays smooth and the interface never waits on an image.

---

## Building

```
mvn package
```

Java 21. The result is `target/meridian-world-map-<version>.jar` — the module and the standalone
program are the same file.

## Source

| file | what it is |
|---|---|
| `WorldMapModule` | the module: keeps core's tiles, the settings page, the window |
| `WorldMapStore` / `Tile` | the map itself: every tile collected, across worlds and sessions |
| `LegacyTiles` | reads tiles out of maps written in the old wire format |
| `MapViewer` / `MapCanvas` / `TilePyramid` | the window: tiles, the player, the markers |
| `Place` / `Places` | what the window draws a marker as, and how it reads one out of a file |
| `CoreMarkers` / `CoreTiles` | what comes from the proxy — the only classes that know it is there |
| `OpenPrompt` | what the window says when it was opened with nothing to show |

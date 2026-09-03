# Meridian World Map

Keeps the map of every world you play in — every tile the server has drawn for you — and gives you
a window to look at them in. The in-game map only shows what is around you and forgets the rest;
this remembers all of it, world by world, across sessions, and lets you pan and zoom over
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
| Show every tile in full colour | on: the whole map as the server drew it. Off: ground you have not collected is faded — see below |
| Open map | opens the window. Tiles appear in it as they arrive |

**On its own.** Double-click the jar, or run it from a terminal:

```
java -jar meridian-world-map.jar [path/to/worldmap.dat]
```

Without a path it looks for the map in its own folder beside the jar. Drag to pan, wheel to zoom,
*Follow player* keeps it centred while you play, *Reset view* fits everything on screen. The status
line shows the block coordinates under the cursor.

**Every world is in there.** One archive holds each world you have visited, and the toolbar's
*World* picker says which one is on screen — with how many tiles each has, largest first. Switching
rebuilds the map for that world: its tiles, its markers, and the player only when they are actually
standing in it. A world entered while the window is open joins the list on its own.

**Right-click to go there.** With a module installed that can teleport, right-clicking anywhere on
the map takes the player to that spot — the height at the column is worked out for you. Without
one, the map says so and stays a map.

**The map file** lives in `<modules>/Meridian World Map/data/worldmap.dat`. It is yours: copy it,
back it up, or hand it to the standalone window on another machine.

---

## Collected ground, and the rest

The server draws a tile for ground it merely shows you, which is a good deal more than the ground
it sends you in full — a coastline seen from a hilltop is on your map long before you walk it.
**Every tile is kept**, both kinds: a map is a record, and the far shore is worth having.

What the setting changes is how they are drawn. With *Show every tile in full colour* off, ground
nobody has collected is faded — half its colour and half its light — so one look says where you
have actually been while the shape of the land still reads. Turn it on and the whole map is drawn
as the server painted it.

Telling the two apart needs something that collects the world and can say which ground it has;
with nothing to ask, there is nothing to fade and the map is drawn plainly. The map needs no
companion module and asks for none.

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

A **table**, not a stream. Every tile is addressed by its world and its column, so writing one
again replaces it in place — the same ground walked over a hundred times takes the room of one
tile, and the file cannot fill with old copies of itself.

```
  0  "MMP3"   what this is        64  data segments …
  4  version                          directory: one record a tile
  8  directory offset  <- the commit
```

That shape is what makes the rest work:

- **Opening is instant.** Only the directory is read — a 147 MB map opens in a hundredth of a
  second, and tiles are read as they are drawn. Nothing is held in memory but what is on screen.
- **A crash cannot cost the map.** Tile bytes go into free space, then the directory is written
  somewhere free, and only then does the header point at it. Every step before the last leaves
  the file exactly as it was, and the last is a few bytes at a fixed place.
- **A session cannot shrink the map.** It writes its own tiles and nothing else; there is no pass
  that rewrites the file from what one session happens to hold.
- **An unchanged tile is not written.** The directory keeps a checksum, and a server that re-sends
  the same picture every time you walk past costs nothing.

Tiles are kept at the size the server draws them (96 pixels a side in the current build), decoded
and deflated one by one with what the JDK ships — the map has to open on a double-click, with
nothing on the class path but itself.

Maps written by earlier builds were one long gzip stream. The first time such a map is opened it
is converted, once, and the original is kept beside it as `worldmap.dat.v2`.

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

# Changelog

All notable changes to **Server Browser** (Fabric, Minecraft 26.3).

## 1.2.0

### Added
- **Background querying that never stalls the game.** All server pings run on low-priority background threads
  and results are picked up by the screen without waiting. Disk reads and writes (cache, favorites, history) run on a
  separate background thread, and new server icons are turned into textures at most 3 per frame, so scrolling through
  hundreds of servers stays smooth. The screen rebuilds less often while many servers are still being checked.
- **Status cache.** The last known state of every server (players, message of the day, version, ping, icon) is
  remembered in memory and in `config/serverbrowser/status_cache.json`. Reopening the browser within a few minutes is
  instant, even after restarting the game. Entries older than the cache time are refreshed in the background.
  The server list file itself is also kept in memory for the session. New setting: *Cache server status for: N min*
  (default 5).
- **Tabs: All / Favorites / Recent.**
- **Favorites with notes.** Star a server and press the pencil button on its row to write a note
  (for example "Friendly admins, avoid player X"). Notes are shown on the server's row, are searchable, and can be
  added to any server, not only favorites. Stored in `config/serverbrowser/favorites.json`.
- **Recent tab.** Every multiplayer server you join, however you join it, is logged with the time you last joined,
  your total play time there, and how many times you joined. Keeps the last 20 to 50 servers
  (setting *History size*, default 50). Stored in `config/serverbrowser/history.json`. Play time is saved when you
  disconnect or close the game.
- **Filters screen** (the *Filters (n)* button, with a count of active filters):
  - Offline servers: hide or show
  - Full servers: show / hide / only
  - Empty servers: show / hide / only
  - Locked (whitelist or password) servers: show / hide / only
  - Servers needing mods you do not have installed: show / hide / only
  - Compatible game version only
  - **Game mode tags as pills.** Select several; a server matches if it has any of them.
- **Row badges:** `FULL`, `LOCKED` and `MODS` appear next to a server's name.
- Server list format **schema 2**: optional `tags`, `locked` and `requiredMods` fields per server
  (older lists keep working; without `tags` the category is used as the tag).
- Forge / NeoForge servers announce their mods in the ping, so they are marked `MODS` automatically.
- New server list with 217 servers, game mode tags and language tags (see `servers.json`).
- Translations for all new text in all 15 languages.
- `../../Downloads/serverbrowser/tools/check_servers.py`: checks which servers in a list answer a ping and writes a cleaned copy.

### Changed
- The **Category** button is replaced by the tag pills in the Filters screen (the category is also a tag).
- The **Favorites: ON/OFF** toggle is replaced by the Favorites tab.
- **Hide offline** and **Compatible** moved into the Filters screen.
- The Add button row now has three buttons: Add, star, note.
- The built-in fallback list is now the full 217-server list.
- The `pingCacheSeconds` setting is replaced by `statusCacheMinutes`.
- `favorites.json` has a new format. An old favorites file is converted automatically the first time.
- The settings screen has two new options (status cache time, history size) and a tighter layout.

### Fixed
- After opening Settings (or the new Filters / Note screens) and coming back, the browser kept treating itself as
  closed, so a reload triggered by changing the list URL was ignored.
- After coming back from those screens, queued pings stayed cancelled, so servers on the page could stay at "..."
  until you pressed Refresh.
- Cancelled queued pings never told the screen, so a server could be left unchecked after a page change.

### Notes
- A status ping cannot reveal a **password or whitelist**. The *Locked* filter uses the list's `locked` flag, plus
  servers whose message of the day says "whitelist".
- Custom **maps** live on the server and cannot be detected. The mods filter covers mods: those the list names in
  `requiredMods` and those a Forge / NeoForge server announces. Fabric servers do not announce their mods, so they
  need `requiredMods` in the list.
- Filters that need live data (full, empty, locked, mods, version) and sorting by players or ping check **all**
  servers in the open tab, with progress shown at the bottom. Other views only check the current page.

## 1.1.0

### Added
- **Language button** in the settings screen: *Auto* (follows Minecraft) or any language pack, independent of the
  game's language.
- **Drop-in language packs:** put a `<code>.json` file in `config/serverbrowser/lang/`, no rebuild needed.
  `../../Downloads/serverbrowser/language-pack-template.json` is included.
- Translations for 15 languages: English, German, French, Spanish (Spain, Mexico), Brazilian Portuguese, Italian,
  Dutch, Polish, Russian, Turkish, Japanese, Korean, Simplified and Traditional Chinese.
- Server categories are translated; the Add button resizes to fit longer words.

### Changed
- **Hide offline** replaces "Hide empty" and is **on by default**. Online servers with zero players stay visible.
- The setting `hideEmptyByDefault` is replaced by `hideOfflineByDefault`.

## 1.0.0

### Added
- **Server icons** from each server's ping, shown in place of the letter box.
- **Paging** (10 servers per page, 5 to 50 in settings) with only the current page pinged, and ping results reused
  for 60 seconds.
- **Add / star buttons on every row.**
- **Global sorting** by players or ping across all pages, with progress shown.
- `../../Downloads/serverbrowser/build.sh` and `build.bat`: build the jar from inside the mod folder, without the Fabric template. They check for a
  full JDK 25 and download Gradle into the folder on first use.
- Default server list URL set to your GitHub list.

### Fixed
- The *Browse Servers* button was added several times on the Multiplayer screen.
- Clicking a server or the star did nothing; replaced by real buttons on each row.
- Build failed with "does not provide the required capabilities: [JAVA_COMPILER]" when only a JRE was installed;
  the build scripts now explain how to install the JDK.
- Build failed with "Invalid Timestamp" after unzipping; the zip has valid dates and `../../Downloads/serverbrowser/build.sh` repairs file dates.
- Collapsed extra spaces in server messages of the day.

## 0.1.0 alpha

### Added
- *Browse Servers* button on the Multiplayer screen, added with Fabric's screen events (no Mixins) and moved out of
  the way if another mod uses that spot.
- Server browser screen with search, category filter, sorting, favorites toggle, version-compatible filter,
  Refresh and Settings.
- Server list loaded from a JSON file on the web, with a local cache, an offline copy and a built-in fallback.
- Live player counts from a direct status ping (including SRV records), without depending on Minecraft's internal
  pinger classes.
- Clicking a server adds it, under its proper name, to your normal Multiplayer list (`servers.dat`).
- Settings screen and Mod Menu integration. Client-side only.
- Built for Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.160.5+26.3, Java 25.
  (Note this version was never published. It ws only used internally for testing anc creating the real first version)

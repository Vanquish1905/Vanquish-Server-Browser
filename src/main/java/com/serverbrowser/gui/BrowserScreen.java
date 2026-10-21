package com.serverbrowser.gui;

import com.serverbrowser.Lang;
import com.serverbrowser.Nav;
import com.serverbrowser.config.Favorites;
import com.serverbrowser.config.History;
import com.serverbrowser.config.ModConfig;
import com.serverbrowser.data.PingCache;
import com.serverbrowser.data.ServerAdder;
import com.serverbrowser.data.ServerEntry;
import com.serverbrowser.data.ServerListFetcher;
import com.serverbrowser.data.ServerPinger;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The server browser. Tabs: All / Favorites / Recent. Each row has an Add button (adds the server to your
 * Multiplayer list), a star (favorite) and a note button.
 */
public class BrowserScreen extends Screen {
	private static final int ROW_H = 36;

	/** PLAYERS and PING (and live-data filters) need every server's status, so they check all servers. */
	private enum Sort { ORDER, NAME, PLAYERS, PING }

	private enum Tab { ALL, FAVORITES, RECENT }

	private final Screen parent;
	private final Filters filters = new Filters();

	private List<ServerEntry> all = List.of();
	private final Map<String, ServerEntry> byIp = new HashMap<>();
	/** Favorites / history servers that are not in the downloaded list. */
	private final Map<String, ServerEntry> extras = new HashMap<>();
	private Map<String, Integer> tagCounts = new LinkedHashMap<>();
	private List<ServerEntry> view = List.of();

	private Tab tab = Tab.ALL;
	private Sort sort = Sort.ORDER;
	private String searchText = "";

	private EditBox search;
	private final Button[] tabBtns = new Button[3];
	private Button sortBtn, filterBtn, prevBtn, nextBtn;
	private Button[] addBtns = new Button[0];
	private Button[] starBtns = new Button[0];
	private Button[] noteBtns = new Button[0];
	private int addW = 38;

	private double scroll;
	private int listTop, listBottom, rowLeft, rowWidth;
	private int page;
	private int totalPages = 1;
	private int staticTotal;
	private int pendingCount;
	private boolean needAll;

	private volatile boolean dirty = true;
	private boolean loading = true;
	private boolean started;
	private boolean closed;
	private boolean addedAny;
	private long lastRebuild;
	private String lastUrl = ModConfig.get().listUrl;

	private Set<String> existing = new HashSet<>();
	private Component status = Component.empty();
	private int statusColor = 0xFFAAAAAA;
	private ServerPinger.Batch batch = new ServerPinger.Batch();

	public BrowserScreen(Screen parent) {
		super(Lang.tr("serverbrowser.screen.title"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		// This screen is re-initialised after Settings / Filters / Note screens and on window resize.
		closed = false;
		resetBatch();

		rowWidth = Math.min(this.width - 20, 440);
		rowLeft = (this.width - rowWidth) / 2;
		listTop = 74;
		listBottom = this.height - 46;

		search = new EditBox(this.font, rowLeft, 22, Math.max(80, rowWidth - 130), 20, Lang.tr("serverbrowser.search"));
		search.setHint(Lang.tr("serverbrowser.search.hint"));
		search.setMaxLength(64);
		search.setValue(searchText);
		search.setResponder(s -> {
			searchText = s;
			resetView();
		});
		this.addRenderableWidget(search);

		int right = rowLeft + rowWidth;
		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.button.refresh"), b -> reload(true))
				.bounds(right - 124, 22, 60, 20).build());
		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.button.settings"),
						b -> Nav.open(this.minecraft, new ConfigScreen(this)))
				.bounds(right - 60, 22, 60, 20).build());

		int bw = (rowWidth - 16) / 5;
		String[] tabKeys = {"serverbrowser.tab.all", "serverbrowser.tab.favorites", "serverbrowser.tab.recent"};
		for (int i = 0; i < 3; i++) {
			final Tab target = Tab.values()[i];
			tabBtns[i] = this.addRenderableWidget(Button.builder(Lang.tr(tabKeys[i]), b -> {
				tab = target;
				resetView();
			}).bounds(rowLeft + i * (bw + 4), 46, bw, 20).build());
		}
		sortBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
			resetView();
		}).bounds(rowLeft + 3 * (bw + 4), 46, bw, 20).build());
		filterBtn = this.addRenderableWidget(Button.builder(Component.empty(),
						b -> Nav.open(this.minecraft, new FilterScreen(this, filters, tagCounts)))
				.bounds(rowLeft + 4 * (bw + 4), 46, bw, 20).build());

		int cx = this.width / 2;
		prevBtn = this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.button.prev"), b -> setPage(page - 1))
				.bounds(cx - 175, this.height - 26, 80, 20).build());
		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.button.done"), b -> onClose())
				.bounds(cx - 85, this.height - 26, 170, 20).build());
		nextBtn = this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.button.next"), b -> setPage(page + 1))
				.bounds(cx + 95, this.height - 26, 80, 20).build());

		// The Add button is as wide as its longest label ("Add" / "Added") in the current language.
		addW = Math.max(38, Math.max(
				this.font.width(Lang.tr("serverbrowser.row.add")),
				this.font.width(Lang.tr("serverbrowser.row.added"))) + 12);

		// One Add, star and note button per row on a page. They are real buttons, positioned each frame.
		int rows = Math.max(1, ModConfig.get().pageSize);
		addBtns = new Button[rows];
		starBtns = new Button[rows];
		noteBtns = new Button[rows];
		for (int i = 0; i < rows; i++) {
			final int idx = i;
			addBtns[i] = this.addWidget(Button.builder(Lang.tr("serverbrowser.row.add"), b -> addServer(rowEntry(idx)))
					.bounds(0, 0, addW, 20).build());
			starBtns[i] = this.addWidget(Button.builder(Component.literal("\u2606"), b -> toggleFavorite(rowEntry(idx)))
					.bounds(0, 0, 20, 20).build());
			noteBtns[i] = this.addWidget(Button.builder(Component.literal("\u270E"), b -> openNote(rowEntry(idx)))
					.bounds(0, 0, 20, 20).build());
			addBtns[i].visible = false;
			starBtns[i].visible = false;
			noteBtns[i].visible = false;
		}

		updateLabels();
		dirty = true;

		boolean urlChanged = !lastUrl.equals(ModConfig.get().listUrl);
		if (!started || urlChanged) {
			started = true;
			lastUrl = ModConfig.get().listUrl;
			reload(urlChanged);
		}
	}

	private void resetBatch() {
		batch.cancel();
		batch = new ServerPinger.Batch();
	}

	private void reload(boolean force) {
		resetBatch();
		if (force) {
			PingCache.clear();
			extras.clear();
		}

		// Opened again within a few minutes: show the list we already have, instantly.
		ServerListFetcher.Result cached = force ? null : ServerListFetcher.peekFresh();
		if (cached != null) {
			onLoaded(cached);
			return;
		}

		loading = true;
		setStatus(Lang.tr("serverbrowser.status.loading"), 0xFFAAAAAA);
		CompletableFuture
				.supplyAsync(() -> ServerListFetcher.load(force))
				.thenAccept(result -> this.minecraft.execute(() -> onLoaded(result)))
				.exceptionally(ex -> {
					this.minecraft.execute(() -> {
						loading = false;
						setStatus(Lang.tr("serverbrowser.status.load_failed"), 0xFFFF5555);
					});
					return null;
				});
	}

	private void onLoaded(ServerListFetcher.Result result) {
		if (closed) return;
		if (result.servers() != all) {
			for (ServerEntry old : all) IconCache.release(old);
		}
		all = result.servers();
		loading = false;

		// Index by address, fill in remembered status from the cache, and count the tags.
		byIp.clear();
		Map<String, String> display = new LinkedHashMap<>();
		Map<String, Integer> counts = new HashMap<>();
		for (ServerEntry e : all) {
			PingCache.applyTo(e);
			byIp.put(ServerAdder.normalize(e.ip), e);
			for (String t : e.tags) {
				String key = t.toLowerCase(Locale.ROOT);
				display.putIfAbsent(key, t);
				counts.merge(key, 1, Integer::sum);
			}
		}
		List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort((a, b) -> {
			int c = Integer.compare(b.getValue(), a.getValue());
			return c != 0 ? c : a.getKey().compareTo(b.getKey());
		});
		tagCounts = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> en : sorted) tagCounts.put(display.get(en.getKey()), en.getValue());

		existing = ServerAdder.existing(this.minecraft);

		String sourceKey = switch (result.source()) {
			case ONLINE -> "serverbrowser.source.online";
			case CACHE -> "serverbrowser.source.cache";
			case OFFLINE_CACHE -> "serverbrowser.source.offline_cache";
			default -> "serverbrowser.source.bundled";
		};
		if (result.source() == ServerListFetcher.Source.BUNDLED && !ModConfig.get().isListUrlConfigured()) {
			setStatus(Lang.tr("serverbrowser.status.no_url"), 0xFFFFFF55);
		} else {
			setStatus(Lang.tr("serverbrowser.status.loaded", all.size(), Lang.tr(sourceKey)), 0xFFAAAAAA);
		}
		updateLabels();
		lastRebuild = 0;
		dirty = true;
	}

	// ------------------------------------------------------------------ state changes

	/** Search text, tab, sort or filters changed: back to the first page. */
	private void resetView() {
		scroll = 0;
		page = 0;
		lastRebuild = 0;
		dirty = true;
		updateLabels();
	}

	private void setPage(int p) {
		page = Math.max(0, Math.min(totalPages - 1, p));
		scroll = 0;
		lastRebuild = 0;
		dirty = true;
	}

	/** Called by the filters screen when it closes. */
	void filtersChanged() {
		resetView();
	}

	/** Called by the note screen after saving. */
	void noteChanged() {
		lastRebuild = 0;
		dirty = true;
	}

	private void updateLabels() {
		if (sortBtn == null) return;
		for (int i = 0; i < tabBtns.length; i++) {
			if (tabBtns[i] != null) tabBtns[i].active = tab.ordinal() != i; // the open tab is greyed out
		}
		sortBtn.setMessage(Lang.tr("serverbrowser.filter.sort",
				Lang.tr("serverbrowser.sort." + sort.name().toLowerCase(Locale.ROOT))));
		filterBtn.setMessage(Lang.tr("serverbrowser.filters.button", filters.activeCount()));
	}

	/** Category names come from the server list. Known ones are translated, unknown ones are shown as written. */
	private static Component categoryLabel(String cat) {
		String key = "serverbrowser.category." + cat.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		Component translated = Lang.tr(key);
		return translated.getString().equals(key) ? Component.literal(cat) : translated;
	}

	// ------------------------------------------------------------------ building the visible page

	/** The servers of the open tab, before searching and filtering. */
	private List<ServerEntry> sourceList() {
		switch (tab) {
			case FAVORITES: {
				List<ServerEntry> out = new ArrayList<>();
				for (Favorites.Record r : Favorites.favorites()) out.add(entryFor(r.ip, r.name));
				return out;
			}
			case RECENT: {
				List<ServerEntry> out = new ArrayList<>();
				for (History.Entry h : History.list()) out.add(entryFor(h.ip, h.name));
				return out;
			}
			default:
				return all;
		}
	}

	/** The list entry for an address, or a plain entry for servers that are not in the downloaded list. */
	private ServerEntry entryFor(String ip, String name) {
		String key = ServerAdder.normalize(ip);
		ServerEntry listed = byIp.get(key);
		if (listed != null) return listed;
		return extras.computeIfAbsent(key, k -> {
			ServerEntry e = new ServerEntry(k, name == null || name.isBlank() ? ip : name, ip, "Other", "", "",
					List.of(), false, List.of());
			PingCache.applyTo(e);
			return e;
		});
	}

	private static boolean matchesSearch(ServerEntry e, String q) {
		return e.name.toLowerCase(Locale.ROOT).contains(q)
				|| e.ip.toLowerCase(Locale.ROOT).contains(q)
				|| e.category.toLowerCase(Locale.ROOT).contains(q)
				|| e.description.toLowerCase(Locale.ROOT).contains(q)
				|| String.join(" ", e.tags).toLowerCase(Locale.ROOT).contains(q)
				|| Favorites.note(e.ip).toLowerCase(Locale.ROOT).contains(q);
	}

	private void rebuildView() {
		lastRebuild = System.currentTimeMillis();
		dirty = false;
		String q = searchText.trim().toLowerCase(Locale.ROOT);

		// 1) Things that need no ping data: tab, search, game mode tags.
		List<ServerEntry> out = new ArrayList<>();
		for (ServerEntry e : sourceList()) {
			if (!q.isEmpty() && !matchesSearch(e, q)) continue;
			if (!filters.matchesTags(e)) continue;
			out.add(e);
		}

		// 2) Sorting by players/ping and the live filters (full, empty, locked, mods, version) need every
		//    server's status, so in those modes all servers are checked, with progress shown at the bottom.
		//    Otherwise only the servers on the current page are checked.
		needAll = sort == Sort.PLAYERS || sort == Sort.PING || filters.needsLiveData();
		staticTotal = out.size();
		pendingCount = 0;
		for (ServerEntry e : out) {
			if (e.state == ServerEntry.State.IDLE || e.state == ServerEntry.State.PINGING) pendingCount++;
		}
		if (needAll) ServerPinger.request(out, batch, () -> dirty = true);

		out.removeIf(e -> !filters.matchesLive(e));

		switch (sort) {
			case NAME -> out.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
			case PLAYERS -> out.sort(Comparator.comparingInt(BrowserScreen::playersRank).reversed());
			case PING -> out.sort(Comparator.comparingInt(e -> e.state == ServerEntry.State.ONLINE && e.pingMs >= 0 ? e.pingMs : Integer.MAX_VALUE));
			default -> { }
		}

		// 3) Split into pages.
		int size = Math.max(1, ModConfig.get().pageSize);
		totalPages = Math.max(1, (out.size() + size - 1) / size);
		page = Math.max(0, Math.min(page, totalPages - 1));
		int from = page * size;
		int to = Math.min(out.size(), from + size);
		List<ServerEntry> pageEntries = new ArrayList<>(out.subList(from, to));
		if (!needAll) ServerPinger.request(pageEntries, batch, () -> dirty = true);
		view = pageEntries;

		if (prevBtn != null) prevBtn.active = page > 0;
		if (nextBtn != null) nextBtn.active = page < totalPages - 1;
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
	}

	/** Higher = more players. Servers still being checked sit below online ones, offline servers last. */
	private static int playersRank(ServerEntry e) {
		return switch (e.state) {
			case ONLINE -> e.online;
			case OFFLINE -> -2;
			default -> -1;
		};
	}

	private ServerEntry rowEntry(int i) {
		return (i >= 0 && i < view.size()) ? view.get(i) : null;
	}

	private int maxScroll() {
		return Math.max(0, view.size() * ROW_H - (listBottom - listTop));
	}

	private void setStatus(Component text, int color) {
		status = text;
		statusColor = color;
	}

	// ------------------------------------------------------------------ row buttons

	private void toggleFavorite(ServerEntry e) {
		if (e == null) return;
		Favorites.toggle(e.ip, e.name);
		lastRebuild = 0;
		dirty = true;
	}

	private void openNote(ServerEntry e) {
		if (e == null) return;
		Nav.open(this.minecraft, new NoteScreen(this, e));
	}

	/** Moves the per-row buttons to their rows. Rows that are cut off by the list edge get no buttons. */
	private void layoutRowButtons() {
		int s = (int) scroll;
		int right = rowLeft + rowWidth;
		for (int i = 0; i < addBtns.length; i++) {
			ServerEntry e = rowEntry(i);
			int top = listTop - s + i * ROW_H;
			boolean show = e != null && top >= listTop && top + ROW_H - 2 <= listBottom;
			addBtns[i].visible = show;
			starBtns[i].visible = show;
			noteBtns[i].visible = show;
			addBtns[i].active = show;
			starBtns[i].active = show;
			noteBtns[i].active = show;
			if (!show) continue;
			boolean added = existing.contains(ServerAdder.normalize(e.ip));
			addBtns[i].setX(right - 48 - addW);
			addBtns[i].setY(top + 7);
			starBtns[i].setX(right - 44);
			starBtns[i].setY(top + 7);
			noteBtns[i].setX(right - 22);
			noteBtns[i].setY(top + 7);
			addBtns[i].setMessage(Lang.tr(added ? "serverbrowser.row.added" : "serverbrowser.row.add"));
			addBtns[i].active = !added;
			starBtns[i].setMessage(Component.literal(Favorites.has(e.ip) ? "\u2605" : "\u2606"));
		}
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		IconCache.beginFrame();
		long interval = pendingCount > 50 ? 500 : 250; // rebuild less often while many servers are being checked
		if (dirty && System.currentTimeMillis() - lastRebuild > interval) rebuildView();
		layoutRowButtons();

		super.extractRenderState(g, mouseX, mouseY, delta);

		g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 8, 0xFFFFFFFF, true);

		// list panel
		g.fill(rowLeft - 4, listTop, rowLeft + rowWidth + 12, listBottom, 0x88000000);
		g.enableScissor(rowLeft - 4, listTop, rowLeft + rowWidth + 12, listBottom);
		int s = (int) scroll;
		for (int i = 0; i < view.size(); i++) {
			int top = listTop - s + i * ROW_H;
			if (top + ROW_H < listTop || top > listBottom) continue;
			boolean hover = mouseY >= listTop && mouseY < listBottom
					&& mouseX >= rowLeft && mouseX < rowLeft + rowWidth
					&& mouseY >= top && mouseY < top + ROW_H;
			drawRow(g, view.get(i), top, hover);
		}
		if (view.isEmpty()) {
			Component msg = Lang.tr(emptyKey());
			g.text(this.font, msg, this.width / 2 - this.font.width(msg) / 2, listTop + 24, 0xFFAAAAAA, true);
		}
		g.disableScissor();

		// scrollbar
		int max = maxScroll();
		if (max > 0) {
			int trackH = listBottom - listTop;
			int barH = Math.max(20, trackH * trackH / (view.size() * ROW_H));
			int barY = listTop + (int) ((trackH - barH) * (scroll / max));
			int bx = rowLeft + rowWidth + 6;
			g.fill(bx, listTop, bx + 4, listBottom, 0x44FFFFFF);
			g.fill(bx, barY, bx + 4, barY + barH, 0xFFCCCCCC);
		}

		// Row buttons are drawn here (not by super) so they sit on top of the list.
		for (int i = 0; i < addBtns.length; i++) {
			if (addBtns[i].visible) {
				addBtns[i].extractRenderState(g, mouseX, mouseY, delta);
				starBtns[i].extractRenderState(g, mouseX, mouseY, delta);
				noteBtns[i].extractRenderState(g, mouseX, mouseY, delta);
			}
		}

		// footer
		Component pageInfo = Lang.tr("serverbrowser.page", page + 1, totalPages, staticTotal);
		int pw = this.font.width(pageInfo);
		g.text(this.font, pageInfo, rowLeft + rowWidth - pw, listBottom + 6, 0xFF888888, true);
		if (needAll && pendingCount > 0) {
			Component checking = Lang.tr("serverbrowser.status.checking", staticTotal - pendingCount, staticTotal);
			g.text(this.font, checking, rowLeft, listBottom + 6, 0xFFFFFF55, true);
		} else {
			g.text(this.font, fit(status.getString(), rowWidth - pw - 12), rowLeft, listBottom + 6, statusColor, true);
		}
	}

	private String emptyKey() {
		if (loading) return "serverbrowser.loading";
		if (tab == Tab.FAVORITES && Favorites.favorites().isEmpty()) return "serverbrowser.empty.favorites";
		if (tab == Tab.RECENT && History.list().isEmpty()) return "serverbrowser.empty.recent";
		return "serverbrowser.empty";
	}

	private void drawRow(GuiGraphicsExtractor g, ServerEntry e, int top, boolean hover) {
		int left = rowLeft;
		int right = rowLeft + rowWidth;
		g.fill(left, top, right, top + ROW_H - 2, hover ? 0x50FFFFFF : 0x30000000);

		Identifier icon = IconCache.get(this.minecraft, e);
		if (icon != null) {
			// server icons are 64x64 pixels, drawn scaled down into the 26x26 box
			g.blit(RenderPipelines.GUI_TEXTURED, icon, left + 4, top + 4, 0, 0, 26, 26, 64, 64, 64, 64);
		} else {
			// no icon (yet): coloured box with the first letter
			int h = e.name.hashCode();
			int color = 0xFF000000 | (((h & 0xFF) / 2 + 60) << 16) | ((((h >> 8) & 0xFF) / 2 + 60) << 8) | (((h >> 16) & 0xFF) / 2 + 60);
			g.fill(left + 4, top + 4, left + 30, top + 30, color);
			String letter = e.name.isEmpty() ? "?" : e.name.substring(0, 1).toUpperCase(Locale.ROOT);
			g.text(this.font, letter, left + 17 - this.font.width(letter) / 2, top + 13, 0xFFFFFFFF, true);
		}

		boolean added = existing.contains(ServerAdder.normalize(e.ip));
		int textX = left + 38;
		int rightCol = right - 54 - addW;
		int maxText = rightCol - 84 - textX;

		String title = fit((added ? "\u2714 " : "") + e.name, maxText);
		g.text(this.font, title, textX, top + 6, added ? 0xFF55FF55 : 0xFFFFFFFF, true);
		int bx = textX + this.font.width(title) + 5;
		bx = badge(g, bx, top + 6, textX + maxText, Filters.isFull(e), "serverbrowser.badge.full", 0xFFFFD84D);
		bx = badge(g, bx, top + 6, textX + maxText, Filters.isLocked(e), "serverbrowser.badge.locked", 0xFFFF7777);
		badge(g, bx, top + 6, textX + maxText, e.missingMods > 0, "serverbrowser.badge.mods", 0xFFFFA64D);

		// second line: your note, or (Recent tab) when you joined, or the server's message / description
		String note = Favorites.note(e.ip);
		String sub;
		int subColor = 0xFFAAAAAA;
		if (!note.isEmpty()) {
			sub = "\u270E " + note;
			subColor = 0xFFFFD27F;
		} else if (tab == Tab.RECENT) {
			sub = historyLine(e);
		} else if (e.state == ServerEntry.State.ONLINE && !e.motd.isEmpty()) {
			sub = e.motd;
		} else {
			sub = e.description.isEmpty() ? categoryLabel(e.category).getString() : e.description;
		}
		g.text(this.font, fit(sub, maxText), textX, top + 19, subColor, false);

		String players;
		int playersColor;
		switch (e.state) {
			case ONLINE -> {
				players = e.online + "/" + e.max;
				playersColor = e.online > 0 ? 0xFF55FF55 : 0xFFAAAAAA;
			}
			case OFFLINE -> {
				players = Lang.tr("serverbrowser.state.offline").getString();
				playersColor = 0xFFFF5555;
			}
			default -> {
				players = "...";
				playersColor = 0xFFAAAAAA;
			}
		}
		g.text(this.font, players, rightCol - this.font.width(players), top + 6, playersColor, true);

		if (e.state == ServerEntry.State.ONLINE) {
			String info = fit(e.versionName, 70) + (e.pingMs >= 0 ? "  " + e.pingMs + "ms" : "");
			int infoColor = Filters.compat(e) == 0 ? 0xFFFF7777 : 0xFF888888;
			g.text(this.font, info, rightCol - this.font.width(info), top + 19, infoColor, false);
		}
	}

	/** Draws a small tag after the server name if it applies and fits. Returns where the next one starts. */
	private int badge(GuiGraphicsExtractor g, int x, int y, int limit, boolean on, String key, int color) {
		if (!on) return x;
		String text = Lang.tr(key).getString();
		int w = this.font.width(text);
		if (x + w > limit) return x;
		g.text(this.font, text, x, y, color, true);
		return x + w + 5;
	}

	private String historyLine(ServerEntry e) {
		History.Entry h = History.get(e.ip);
		if (h == null) return "";
		String when = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
				.withLocale(Lang.locale())
				.withZone(ZoneId.systemDefault())
				.format(Instant.ofEpochMilli(h.lastJoined));
		return Lang.tr("serverbrowser.history.line", when, duration(History.totalMs(h)), h.joins).getString();
	}

	private static String duration(long ms) {
		long minutes = ms / 60_000L;
		if (minutes >= 60) return Lang.tr("serverbrowser.duration", minutes / 60, minutes % 60).getString();
		return Lang.tr("serverbrowser.duration.short", minutes).getString();
	}

	private String fit(String s, int maxWidth) {
		if (maxWidth <= 0) return "";
		if (this.font.width(s) <= maxWidth) return s;
		String ellipsis = "...";
		int limit = maxWidth - this.font.width(ellipsis);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			if (this.font.width(sb.toString() + s.charAt(i)) > limit) break;
			sb.append(s.charAt(i));
		}
		return sb + ellipsis;
	}

	// ------------------------------------------------------------------ input

	private ServerEntry entryAt(double mx, double my) {
		if (my < listTop || my >= listBottom || mx < rowLeft || mx >= rowLeft + rowWidth) return null;
		int idx = (int) ((my - listTop + (int) scroll) / ROW_H);
		return (idx >= 0 && idx < view.size()) ? view.get(idx) : null;
	}

	/** Fallback: a click on the body of a row (not on its buttons) also adds the server, if the screen receives it. */
	boolean onListClick(double mx, double my, int button) {
		if (button != 0) return false;
		if (mx >= rowLeft + rowWidth - (50 + addW)) return false;
		ServerEntry e = entryAt(mx, my);
		if (e == null) return false;
		addServer(e);
		return true;
	}

	boolean onListScroll(double mx, double my, double amount) {
		if (my < listTop || my >= listBottom) return false;
		scroll = Math.max(0, Math.min(maxScroll(), scroll - amount * ROW_H));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		return onListClick(event.x(), event.y(), event.button());
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (onListScroll(mouseX, mouseY, scrollY)) return true;
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void addServer(ServerEntry e) {
		if (e == null) return;
		switch (ServerAdder.add(this.minecraft, e)) {
			case ADDED -> {
				addedAny = true;
				existing.add(ServerAdder.normalize(e.ip));
				setStatus(Lang.tr("serverbrowser.status.added", e.name), 0xFF55FF55);
			}
			case ALREADY -> {
				existing.add(ServerAdder.normalize(e.ip));
				setStatus(Lang.tr("serverbrowser.status.already", e.name), 0xFFFFFF55);
			}
			case FAILED -> setStatus(Lang.tr("serverbrowser.status.failed"), 0xFFFF5555);
		}
	}

	// ------------------------------------------------------------------ closing

	@Override
	public void removed() {
		closed = true;
		batch.cancel();
		for (ServerEntry e : all) IconCache.release(e);
		for (ServerEntry e : extras.values()) IconCache.release(e);
		PingCache.flushNow();
		super.removed();
	}

	@Override
	public void onClose() {
		if (addedAny) {
			// The vanilla multiplayer screen only reads servers.dat once, so open a fresh one to show the new entries.
			Nav.open(this.minecraft, new JoinMultiplayerScreen(new TitleScreen()));
		} else {
			Nav.open(this.minecraft, parent);
		}
	}
}

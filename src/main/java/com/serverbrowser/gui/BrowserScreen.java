package com.serverbrowser.gui;

import com.serverbrowser.GameInfo;
import com.serverbrowser.Nav;
import com.serverbrowser.config.Favorites;
import com.serverbrowser.config.ModConfig;
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
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The server browser. Servers are shown a page at a time and only the current page is pinged.
 * Click a server to add it to your server list. Click the star to favorite it.
 */
public class BrowserScreen extends Screen {
	private static final int ROW_H = 36;
	private static final String ALL = "\u0000all";

	/** PLAYERS and PING (and the Hide empty / Compatible filters) need every server's status, so they check all servers. */
	private enum Sort { ORDER, NAME, PLAYERS, PING }

	private final Screen parent;

	private List<ServerEntry> all = List.of();
	private List<ServerEntry> filtered = List.of();
	private List<ServerEntry> view = List.of();
	private final List<String> categories = new ArrayList<>();
	private String category = ALL;
	private Sort sort = Sort.ORDER;
	private boolean hideEmpty = ModConfig.get().hideEmptyByDefault;
	private boolean favOnly;
	private boolean compatOnly;
	private String searchText = "";
	private int page;
	private int totalPages = 1;

	private EditBox search;
	private Button[] addBtns = new Button[0];
	private Button[] starBtns = new Button[0];
	private int pendingCount;
	private int staticTotal;
	private boolean needAll;
	private Button categoryBtn, sortBtn, hideBtn, favBtn, compatBtn, prevBtn, nextBtn;

	private double scroll;
	private int listTop, listBottom, rowLeft, rowWidth;

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
		super(Component.translatable("serverbrowser.screen.title"));
		this.parent = parent;
	}



	@Override
	protected void init() {
		rowWidth = Math.min(this.width - 20, 440);
		rowLeft = (this.width - rowWidth) / 2;
		listTop = 74;
		listBottom = this.height - 46;

		search = new EditBox(this.font, rowLeft, 22, Math.max(80, rowWidth - 130), 20, Component.translatable("serverbrowser.search"));
		search.setHint(Component.translatable("serverbrowser.search.hint"));
		search.setMaxLength(64);
		search.setValue(searchText);
		search.setResponder(s -> {
			searchText = s;
			resetView();
		});
		this.addRenderableWidget(search);

		int right = rowLeft + rowWidth;
		this.addRenderableWidget(Button.builder(Component.translatable("serverbrowser.button.refresh"), b -> reload(true))
				.bounds(right - 124, 22, 60, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("serverbrowser.button.settings"),
						b -> Nav.open(this.minecraft, new ConfigScreen(this)))
				.bounds(right - 60, 22, 60, 20).build());

		int bw = (rowWidth - 16) / 5;
		categoryBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> cycleCategory())
				.bounds(rowLeft, 46, bw, 20).build());
		sortBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
			resetView();
		}).bounds(rowLeft + (bw + 4), 46, bw, 20).build());
		hideBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			hideEmpty = !hideEmpty;
			resetView();
		}).bounds(rowLeft + 2 * (bw + 4), 46, bw, 20).build());
		favBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			favOnly = !favOnly;
			resetView();
		}).bounds(rowLeft + 3 * (bw + 4), 46, bw, 20).build());
		compatBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			compatOnly = !compatOnly;
			resetView();
		}).bounds(rowLeft + 4 * (bw + 4), 46, bw, 20).build());

		int cx = this.width / 2;
		prevBtn = this.addRenderableWidget(Button.builder(Component.translatable("serverbrowser.button.prev"), b -> goToPage(page - 1))
				.bounds(cx - 144, this.height - 26, 80, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(cx - 60, this.height - 26, 120, 20).build());
		nextBtn = this.addRenderableWidget(Button.builder(Component.translatable("serverbrowser.button.next"), b -> goToPage(page + 1))
				.bounds(cx + 64, this.height - 26, 80, 20).build());

		// One Add button and one star button per row on a page. They are real buttons, positioned each frame.
		int rows = Math.max(1, ModConfig.get().pageSize);
		addBtns = new Button[rows];
		starBtns = new Button[rows];
		for (int i = 0; i < rows; i++) {
			final int idx = i;
			addBtns[i] = this.addWidget(Button.builder(Component.translatable("serverbrowser.row.add"), b -> addServer(rowEntry(idx)))
					.bounds(0, 0, 38, 20).build());
			starBtns[i] = this.addWidget(Button.builder(Component.literal("\u2606"), b -> toggleFavorite(rowEntry(idx)))
					.bounds(0, 0, 20, 20).build());
			addBtns[i].visible = false;
			starBtns[i].visible = false;
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

	private void reload(boolean force) {
		resetBatch();
		loading = true;
		setStatus(Component.translatable("serverbrowser.status.loading"), 0xFFAAAAAA);
		CompletableFuture
				.supplyAsync(() -> ServerListFetcher.load(force))
				.thenAccept(result -> this.minecraft.execute(() -> onLoaded(result)))
				.exceptionally(ex -> {
					this.minecraft.execute(() -> {
						loading = false;
						setStatus(Component.translatable("serverbrowser.status.load_failed"), 0xFFFF5555);
					});
					return null;
				});
	}

	private void onLoaded(ServerListFetcher.Result result) {
		if (closed) return;
		for (ServerEntry old : all) IconCache.release(old);
		all = result.servers();
		loading = false;

		categories.clear();
		for (ServerEntry e : all) {
			if (categories.stream().noneMatch(c -> c.equalsIgnoreCase(e.category))) categories.add(e.category);
		}
		categories.sort(String.CASE_INSENSITIVE_ORDER);
		if (categories.stream().noneMatch(c -> c.equals(category))) category = ALL;

		existing = ServerAdder.existing(this.minecraft);

		String sourceKey = switch (result.source()) {
			case ONLINE -> "serverbrowser.source.online";
			case CACHE -> "serverbrowser.source.cache";
			case OFFLINE_CACHE -> "serverbrowser.source.offline_cache";
			default -> "serverbrowser.source.bundled";
		};
		if (result.source() == ServerListFetcher.Source.BUNDLED && !ModConfig.get().isListUrlConfigured()) {
			setStatus(Component.translatable("serverbrowser.status.no_url"), 0xFFFFFF55);
		} else {
			setStatus(Component.translatable("serverbrowser.status.loaded", all.size(), Component.translatable(sourceKey)), 0xFFAAAAAA);
		}
		updateLabels();
		resetView();
	}


	private void resetBatch() {
		batch.cancel();
		batch = new ServerPinger.Batch();
	}

	/** Filters/search/sort changed: go back to page 1 and drop queued pings for the old page. */
	private void resetView() {
		page = 0;
		scroll = 0;
		resetBatch();
		updateLabels();
		dirty = true;
	}

	private void goToPage(int newPage) {
		page = Math.max(0, Math.min(totalPages - 1, newPage));
		scroll = 0;
		resetBatch();
		dirty = true;
	}

	private void cycleCategory() {
		List<String> options = new ArrayList<>();
		options.add(ALL);
		options.addAll(categories);
		int idx = options.indexOf(category);
		category = options.get((idx + 1) % options.size());
		resetView();
	}

	private static Component onOff(boolean b) {
		return Component.translatable(b ? "serverbrowser.on" : "serverbrowser.off");
	}

	private void updateLabels() {
		if (categoryBtn == null) return;
		Component cat = category.equals(ALL) ? Component.translatable("serverbrowser.filter.category.all") : Component.literal(category);
		categoryBtn.setMessage(Component.translatable("serverbrowser.filter.category", cat));
		sortBtn.setMessage(Component.translatable("serverbrowser.filter.sort",
				Component.translatable("serverbrowser.sort." + sort.name().toLowerCase(Locale.ROOT))));
		hideBtn.setMessage(Component.translatable("serverbrowser.filter.hide_empty", onOff(hideEmpty)));
		favBtn.setMessage(Component.translatable("serverbrowser.filter.favorites", onOff(favOnly)));
		compatBtn.setMessage(Component.translatable("serverbrowser.filter.compatible", onOff(compatOnly)));
		compatBtn.active = GameInfo.protocol() > 0;
	}

	/** 1 = compatible, 0 = incompatible, -1 = unknown. */
	private static int compat(ServerEntry e) {
		int mine = GameInfo.protocol();
		if (mine <= 0 || e.state != ServerEntry.State.ONLINE || e.protocol <= 0) return -1;
		return e.protocol == mine ? 1 : 0;
	}

	private void rebuildView() {
		lastRebuild = System.currentTimeMillis();
		dirty = false;
		String q = searchText.trim().toLowerCase(Locale.ROOT);

		// 1) Filters that don't need ping data.
		List<ServerEntry> out = new ArrayList<>();
		for (ServerEntry e : all) {
			if (!category.equals(ALL) && !e.category.equalsIgnoreCase(category)) continue;
			if (!q.isEmpty() && !(e.name.toLowerCase(Locale.ROOT).contains(q)
					|| e.ip.toLowerCase(Locale.ROOT).contains(q)
					|| e.category.toLowerCase(Locale.ROOT).contains(q)
					|| e.description.toLowerCase(Locale.ROOT).contains(q))) continue;
			if (favOnly && !Favorites.has(e.ip)) continue;
			out.add(e);
		}

		// 2) Sorting by players/ping and the Hide empty / Compatible filters need every server's status,
		//    so in those modes all servers are checked (with progress shown). Otherwise only the current page is.
		needAll = sort == Sort.PLAYERS || sort == Sort.PING || hideEmpty || compatOnly;
		staticTotal = out.size();
		pendingCount = 0;
		for (ServerEntry e : out) {
			if (e.state == ServerEntry.State.IDLE || e.state == ServerEntry.State.PINGING) pendingCount++;
		}

		if (needAll) {
			ServerPinger.request(out, batch, () -> dirty = true);
			List<ServerEntry> kept = new ArrayList<>();
			for (ServerEntry e : out) {
				if (hideEmpty && (e.state == ServerEntry.State.OFFLINE
						|| (e.state == ServerEntry.State.ONLINE && e.online <= 0))) continue;
				if (compatOnly && compat(e) == 0) continue;
				kept.add(e);
			}
			out = kept;
			if (sort == Sort.PLAYERS) {
				out.sort(Comparator.comparingInt(BrowserScreen::playersRank).reversed());
			} else if (sort == Sort.PING) {
				out.sort(Comparator.comparingInt(e -> e.state == ServerEntry.State.ONLINE && e.pingMs >= 0 ? e.pingMs : Integer.MAX_VALUE));
			}
		}
		if (sort == Sort.NAME) out.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
		filtered = out;

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

	private void toggleFavorite(ServerEntry e) {
		if (e == null) return;
		Favorites.toggle(e.ip);
		lastRebuild = 0;
		dirty = true;
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
			addBtns[i].active = show;
			starBtns[i].active = show;
			if (!show) continue;
			boolean added = existing.contains(ServerAdder.normalize(e.ip));
			addBtns[i].setX(right - 66);
			addBtns[i].setY(top + 7);
			starBtns[i].setX(right - 24);
			starBtns[i].setY(top + 7);
			addBtns[i].setMessage(Component.translatable(added ? "serverbrowser.row.added" : "serverbrowser.row.add"));
			addBtns[i].active = !added;
			starBtns[i].setMessage(Component.literal(Favorites.has(e.ip) ? "\u2605" : "\u2606"));
		}
	}

	private int maxScroll() {
		return Math.max(0, view.size() * ROW_H - (listBottom - listTop));
	}

	private void setStatus(Component text, int color) {
		status = text;
		statusColor = color;
	}


	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (dirty && System.currentTimeMillis() - lastRebuild > 250) rebuildView();
		layoutRowButtons();

		super.extractRenderState(g, mouseX, mouseY, delta);

		g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 8, 0xFFFFFFFF, true);

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
			Component msg = Component.translatable(loading ? "serverbrowser.loading" : "serverbrowser.empty");
			g.text(this.font, msg, this.width / 2 - this.font.width(msg) / 2, listTop + 24, 0xFFAAAAAA, true);
		}
		g.disableScissor();

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
			}
		}

		if (needAll && pendingCount > 0) {
			Component checking = Component.translatable("serverbrowser.status.checking", staticTotal - pendingCount, staticTotal);
			g.text(this.font, checking, rowLeft, listBottom + 6, 0xFFFFFF55, true);
		} else {
			g.text(this.font, status, rowLeft, listBottom + 6, statusColor, true);
		}
		Component pageInfo = Component.translatable("serverbrowser.page", page + 1, totalPages, filtered.size());
		g.text(this.font, pageInfo, rowLeft + rowWidth - this.font.width(pageInfo), listBottom + 6, 0xFF888888, true);
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
			String letter = e.name.substring(0, 1).toUpperCase(Locale.ROOT);
			g.text(this.font, letter, left + 17 - this.font.width(letter) / 2, top + 13, 0xFFFFFFFF, true);
		}

		boolean added = existing.contains(ServerAdder.normalize(e.ip));
		int textX = left + 38;
		int rightCol = right - 72;
		int maxText = rightCol - 84 - textX;

		String title = (added ? "\u2714 " : "") + e.name;
		g.text(this.font, fit(title, maxText), textX, top + 6, added ? 0xFF55FF55 : 0xFFFFFFFF, true);

		String sub = (e.state == ServerEntry.State.ONLINE && !e.motd.isEmpty()) ? e.motd
				: (e.description.isEmpty() ? e.category : e.description);
		g.text(this.font, fit(sub, maxText), textX, top + 19, 0xFFAAAAAA, false);

		String players;
		int playersColor;
		switch (e.state) {
			case ONLINE -> {
				players = e.online + "/" + e.max;
				playersColor = e.online > 0 ? 0xFF55FF55 : 0xFFAAAAAA;
			}
			case OFFLINE -> {
				players = Component.translatable("serverbrowser.state.offline").getString();
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
			int infoColor = compat(e) == 0 ? 0xFFFF7777 : 0xFF888888;
			g.text(this.font, info, rightCol - this.font.width(info), top + 19, infoColor, false);
		}

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


	private ServerEntry entryAt(double mx, double my) {
		if (my < listTop || my >= listBottom || mx < rowLeft || mx >= rowLeft + rowWidth) return null;
		int idx = (int) ((my - listTop + (int) scroll) / ROW_H);
		return (idx >= 0 && idx < view.size()) ? view.get(idx) : null;
	}

	/** Fallback: a click on the body of a row (not on its buttons) also adds the server, if the screen receives it. */
	boolean onListClick(double mx, double my, int button) {
		if (button != 0) return false;
		if (mx >= rowLeft + rowWidth - 70) return false;
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
				setStatus(Component.translatable("serverbrowser.status.added", e.name), 0xFF55FF55);
			}
			case ALREADY -> {
				existing.add(ServerAdder.normalize(e.ip));
				setStatus(Component.translatable("serverbrowser.status.already", e.name), 0xFFFFFF55);
			}
			case FAILED -> setStatus(Component.translatable("serverbrowser.status.failed"), 0xFFFF5555);
		}
	}


	@Override
	public void removed() {
		closed = true;
		batch.cancel();
		for (ServerEntry e : all) IconCache.release(e);
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

package com.serverbrowser.gui;

import com.serverbrowser.Lang;
import com.serverbrowser.Nav;
import com.serverbrowser.config.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** In-game settings. Also opened from Mod Menu. */
public class ConfigScreen extends Screen {
	private static final int[] LIST_CACHE = {5, 15, 30, 60, 180};
	private static final int[] STATUS_CACHE = {1, 2, 5, 10, 30, 60};
	private static final int[] TIMEOUT = {1500, 3000, 5000, 8000};
	private static final int[] CONCURRENT = {4, 8, 16, 32};
	private static final int[] PAGE = {5, 10, 15, 20, 30, 50};
	private static final int[] HISTORY = {20, 30, 40, 50};
	private static final int ROW = 22;

	private final Screen parent;
	private EditBox url;
	private Button listCacheBtn, statusCacheBtn, timeoutBtn, concurrentBtn, pageBtn, historyBtn, hideBtn, langBtn;

	public ConfigScreen(Screen parent) {
		super(Lang.tr("serverbrowser.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModConfig c = ModConfig.get();
		int cx = this.width / 2;
		int x = cx - 150;

		url = new EditBox(this.font, x, 44, 300, 20, Lang.tr("serverbrowser.config.url"));
		url.setMaxLength(512);
		url.setValue(c.listUrl);
		this.addRenderableWidget(url);

		int y = 70;
		listCacheBtn = cycle(x, y, () -> c.cacheMinutes = next(LIST_CACHE, c.cacheMinutes));
		y += ROW;
		statusCacheBtn = cycle(x, y, () -> c.statusCacheMinutes = next(STATUS_CACHE, c.statusCacheMinutes));
		y += ROW;
		timeoutBtn = cycle(x, y, () -> c.pingTimeoutMs = next(TIMEOUT, c.pingTimeoutMs));
		y += ROW;
		concurrentBtn = cycle(x, y, () -> c.maxConcurrentPings = next(CONCURRENT, c.maxConcurrentPings));
		y += ROW;
		pageBtn = cycle(x, y, () -> c.pageSize = next(PAGE, c.pageSize));
		y += ROW;
		historyBtn = cycle(x, y, () -> c.historySize = next(HISTORY, c.historySize));
		y += ROW;
		hideBtn = cycle(x, y, () -> c.hideOfflineByDefault = !c.hideOfflineByDefault);
		y += ROW;

		// Language: cycles through "auto" and every language pack. The screen is reopened so all text switches at once.
		langBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			c.language = Lang.next(c.language);
			c.listUrl = url.getValue().trim();
			ModConfig.save();
			Nav.open(this.minecraft, new ConfigScreen(parent));
		}).bounds(x, y, 300, 20).build());

		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.button.done"), b -> onClose())
				.bounds(cx - 100, Math.max(this.height - 26, y + 26), 200, 20).build());

		labels();
	}

	/** A full-width button that runs {@code change} when clicked and then refreshes all labels. */
	private Button cycle(int x, int y, Runnable change) {
		return this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			change.run();
			labels();
		}).bounds(x, y, 300, 20).build());
	}

	private void labels() {
		ModConfig c = ModConfig.get();
		listCacheBtn.setMessage(Lang.tr("serverbrowser.config.cache", c.cacheMinutes));
		statusCacheBtn.setMessage(Lang.tr("serverbrowser.config.status_cache", c.statusCacheMinutes));
		timeoutBtn.setMessage(Lang.tr("serverbrowser.config.timeout", c.pingTimeoutMs));
		concurrentBtn.setMessage(Lang.tr("serverbrowser.config.concurrent", c.maxConcurrentPings));
		pageBtn.setMessage(Lang.tr("serverbrowser.config.page_size", c.pageSize));
		historyBtn.setMessage(Lang.tr("serverbrowser.config.history_size", c.historySize));
		hideBtn.setMessage(Lang.tr("serverbrowser.config.hide_offline",
				Lang.tr(c.hideOfflineByDefault ? "serverbrowser.on" : "serverbrowser.off")));
		Component langName = Lang.AUTO.equals(c.language)
				? Lang.tr("serverbrowser.language.auto")
				: Component.literal(Lang.displayName(c.language));
		langBtn.setMessage(Lang.tr("serverbrowser.config.language", langName));
	}

	private static int next(int[] values, int current) {
		for (int v : values) if (v > current) return v;
		return values[0];
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 14, 0xFFFFFFFF, true);
		g.text(this.font, Lang.tr("serverbrowser.config.url"), this.width / 2 - 150, 32, 0xFFAAAAAA, true);
	}

	@Override
	public void onClose() {
		ModConfig.get().listUrl = url.getValue().trim();
		ModConfig.save();
		Nav.open(this.minecraft, parent);
	}
}

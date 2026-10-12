package com.serverbrowser.gui;

import com.serverbrowser.Nav;
import com.serverbrowser.config.ModConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** In-game settings. Also opened from Mod Menu. */
public class ConfigScreen extends Screen {
	private static final int[] CACHE = {5, 15, 30, 60, 180};
	private static final int[] TIMEOUT = {1500, 3000, 5000, 8000};
	private static final int[] CONCURRENT = {4, 8, 16, 32};
	private static final int[] PAGE = {5, 10, 15, 20, 30, 50};

	private final Screen parent;
	private EditBox url;
	private Button cacheBtn, timeoutBtn, concurrentBtn, hideBtn, pageBtn;

	public ConfigScreen(Screen parent) {
		super(Component.translatable("serverbrowser.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ModConfig c = ModConfig.get();
		int cx = this.width / 2;

		url = new EditBox(this.font, cx - 150, 50, 300, 20, Component.translatable("serverbrowser.config.url"));
		url.setMaxLength(512);
		url.setValue(c.listUrl);
		this.addRenderableWidget(url);

		cacheBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			c.cacheMinutes = next(CACHE, c.cacheMinutes);
			labels();
		}).bounds(cx - 150, 82, 300, 20).build());

		timeoutBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			c.pingTimeoutMs = next(TIMEOUT, c.pingTimeoutMs);
			labels();
		}).bounds(cx - 150, 106, 300, 20).build());

		concurrentBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			c.maxConcurrentPings = next(CONCURRENT, c.maxConcurrentPings);
			labels();
		}).bounds(cx - 150, 130, 300, 20).build());

		hideBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			c.hideEmptyByDefault = !c.hideEmptyByDefault;
			labels();
		}).bounds(cx - 150, 154, 300, 20).build());

		pageBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			c.pageSize = next(PAGE, c.pageSize);
			labels();
		}).bounds(cx - 150, 178, 300, 20).build());

		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(cx - 100, this.height - 28, 200, 20).build());

		labels();
	}

	private void labels() {
		ModConfig c = ModConfig.get();
		cacheBtn.setMessage(Component.translatable("serverbrowser.config.cache", c.cacheMinutes));
		timeoutBtn.setMessage(Component.translatable("serverbrowser.config.timeout", c.pingTimeoutMs));
		concurrentBtn.setMessage(Component.translatable("serverbrowser.config.concurrent", c.maxConcurrentPings));
		pageBtn.setMessage(Component.translatable("serverbrowser.config.page_size", c.pageSize));
		hideBtn.setMessage(Component.translatable("serverbrowser.config.hide_empty",
				Component.translatable(c.hideEmptyByDefault ? "serverbrowser.on" : "serverbrowser.off")));
	}

	private static int next(int[] values, int current) {
		for (int v : values) if (v > current) return v;
		return values[0];
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 16, 0xFFFFFFFF, true);
		g.text(this.font, Component.translatable("serverbrowser.config.url"), this.width / 2 - 150, 38, 0xFFAAAAAA, true);
	}

	@Override
	public void onClose() {
		ModConfig.get().listUrl = url.getValue().trim();
		ModConfig.save();
		Nav.open(this.minecraft, parent);
	}
}

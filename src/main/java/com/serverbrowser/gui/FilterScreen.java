package com.serverbrowser.gui;

import com.serverbrowser.GameInfo;
import com.serverbrowser.Lang;
import com.serverbrowser.Nav;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;

/** Granular filters (offline, full, empty, locked, mods, version) and game mode tags as pills. */
public class FilterScreen extends Screen {
	private static final int ROW = 22;

	private final BrowserScreen parent;
	private final Filters f;
	private final Map<String, Integer> tagCounts;

	private Button offlineBtn, fullBtn, emptyBtn, lockedBtn, modsBtn, compatBtn;
	private int tagsLabelY;

	FilterScreen(BrowserScreen parent, Filters filters, Map<String, Integer> tagCounts) {
		super(Lang.tr("serverbrowser.filters.title"));
		this.parent = parent;
		this.f = filters;
		this.tagCounts = tagCounts;
	}

	private static Component onOff(boolean b) {
		return Lang.tr(b ? "serverbrowser.on" : "serverbrowser.off");
	}

	private static Component mode(Filters.Mode m) {
		return Lang.tr("serverbrowser.filters.mode." + m.name().toLowerCase(Locale.ROOT));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int x = cx - 150;
		int y = 24;

		offlineBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			f.hideOffline = !f.hideOffline;
			labels();
		}).bounds(x, y, 300, 20).build());
		y += ROW;
		fullBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			f.full = f.full.next();
			labels();
		}).bounds(x, y, 300, 20).build());
		y += ROW;
		emptyBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			f.empty = f.empty.next();
			labels();
		}).bounds(x, y, 300, 20).build());
		y += ROW;
		lockedBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			f.locked = f.locked.next();
			labels();
		}).bounds(x, y, 300, 20).build());
		y += ROW;
		modsBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			f.mods = f.mods.next();
			labels();
		}).bounds(x, y, 300, 20).build());
		y += ROW;
		compatBtn = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			f.compatOnly = !f.compatOnly;
			labels();
		}).bounds(x, y, 300, 20).build());
		y += ROW;
		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.filters.reset"), b -> {
			f.reset();
			Nav.open(this.minecraft, new FilterScreen(parent, f, tagCounts)); // reopen so every button refreshes
		}).bounds(x, y, 300, 20).build());
		y += ROW + 6;

		// Game mode tags as pills. Several can be selected: a server matches if it has any of them.
		tagsLabelY = y;
		y += 12;
		int px = x;
		int limitY = this.height - 34;
		for (Map.Entry<String, Integer> en : tagCounts.entrySet()) {
			String tag = en.getKey();
			int w = this.font.width(tag + " (" + en.getValue() + ")") + 24; // room for the check mark
			if (px + w > x + 300) {
				px = x;
				y += 22;
			}
			if (y + 18 > limitY) break; // no more room on this screen
			this.addRenderableWidget(Button.builder(pillLabel(tag, en.getValue()), b -> {
				f.toggleTag(tag);
				b.setMessage(pillLabel(tag, en.getValue()));
			}).bounds(px, y, w, 18).build());
			px += w + 4;
		}

		this.addRenderableWidget(Button.builder(Lang.tr("serverbrowser.button.done"), b -> onClose())
				.bounds(cx - 100, this.height - 26, 200, 20).build());
		labels();
	}

	private Component pillLabel(String tag, int count) {
		return Component.literal((f.hasTag(tag) ? "\u2714 " : "") + tag + " (" + count + ")");
	}

	private void labels() {
		offlineBtn.setMessage(Lang.tr("serverbrowser.filter.hide_offline", onOff(f.hideOffline)));
		fullBtn.setMessage(Lang.tr("serverbrowser.filters.full", mode(f.full)));
		emptyBtn.setMessage(Lang.tr("serverbrowser.filters.empty", mode(f.empty)));
		lockedBtn.setMessage(Lang.tr("serverbrowser.filters.locked", mode(f.locked)));
		modsBtn.setMessage(Lang.tr("serverbrowser.filters.mods", mode(f.mods)));
		compatBtn.setMessage(Lang.tr("serverbrowser.filter.compatible", onOff(f.compatOnly)));
		compatBtn.active = GameInfo.protocol() > 0;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.text(this.font, this.title, this.width / 2 - this.font.width(this.title) / 2, 8, 0xFFFFFFFF, true);
		g.text(this.font, Lang.tr("serverbrowser.filters.tags"), this.width / 2 - 150, tagsLabelY, 0xFFAAAAAA, true);
	}

	@Override
	public void onClose() {
		parent.filtersChanged();
		Nav.open(this.minecraft, parent);
	}
}

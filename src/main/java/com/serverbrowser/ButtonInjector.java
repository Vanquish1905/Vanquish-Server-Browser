package com.serverbrowser;

import com.serverbrowser.gui.BrowserScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Adds the "Browse Servers" button to the Multiplayer screen using Fabric's screen events (no Mixins). */
final class ButtonInjector {
	/** Buttons we created, so a repeated init of the same screen never leaves a second copy behind. */
	private static final Set<AbstractWidget> MINE = Collections.newSetFromMap(new WeakHashMap<>());
	private static boolean warned;

	private ButtonInjector() {}

	static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
		if (!(screen instanceof JoinMultiplayerScreen multiplayer)) return;

		List<AbstractWidget> widgets = ScreenWidgets.of(screen);
		if (widgets == null) {
			if (!warned) {
				warned = true;
				ServerBrowserClient.LOGGER.warn("Could not access the screen widget list; Browse Servers button not added");
			}
			return;
		}

		// This screen can be initialised more than once without its widgets being cleared. Remove our old button first.
		for (AbstractWidget w : new ArrayList<>(widgets)) {
			if (MINE.contains(w)) {
				try {
					widgets.remove(w);
				} catch (RuntimeException ignored) {
				}
				MINE.remove(w);
			}
		}

		final int bw = 104, bh = 20;
		final int x = width - bw - 6;
		int y = 6;
		// If another mod already put something in the top-right corner, slide down until it is free.
		for (int i = 0; i < 8 && overlaps(widgets, width, x, y, bw, bh); i++) {
			y += bh + 2;
		}

		Button button = Button.builder(
				Component.translatable("serverbrowser.button.browse"),
				b -> Nav.open(client, new BrowserScreen(multiplayer))
		).bounds(x, y, bw, bh).build();
		MINE.add(button);
		widgets.add(button);
	}

	private static boolean overlaps(List<AbstractWidget> widgets, int screenWidth, int x, int y, int w, int h) {
		for (AbstractWidget other : widgets) {
			// Skip very wide widgets (the vanilla server list itself) so they don't push us off the screen.
			if (other.getWidth() >= screenWidth * 0.6) continue;
			boolean hit = x < other.getX() + other.getWidth() && x + w > other.getX()
					&& y < other.getY() + other.getHeight() && y + h > other.getY();
			if (hit) return true;
		}
		return false;
	}
}

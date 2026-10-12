package com.serverbrowser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** All screen switching goes through here, so a future API rename is a one-line fix. */
public final class Nav {
	private Nav() {}

	public static void open(Minecraft mc, Screen screen) {
		mc.gui.setScreen(screen);
	}
}

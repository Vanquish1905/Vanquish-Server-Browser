package com.serverbrowser;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Gets Fabric API's mutable widget list for a screen. Fabric has used different method names across
 * versions, so we try the known ones reflectively instead of hard-linking to one.
 */
final class ScreenWidgets {
	private ScreenWidgets() {}

	@SuppressWarnings("unchecked")
	static List<AbstractWidget> of(Screen screen) {
		for (String name : new String[]{"getWidgets", "getButtons"}) {
			try {
				Method m = Screens.class.getMethod(name, Screen.class);
				return (List<AbstractWidget>) m.invoke(null, screen);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		return null;
	}
}

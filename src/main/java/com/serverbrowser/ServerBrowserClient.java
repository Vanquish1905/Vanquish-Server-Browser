package com.serverbrowser;

import com.serverbrowser.config.Favorites;
import com.serverbrowser.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerBrowserClient implements ClientModInitializer {
	public static final String MOD_ID = "serverbrowser";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		ModConfig.load();
		Lang.load();
		Favorites.load();
		ScreenEvents.AFTER_INIT.register(ButtonInjector::onScreenInit);
		LOGGER.info("Server Browser loaded (game {}, protocol {})", GameInfo.gameVersion(), GameInfo.protocol());
	}
}

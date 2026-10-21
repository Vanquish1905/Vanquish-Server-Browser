package com.serverbrowser;

import com.serverbrowser.config.History;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Notes every multiplayer server the player joins (any way of joining), and how long they stay. */
final class HistoryTracker {
	private HistoryTracker() {}

	static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			String[] server = GameInfo.currentServer(client);
			if (server != null) History.onJoin(server[0], server[1]);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> History.onLeave(false));
		// The game is closing while connected: write the history before the process ends.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> History.onLeave(true));
	}
}

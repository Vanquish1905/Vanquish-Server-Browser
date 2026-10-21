package com.serverbrowser;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;

public final class GameInfo {
	private static int protocol = Integer.MIN_VALUE;

	private GameInfo() {}

	public static String gameVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	/** {name, address} of the multiplayer server the player is on, or null (singleplayer, LAN, unknown). */
	public static String[] currentServer(net.minecraft.client.Minecraft mc) {
		try {
			Object data = net.minecraft.client.Minecraft.class.getMethod("getCurrentServer").invoke(mc);
			if (data instanceof net.minecraft.client.multiplayer.ServerData server && server.ip != null && !server.ip.isBlank()) {
				return new String[]{server.name == null ? "" : server.name, server.ip};
			}
		} catch (Throwable t) {
			ServerBrowserClient.LOGGER.debug("Could not read the current server", t);
		}
		return null;
	}

	/** Current client protocol number, or -1 if it cannot be determined. Looked up reflectively so a rename never breaks the build. */
	public static synchronized int protocol() {
		if (protocol != Integer.MIN_VALUE) return protocol;
		protocol = -1;
		try {
			Class<?> shared = Class.forName("net.minecraft.SharedConstants");
			Method get = shared.getMethod("getCurrentVersion");
			Object version = get.invoke(null);
			for (String name : new String[]{"protocolVersion", "getProtocolVersion"}) {
				try {
					Method m = get.getReturnType().getMethod(name);
					protocol = ((Number) m.invoke(version)).intValue();
					return protocol;
				} catch (NoSuchMethodException ignored) {
				}
			}
		} catch (Throwable t) {
			ServerBrowserClient.LOGGER.debug("Could not read protocol version", t);
		}
		return protocol;
	}
}

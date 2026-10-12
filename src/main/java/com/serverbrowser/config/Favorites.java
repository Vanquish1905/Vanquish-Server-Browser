package com.serverbrowser.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.serverbrowser.ServerBrowserClient;
import com.serverbrowser.data.ServerAdder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Favorites {
	private static final Set<String> SET = ConcurrentHashMap.newKeySet();

	private Favorites() {}

	private static Path file() {
		return ModConfig.dir().resolve("favorites.json");
	}

	public static void load() {
		SET.clear();
		try {
			Path f = file();
			if (Files.exists(f)) {
				JsonArray arr = JsonParser.parseString(Files.readString(f)).getAsJsonArray();
				for (JsonElement e : arr) SET.add(ServerAdder.normalize(e.getAsString()));
			}
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not read favorites", e);
		}
	}

	public static boolean has(String ip) {
		return SET.contains(ServerAdder.normalize(ip));
	}

	/** Toggles a favorite and returns the new state. */
	public static boolean toggle(String ip) {
		String key = ServerAdder.normalize(ip);
		boolean now = SET.add(key);
		if (!now) SET.remove(key);
		save();
		return now;
	}

	private static void save() {
		try {
			JsonArray arr = new JsonArray();
			SET.stream().sorted().forEach(arr::add);
			Files.writeString(file(), arr.toString());
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not save favorites", e);
		}
	}
}

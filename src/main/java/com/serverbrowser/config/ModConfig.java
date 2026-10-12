package com.serverbrowser.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.serverbrowser.ServerBrowserClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {
	/** The server list the mod loads by default. Can be changed in-game under Settings. */
	public static final String DEFAULT_LIST_URL = "https://raw.githubusercontent.com/Vanquish1905/minecraft-server-list/refs/heads/main/servers.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ModConfig instance = new ModConfig();

	public String listUrl = DEFAULT_LIST_URL;
	public int cacheMinutes = 30;
	public int pingTimeoutMs = 3000;
	public int maxConcurrentPings = 8;
	public boolean hideEmptyByDefault = false;
	public int pageSize = 10;
	public int pingCacheSeconds = 60;

	public static ModConfig get() {
		return instance;
	}

	/** Folder for the cache and favorites: config/serverbrowser/ */
	public static Path dir() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("serverbrowser");
		try {
			Files.createDirectories(dir);
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not create config directory", e);
		}
		return dir;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("serverbrowser.json");
	}

	public static void load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				ModConfig loaded = GSON.fromJson(Files.readString(f), ModConfig.class);
				if (loaded != null) instance = loaded;
			}
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not read config, using defaults", e);
			instance = new ModConfig();
		}
		save();
	}

	public static void save() {
		instance.clamp();
		try {
			Files.writeString(file(), GSON.toJson(instance));
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not save config", e);
		}
	}

	public boolean isListUrlConfigured() {
		return listUrl.startsWith("https://");
	}

	private void clamp() {
		// Also replaces the old placeholder URL that earlier builds saved into the config file.
		listUrl = (listUrl == null || listUrl.isBlank() || listUrl.contains("YOUR_USERNAME")) ? DEFAULT_LIST_URL : listUrl.trim();
		cacheMinutes = Math.max(1, Math.min(1440, cacheMinutes));
		pingTimeoutMs = Math.max(500, Math.min(15000, pingTimeoutMs));
		maxConcurrentPings = Math.max(1, Math.min(32, maxConcurrentPings));
		pageSize = Math.max(3, Math.min(50, pageSize));
		pingCacheSeconds = Math.max(10, Math.min(3600, pingCacheSeconds));
	}
}

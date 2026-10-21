package com.serverbrowser.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.serverbrowser.ServerBrowserClient;
import com.serverbrowser.config.ModConfig;
import com.serverbrowser.config.Persist;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remembers the last status of every server (players, MOTD, icon, ping), in memory and on disk
 * (config/serverbrowser/status_cache.json), so reopening the browser, even after restarting the game, shows data instantly.
 * Entries are refreshed in the background once they are older than the cache time in the settings.
 */
public final class PingCache {
	private static final class Cached {
		boolean online;
		int players, max, pingMs, protocol;
		String motd = "", version = "";
		byte[] icon;
		Set<String> mods = Set.of();
		long ts;
	}

	private static final Map<String, Cached> MAP = new ConcurrentHashMap<>();
	private static final AtomicBoolean FLUSH_PENDING = new AtomicBoolean();
	private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
	private static final int MAX_ICON_BYTES = 24 * 1024;

	private PingCache() {}

	private static Path file() {
		return ModConfig.dir().resolve("status_cache.json");
	}

	/** Reads the cache file on the background thread, so game start-up is not delayed. */
	public static void loadAsync() {
		Persist.run(PingCache::load);
	}

	private static void load() {
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonObject servers = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonObject("servers");
			if (servers == null) return;
			long now = System.currentTimeMillis();
			for (Map.Entry<String, JsonElement> en : servers.entrySet()) {
				JsonObject o = en.getValue().getAsJsonObject();
				long ts = o.has("ts") ? o.get("ts").getAsLong() : 0L;
				if (now - ts > MAX_AGE_MS) continue;
				Cached c = new Cached();
				c.ts = ts;
				c.online = o.has("online") && o.get("online").getAsBoolean();
				c.players = intOf(o, "players");
				c.max = intOf(o, "max");
				c.pingMs = intOf(o, "ping");
				c.protocol = intOf(o, "protocol");
				c.motd = strOf(o, "motd");
				c.version = strOf(o, "version");
				String icon = strOf(o, "icon");
				if (!icon.isEmpty()) c.icon = Base64.getDecoder().decode(icon);
				if (o.has("mods")) {
					Set<String> mods = new HashSet<>();
					for (JsonElement m : o.getAsJsonArray("mods")) mods.add(m.getAsString());
					c.mods = Set.copyOf(mods);
				}
				MAP.putIfAbsent(en.getKey(), c); // a ping that already finished is newer than the file
			}
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not read status cache: {}", e.toString());
		}
	}

	/** Fills in a server that has not been checked yet from the cache, if it has an entry. */
	public static void applyTo(ServerEntry e) {
		if (e.state != ServerEntry.State.IDLE) return;
		Cached c = MAP.get(ServerAdder.normalize(e.ip));
		if (c == null) return;
		long age = System.currentTimeMillis() - c.ts;
		long ttl = ModConfig.get().statusCacheMinutes * 60_000L;
		if (!c.online && age > ttl) return; // an old "offline" result is not trusted, check again
		if (c.online) {
			e.online = c.players;
			e.max = c.max;
			e.pingMs = c.pingMs;
			e.protocol = c.protocol;
			e.motd = c.motd;
			e.versionName = c.version;
			e.pingMods = c.mods;
			if (c.icon != null) e.iconBytes = c.icon;
			Mods.refresh(e);
		}
		e.lastPingMs = c.ts;
		e.state = c.online ? ServerEntry.State.ONLINE : ServerEntry.State.OFFLINE;
	}

	/** Remembers the result of a finished ping. */
	public static void store(ServerEntry e) {
		if (e.state != ServerEntry.State.ONLINE && e.state != ServerEntry.State.OFFLINE) return;
		Cached c = new Cached();
		c.ts = e.lastPingMs > 0 ? e.lastPingMs : System.currentTimeMillis();
		c.online = e.state == ServerEntry.State.ONLINE;
		if (c.online) {
			c.players = e.online;
			c.max = e.max;
			c.pingMs = e.pingMs;
			c.protocol = e.protocol;
			c.motd = e.motd;
			c.version = e.versionName;
			c.mods = e.pingMods;
			byte[] icon = e.iconBytes;
			if (icon != null && icon.length <= MAX_ICON_BYTES) c.icon = icon;
		}
		MAP.put(ServerAdder.normalize(e.ip), c);
		scheduleFlush();
	}

	/** Forgets everything (the Refresh button). */
	public static void clear() {
		MAP.clear();
		scheduleFlush();
	}

	private static void scheduleFlush() {
		if (FLUSH_PENDING.compareAndSet(false, true)) {
			Persist.later(() -> {
				FLUSH_PENDING.set(false);
				writeNow();
			}, 4000);
		}
	}

	/** Saves the cache soon (called when the browser closes). */
	public static void flushNow() {
		Persist.run(PingCache::writeNow);
	}

	private static void writeNow() {
		JsonObject servers = new JsonObject();
		for (Map.Entry<String, Cached> en : MAP.entrySet()) {
			Cached c = en.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("ts", c.ts);
			o.addProperty("online", c.online);
			if (c.online) {
				o.addProperty("players", c.players);
				o.addProperty("max", c.max);
				o.addProperty("ping", c.pingMs);
				o.addProperty("protocol", c.protocol);
				o.addProperty("motd", c.motd);
				o.addProperty("version", c.version);
				if (c.icon != null) o.addProperty("icon", Base64.getEncoder().encodeToString(c.icon));
				if (!c.mods.isEmpty()) {
					JsonArray mods = new JsonArray();
					c.mods.forEach(mods::add);
					o.add("mods", mods);
				}
			}
			servers.add(en.getKey(), o);
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.add("servers", servers);
		Persist.writeNow(file(), root.toString());
	}

	private static int intOf(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsInt() : -1;
	}

	private static String strOf(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return (e != null && e.isJsonPrimitive()) ? e.getAsString() : "";
	}
}

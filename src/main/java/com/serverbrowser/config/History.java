package com.serverbrowser.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.serverbrowser.ServerBrowserClient;
import com.serverbrowser.data.ServerAdder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The servers the player joined recently, with when they last joined and how long they played in total. */
public final class History {
	public static final class Entry {
		public final String ip;
		public String name;
		public long lastJoined;
		public long totalMs;
		public int joins;

		Entry(String ip, String name) {
			this.ip = ip;
			this.name = name == null ? "" : name;
		}
	}

	private static final Map<String, Entry> MAP = new LinkedHashMap<>(); // key: normalized address
	private static String currentKey;
	private static long sessionStart;

	private History() {}

	private static Path file() {
		return ModConfig.dir().resolve("history.json");
	}

	public static synchronized void load() {
		MAP.clear();
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonObject servers = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonObject("servers");
			if (servers == null) return;
			for (Map.Entry<String, JsonElement> en : servers.entrySet()) {
				JsonObject o = en.getValue().getAsJsonObject();
				Entry e = new Entry(en.getKey(), o.has("name") ? o.get("name").getAsString() : "");
				e.lastJoined = o.has("lastJoined") ? o.get("lastJoined").getAsLong() : 0L;
				e.totalMs = o.has("totalMs") ? o.get("totalMs").getAsLong() : 0L;
				e.joins = o.has("joins") ? o.get("joins").getAsInt() : 1;
				MAP.put(ServerAdder.normalize(e.ip), e);
			}
			trim();
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not read join history", e);
		}
	}

	/** Called when the player joins a multiplayer server. */
	public static synchronized void onJoin(String name, String ip) {
		endSession();
		String key = ServerAdder.normalize(ip);
		Entry e = MAP.computeIfAbsent(key, k -> new Entry(ip, name));
		if (name != null && !name.isBlank()) e.name = name;
		e.lastJoined = System.currentTimeMillis();
		e.joins++;
		currentKey = key;
		sessionStart = e.lastJoined;
		trim();
		save(false);
	}

	/** Called when the player leaves a server (or the game closes). Adds the session to the total play time. */
	public static synchronized void onLeave(boolean blockingSave) {
		if (endSession()) save(blockingSave);
	}

	private static boolean endSession() {
		if (currentKey == null) return false;
		Entry e = MAP.get(currentKey);
		if (e != null) e.totalMs += Math.max(0, System.currentTimeMillis() - sessionStart);
		currentKey = null;
		return true;
	}

	/** Most recently joined first. */
	public static synchronized List<Entry> list() {
		List<Entry> out = new ArrayList<>(MAP.values());
		out.sort((a, b) -> Long.compare(b.lastJoined, a.lastJoined));
		return out;
	}

	public static synchronized Entry get(String ip) {
		return MAP.get(ServerAdder.normalize(ip));
	}

	/** Total play time, including the session that is running right now. */
	public static synchronized long totalMs(Entry e) {
		long total = e.totalMs;
		if (currentKey != null && currentKey.equals(ServerAdder.normalize(e.ip))) {
			total += Math.max(0, System.currentTimeMillis() - sessionStart);
		}
		return total;
	}

	private static void trim() {
		int limit = ModConfig.get().historySize;
		while (MAP.size() > limit) {
			String oldest = null;
			long oldestTime = Long.MAX_VALUE;
			for (Map.Entry<String, Entry> en : MAP.entrySet()) {
				if (en.getKey().equals(currentKey)) continue;
				if (en.getValue().lastJoined < oldestTime) {
					oldestTime = en.getValue().lastJoined;
					oldest = en.getKey();
				}
			}
			if (oldest == null) break;
			MAP.remove(oldest);
		}
	}

	private static void save(boolean blocking) {
		JsonObject servers = new JsonObject();
		for (Entry e : MAP.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", e.name);
			o.addProperty("lastJoined", e.lastJoined);
			o.addProperty("totalMs", e.totalMs);
			o.addProperty("joins", e.joins);
			servers.add(ServerAdder.normalize(e.ip), o);
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", 1);
		root.add("servers", servers);
		String json = root.toString();
		if (blocking) Persist.writeNow(file(), json);
		else Persist.writeAsync(file(), () -> json);
	}
}

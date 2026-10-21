package com.serverbrowser.config;

import com.google.gson.JsonArray;
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

/**
 * The player's favorites and personal notes (config/serverbrowser/favorites.json).
 * A server can have a note without being a favorite and the other way round.
 */
public final class Favorites {
	public static final int MAX_NOTE = 200;

	public static final class Record {
		public final String ip;
		public String name;
		public boolean favorite;
		public String note = "";
		public long added;

		Record(String ip, String name) {
			this.ip = ip;
			this.name = name == null ? "" : name;
		}
	}

	private static final Map<String, Record> MAP = new LinkedHashMap<>(); // key: normalized address

	private Favorites() {}

	private static Path file() {
		return ModConfig.dir().resolve("favorites.json");
	}

	public static synchronized void load() {
		MAP.clear();
		try {
			Path f = file();
			if (!Files.exists(f)) return;
			JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			if (root.isJsonArray()) {
				// old format: just a list of addresses
				for (JsonElement e : root.getAsJsonArray()) {
					Record r = new Record(e.getAsString(), "");
					r.favorite = true;
					r.added = System.currentTimeMillis();
					MAP.put(ServerAdder.normalize(r.ip), r);
				}
				save();
				return;
			}
			JsonObject servers = root.getAsJsonObject().getAsJsonObject("servers");
			if (servers == null) return;
			for (Map.Entry<String, JsonElement> en : servers.entrySet()) {
				JsonObject o = en.getValue().getAsJsonObject();
				Record r = new Record(en.getKey(), str(o, "name"));
				r.favorite = o.has("favorite") && o.get("favorite").getAsBoolean();
				r.note = clampNote(str(o, "note"));
				r.added = o.has("added") ? o.get("added").getAsLong() : 0L;
				if (r.favorite || !r.note.isEmpty()) MAP.put(ServerAdder.normalize(r.ip), r);
			}
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not read favorites", e);
		}
	}

	public static synchronized boolean has(String ip) {
		Record r = MAP.get(ServerAdder.normalize(ip));
		return r != null && r.favorite;
	}

	public static synchronized String note(String ip) {
		Record r = MAP.get(ServerAdder.normalize(ip));
		return r == null ? "" : r.note;
	}

	/** Toggles the star and returns the new state. */
	public static synchronized boolean toggle(String ip, String name) {
		String key = ServerAdder.normalize(ip);
		Record r = MAP.computeIfAbsent(key, k -> new Record(ip, name));
		if (name != null && !name.isBlank()) r.name = name;
		r.favorite = !r.favorite;
		if (r.favorite && r.added == 0) r.added = System.currentTimeMillis();
		if (!r.favorite && r.note.isEmpty()) MAP.remove(key);
		save();
		return r.favorite;
	}

	public static synchronized void setNote(String ip, String name, String text) {
		String key = ServerAdder.normalize(ip);
		String note = clampNote(text);
		Record r = MAP.get(key);
		if (r == null) {
			if (note.isEmpty()) return;
			r = new Record(ip, name);
			MAP.put(key, r);
		}
		if (name != null && !name.isBlank()) r.name = name;
		r.note = note;
		if (!r.favorite && r.note.isEmpty()) MAP.remove(key);
		save();
	}

	/** All favorite servers, oldest first. */
	public static synchronized List<Record> favorites() {
		List<Record> out = new ArrayList<>();
		for (Record r : MAP.values()) if (r.favorite) out.add(r);
		out.sort((a, b) -> Long.compare(a.added, b.added));
		return out;
	}

	private static String clampNote(String s) {
		String t = s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").trim();
		return t.length() > MAX_NOTE ? t.substring(0, MAX_NOTE) : t;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return (e != null && e.isJsonPrimitive()) ? e.getAsString() : "";
	}

	private static void save() {
		JsonObject servers = new JsonObject();
		for (Record r : MAP.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", r.name);
			o.addProperty("favorite", r.favorite);
			o.addProperty("note", r.note);
			o.addProperty("added", r.added);
			servers.add(ServerAdder.normalize(r.ip), o);
		}
		JsonObject root = new JsonObject();
		root.addProperty("version", 2);
		root.add("servers", servers);
		String json = root.toString();
		Persist.writeAsync(file(), () -> json);
	}
}

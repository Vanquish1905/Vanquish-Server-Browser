package com.serverbrowser.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.serverbrowser.ServerBrowserClient;
import com.serverbrowser.config.ModConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Loads the server list. Order: fresh cache -> your hosted JSON -> stale cache -> built-in list.
 * Everything in the JSON is treated as untrusted input and validated.
 */
public final class ServerListFetcher {
	public enum Source { ONLINE, CACHE, OFFLINE_CACHE, BUNDLED, NONE }

	public record Result(List<ServerEntry> servers, Source source) {}

	private static final int MAX_BYTES = 2 * 1024 * 1024;
	private static final int MAX_SERVERS = 500;
	private static final Pattern IP_PATTERN = Pattern.compile("^(\\[[0-9a-fA-F:.]+\\]|[A-Za-z0-9._-]+)(:\\d{1,5})?$");

	private ServerListFetcher() {}

	public static Result load(boolean force) {
		ModConfig cfg = ModConfig.get();
		Path cache = ModConfig.dir().resolve("servers_cache.json");

		if (!force && isFresh(cache, cfg.cacheMinutes)) {
			try {
				return new Result(parse(Files.readString(cache)), Source.CACHE);
			} catch (Exception e) {
				ServerBrowserClient.LOGGER.warn("Cached list unreadable, refetching", e);
			}
		}

		if (cfg.isListUrlConfigured()) {
			try {
				String body = download(cfg.listUrl);
				List<ServerEntry> list = parse(body);
				Files.writeString(cache, body);
				return new Result(list, Source.ONLINE);
			} catch (Exception e) {
				ServerBrowserClient.LOGGER.warn("Could not fetch server list from {}: {}", cfg.listUrl, e.toString());
			}
			try {
				if (Files.exists(cache)) {
					return new Result(parse(Files.readString(cache)), Source.OFFLINE_CACHE);
				}
			} catch (Exception e) {
				ServerBrowserClient.LOGGER.warn("Offline cache unreadable", e);
			}
		}

		try (InputStream in = ServerListFetcher.class.getResourceAsStream("/assets/serverbrowser/default_servers.json")) {
			if (in != null) {
				return new Result(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)), Source.BUNDLED);
			}
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Built-in list unreadable", e);
		}
		return new Result(List.of(), Source.NONE);
	}

	private static boolean isFresh(Path file, int minutes) {
		try {
			if (!Files.exists(file)) return false;
			FileTime t = Files.getLastModifiedTime(file);
			return Duration.between(t.toInstant(), Instant.now()).toMinutes() < minutes;
		} catch (IOException e) {
			return false;
		}
	}

	private static String download(String url) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setConnectTimeout(8000);
		conn.setReadTimeout(10000);
		conn.setRequestProperty("User-Agent", "ServerBrowserMod/1.0");
		conn.setRequestProperty("Accept", "application/json");
		try {
			int code = conn.getResponseCode();
			if (code != 200) throw new IOException("HTTP " + code);
			try (InputStream in = conn.getInputStream()) {
				byte[] data = in.readNBytes(MAX_BYTES + 1);
				if (data.length > MAX_BYTES) throw new IOException("Server list is too large");
				return new String(data, StandardCharsets.UTF_8);
			}
		} finally {
			conn.disconnect();
		}
	}

	/** Accepts {"servers":[...]} or a bare [...] array. Invalid entries are skipped, not fatal. */
	static List<ServerEntry> parse(String json) {
		JsonElement root = JsonParser.parseString(json);
		JsonArray arr;
		if (root.isJsonArray()) {
			arr = root.getAsJsonArray();
		} else {
			arr = root.getAsJsonObject().getAsJsonArray("servers");
		}

		List<ServerEntry> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonElement el : arr) {
			if (out.size() >= MAX_SERVERS) break;
			if (!el.isJsonObject()) continue;
			JsonObject o = el.getAsJsonObject();

			String name = clean(str(o, "name"), 64);
			String ip = clean(str(o, "ip"), 255);
			if (name.isEmpty() || !validAddress(ip)) continue;
			if (!seen.add(ServerAdder.normalize(ip))) continue;

			String id = clean(str(o, "id"), 64);
			String category = clean(str(o, "category"), 32);
			String description = clean(str(o, "description"), 160);
			String website = clean(str(o, "website"), 200);
			out.add(new ServerEntry(id.isEmpty() ? name : id, name, ip,
					category.isEmpty() ? "Other" : category, description, website));
		}
		return out;
	}

	private static boolean validAddress(String ip) {
		if (ip.isEmpty() || !IP_PATTERN.matcher(ip).matches()) return false;
		int colon = ip.lastIndexOf(':');
		if (colon > 0 && !ip.endsWith("]") && ip.indexOf(':') == colon) {
			try {
				int port = Integer.parseInt(ip.substring(colon + 1));
				return port >= 1 && port <= 65535;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return true;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return (e != null && e.isJsonPrimitive()) ? e.getAsString() : "";
	}

	private static String clean(String s, int max) {
		String t = s.replaceAll("\\p{Cntrl}", "").trim();
		return t.length() > max ? t.substring(0, max) : t;
	}
}

package com.serverbrowser.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.serverbrowser.GameInfo;
import com.serverbrowser.config.ModConfig;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Hashtable;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pings servers with the standard Minecraft "server list ping" over a plain socket.
 * It does not depend on Minecraft's internal pinger classes, which change often between versions.
 */
public final class ServerPinger {
	private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
	private static ExecutorService pool;

	private ServerPinger() {}

	/** Handle for a group of pings so they can be cancelled when the screen closes or refreshes. */
	public static final class Batch {
		private volatile boolean cancelled;

		public void cancel() {
			cancelled = true;
		}
	}

	private record Address(String host, int port) {}

	private static synchronized ExecutorService pool() {
		if (pool == null) {
			AtomicInteger n = new AtomicInteger();
			pool = Executors.newFixedThreadPool(ModConfig.get().maxConcurrentPings, r -> {
				Thread t = new Thread(r, "ServerBrowser-Ping-" + n.incrementAndGet());
				t.setDaemon(true);
				return t;
			});
		}
		return pool;
	}

	/**
	 * Pings only the given servers (the current page). Servers that were checked recently are skipped,
	 * and queued pings are dropped if the batch is cancelled (page changed, screen closed).
	 */
	public static void request(List<ServerEntry> entries, Batch batch, Runnable onUpdate) {
		long maxAgeMs = ModConfig.get().pingCacheSeconds * 1000L;
		long now = System.currentTimeMillis();
		for (ServerEntry entry : entries) {
			if (entry.inFlight) continue;
			boolean checked = entry.state == ServerEntry.State.ONLINE || entry.state == ServerEntry.State.OFFLINE;
			if (checked && now - entry.lastPingMs < maxAgeMs) continue;
			entry.inFlight = true;
			if (entry.state == ServerEntry.State.IDLE) entry.state = ServerEntry.State.PINGING;
			pool().execute(() -> {
				if (batch.cancelled) {
					entry.inFlight = false;
					if (entry.state == ServerEntry.State.PINGING) entry.state = ServerEntry.State.IDLE;
					return;
				}
				try {
					ping(entry);
				} finally {
					entry.inFlight = false;
				}
				onUpdate.run();
			});
		}
	}

	static void ping(ServerEntry e) {
		int timeout = ModConfig.get().pingTimeoutMs;
		try {
			Address addr = resolve(e.ip);
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress(addr.host(), addr.port()), timeout);
				socket.setSoTimeout(timeout);
				socket.setTcpNoDelay(true);
				DataOutputStream out = new DataOutputStream(socket.getOutputStream());
				DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

				// Handshake (next state = status)
				ByteArrayOutputStream hb = new ByteArrayOutputStream();
				DataOutputStream h = new DataOutputStream(hb);
				writeVarInt(h, 0x00);
				writeVarInt(h, GameInfo.protocol());
				byte[] host = addr.host().getBytes(StandardCharsets.UTF_8);
				writeVarInt(h, host.length);
				h.write(host);
				h.writeShort(addr.port());
				writeVarInt(h, 1);
				writeVarInt(out, hb.size());
				out.write(hb.toByteArray());

				// Status request
				long statusStart = System.nanoTime();
				writeVarInt(out, 1);
				writeVarInt(out, 0x00);
				out.flush();

				readVarInt(in); // packet length
				if (readVarInt(in) != 0x00) throw new IOException("Unexpected status packet");
				int jsonLen = readVarInt(in);
				if (jsonLen <= 0 || jsonLen > MAX_JSON_BYTES) throw new IOException("Bad status length");
				byte[] json = new byte[jsonLen];
				in.readFully(json);
				int statusMs = (int) ((System.nanoTime() - statusStart) / 1_000_000L);

				// Ping/pong for a cleaner latency number; optional.
				int pingMs = statusMs;
				try {
					long t0 = System.nanoTime();
					writeVarInt(out, 9);
					writeVarInt(out, 0x01);
					out.writeLong(t0);
					out.flush();
					readVarInt(in);
					if (readVarInt(in) == 0x01) {
						in.readLong();
						pingMs = (int) ((System.nanoTime() - t0) / 1_000_000L);
					}
				} catch (IOException ignored) {
				}

				applyStatus(e, new String(json, StandardCharsets.UTF_8), pingMs);
			}
		} catch (Exception ex) {
			e.lastPingMs = System.currentTimeMillis();
			e.state = ServerEntry.State.OFFLINE;
			e.online = -1;
			e.max = -1;
			e.pingMs = -1;
		}
	}

	private static void applyStatus(ServerEntry e, String raw, int pingMs) {
		JsonObject root = JsonParser.parseString(raw).getAsJsonObject();

		JsonObject players = obj(root, "players");
		JsonObject version = obj(root, "version");

		StringBuilder motd = new StringBuilder();
		flatten(root.get("description"), motd);
		String motdText = stripFormatting(motd.toString()).lines().map(String::trim)
				.filter(s -> !s.isEmpty()).findFirst().orElse("");

		motdText = motdText.replaceAll("\\s+", " ").trim();

		// Server icon: "data:image/png;base64,...." (vanilla only accepts 64x64, checked when drawing)
		String favicon = str(root, "favicon");
		if (favicon.startsWith("data:image/png;base64,")) {
			try {
				byte[] png = Base64.getMimeDecoder().decode(favicon.substring(favicon.indexOf(',') + 1));
				if (png.length > 0 && png.length <= 128 * 1024) e.iconBytes = png;
			} catch (IllegalArgumentException ignored) {
			}
		}

		e.online = Math.max(0, num(players, "online"));
		e.max = Math.max(0, num(players, "max"));
		e.protocol = num(version, "protocol");
		e.versionName = clip(stripFormatting(str(version, "name")), 40);
		e.motd = clip(motdText, 200);
		e.pingMs = pingMs;
		e.lastPingMs = System.currentTimeMillis();
		e.state = ServerEntry.State.ONLINE;
	}

	// ---- address resolution (supports SRV records like the vanilla client) ----

	private static Address resolve(String ip) {
		String host = ip.trim();
		int port = -1;
		if (host.startsWith("[")) {
			int end = host.indexOf(']');
			String h = host.substring(1, end);
			if (host.length() > end + 1 && host.charAt(end + 1) == ':') port = parsePort(host.substring(end + 2));
			host = h;
		} else {
			int c = host.lastIndexOf(':');
			if (c > 0 && host.indexOf(':') == c) {
				port = parsePort(host.substring(c + 1));
				host = host.substring(0, c);
			}
		}

		if (port == -1) {
			try {
				Hashtable<String, String> env = new Hashtable<>();
				env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
				env.put("java.naming.provider.url", "dns:");
				env.put("com.sun.jndi.dns.timeout.initial", "2000");
				env.put("com.sun.jndi.dns.timeout.retries", "1");
				DirContext ctx = new InitialDirContext(env);
				try {
					Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
					Attribute srv = attrs.get("srv");
					if (srv != null && srv.size() > 0) {
						String[] parts = srv.get(0).toString().split(" ");
						port = Integer.parseInt(parts[2]);
						String target = parts[3];
						if (target.endsWith(".")) target = target.substring(0, target.length() - 1);
						host = target;
					}
				} finally {
					ctx.close();
				}
			} catch (Exception ignored) {
				// No SRV record: fall back to the default port.
			}
			if (port == -1) port = 25565;
		}
		return new Address(host, port);
	}

	private static int parsePort(String s) {
		try {
			int p = Integer.parseInt(s);
			return (p >= 1 && p <= 65535) ? p : 25565;
		} catch (NumberFormatException ex) {
			return 25565;
		}
	}

	// ---- protocol helpers ----

	private static void writeVarInt(OutputStream o, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			o.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		o.write(value);
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int value = 0;
		int pos = 0;
		while (true) {
			int b = in.read();
			if (b == -1) throw new EOFException();
			value |= (b & 0x7F) << pos;
			if ((b & 0x80) == 0) break;
			pos += 7;
			if (pos >= 35) throw new IOException("VarInt too big");
		}
		return value;
	}

	// ---- JSON helpers ----

	private static void flatten(JsonElement el, StringBuilder sb) {
		if (el == null || el.isJsonNull()) return;
		if (el.isJsonPrimitive()) {
			sb.append(el.getAsString());
		} else if (el.isJsonArray()) {
			for (JsonElement x : el.getAsJsonArray()) flatten(x, sb);
		} else if (el.isJsonObject()) {
			JsonObject o = el.getAsJsonObject();
			if (o.has("text")) flatten(o.get("text"), sb);
			if (o.has("extra")) flatten(o.get("extra"), sb);
		}
	}

	private static String stripFormatting(String s) {
		return s.replaceAll("\u00A7.", "");
	}

	private static String clip(String s, int max) {
		return s.length() > max ? s.substring(0, max) : s;
	}

	private static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : new JsonObject();
	}

	private static int num(JsonObject o, String key) {
		try {
			JsonElement e = o.get(key);
			return (e != null && e.isJsonPrimitive()) ? e.getAsInt() : -1;
		} catch (Exception ex) {
			return -1;
		}
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return (e != null && e.isJsonPrimitive()) ? e.getAsString() : "";
	}
}

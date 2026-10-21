package com.serverbrowser.data;

import java.util.List;
import java.util.Set;

/** One server from the list, plus its live status (filled in by {@link ServerPinger}). */
public final class ServerEntry {
	public enum State { IDLE, PINGING, ONLINE, OFFLINE }

	// From the server list file
	public final String id;
	public final String name;
	public final String ip;
	public final String category;
	public final String description;
	public final String website;
	/** Game mode tags such as "Roleplay" or "Sandbox". */
	public final List<String> tags;
	/** The list says this server is whitelisted or password protected (a ping cannot show this). */
	public final boolean locked;
	/** Mod ids the list says a player needs installed. */
	public final List<String> requiredMods;

	// Live status
	public volatile State state = State.IDLE;
	public volatile int online = -1;
	public volatile int max = -1;
	public volatile int pingMs = -1;
	public volatile int protocol = -1;
	public volatile boolean inFlight = false;
	public volatile long lastPingMs = 0;
	public volatile String motd = "";
	public volatile String versionName = "";
	/** Mod ids the server announced in its ping (Forge / NeoForge servers do this). */
	public volatile Set<String> pingMods = Set.of();
	/** How many of the required / announced mods are not installed on this client. */
	public volatile int missingMods = 0;

	/** PNG bytes of the server's icon from the ping, or null. Turned into a texture on the render thread. */
	public volatile byte[] iconBytes;
	/** Render-thread only: the texture handle and its id, and whether we already tried to build it. */
	public volatile Object iconTexture;
	public volatile Object iconId;
	public volatile boolean iconTried;

	public ServerEntry(String id, String name, String ip, String category, String description, String website,
					   List<String> tags, boolean locked, List<String> requiredMods) {
		this.id = id;
		this.name = name;
		this.ip = ip;
		this.category = category;
		this.description = description;
		this.website = website;
		this.tags = tags;
		this.locked = locked;
		this.requiredMods = requiredMods;
	}
}

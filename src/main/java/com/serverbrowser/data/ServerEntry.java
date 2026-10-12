package com.serverbrowser.data;

/** One server from the list, plus its live status (filled in by {@link ServerPinger}). */
public final class ServerEntry {
	public enum State { IDLE, PINGING, ONLINE, OFFLINE }

	public final String id;
	public final String name;
	public final String ip;
	public final String category;
	public final String description;
	public final String website;

	public volatile State state = State.IDLE;
	public volatile int online = -1;
	public volatile int max = -1;
	public volatile int pingMs = -1;
	public volatile int protocol = -1;
	public volatile boolean inFlight = false;
	public volatile long lastPingMs = 0;
	public volatile String motd = "";
	public volatile String versionName = "";

	/** PNG bytes of the server's icon from the ping, or null. Turned into a texture on the render thread. */
	public volatile byte[] iconBytes;
	/** Render-thread only: the texture handle and its id, and whether we already tried to build it. */
	public volatile Object iconTexture;
	public volatile Object iconId;
	public volatile boolean iconTried;

	public ServerEntry(String id, String name, String ip, String category, String description, String website) {
		this.id = id;
		this.name = name;
		this.ip = ip;
		this.category = category;
		this.description = description;
		this.website = website;
	}
}

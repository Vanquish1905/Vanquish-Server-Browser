package com.serverbrowser.gui;

import com.serverbrowser.GameInfo;
import com.serverbrowser.config.ModConfig;
import com.serverbrowser.data.ServerEntry;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** What the player has filtered the list by. Shared between the browser and the filters screen. */
final class Filters {
	enum Mode {
		SHOW, HIDE, ONLY;

		Mode next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	boolean hideOffline = ModConfig.get().hideOfflineByDefault;
	Mode full = Mode.SHOW;
	Mode empty = Mode.SHOW;
	Mode locked = Mode.SHOW;
	Mode mods = Mode.SHOW;
	boolean compatOnly;
	final Set<String> tags = new LinkedHashSet<>();

	void reset() {
		hideOffline = ModConfig.get().hideOfflineByDefault;
		full = empty = locked = mods = Mode.SHOW;
		compatOnly = false;
		tags.clear();
	}

	/** How many filters differ from "show everything". */
	int activeCount() {
		int n = 0;
		if (hideOffline) n++;
		if (full != Mode.SHOW) n++;
		if (empty != Mode.SHOW) n++;
		if (locked != Mode.SHOW) n++;
		if (mods != Mode.SHOW) n++;
		if (compatOnly) n++;
		if (!tags.isEmpty()) n++;
		return n;
	}

	/** These filters depend on a server's live status, so every server has to be checked, not just the visible page. */
	boolean needsLiveData() {
		return full != Mode.SHOW || empty != Mode.SHOW || locked != Mode.SHOW || mods != Mode.SHOW || compatOnly;
	}

	void toggleTag(String tag) {
		boolean removed = tags.removeIf(t -> t.equalsIgnoreCase(tag));
		if (!removed) tags.add(tag);
	}

	boolean hasTag(String tag) {
		return tags.stream().anyMatch(t -> t.equalsIgnoreCase(tag));
	}

	/** Game mode tags: a server matches if it has at least one of the selected tags. */
	boolean matchesTags(ServerEntry e) {
		if (tags.isEmpty()) return true;
		for (String selected : tags) {
			for (String tag : e.tags) if (tag.equalsIgnoreCase(selected)) return true;
		}
		return false;
	}

	/** 1 = same game version as this client, 0 = different, -1 = unknown. */
	static int compat(ServerEntry e) {
		int mine = GameInfo.protocol();
		if (mine <= 0 || e.state != ServerEntry.State.ONLINE || e.protocol <= 0) return -1;
		return e.protocol == mine ? 1 : 0;
	}

	/** The list marks it as locked, or its message of the day says it is whitelisted. A ping cannot show a password. */
	static boolean isLocked(ServerEntry e) {
		if (e.locked) return true;
		return e.state == ServerEntry.State.ONLINE && e.motd.toLowerCase(Locale.ROOT).contains("whitelist");
	}

	static boolean isFull(ServerEntry e) {
		return e.state == ServerEntry.State.ONLINE && e.max > 0 && e.online >= e.max;
	}

	static boolean isEmpty(ServerEntry e) {
		return e.state == ServerEntry.State.ONLINE && e.online == 0;
	}

	/**
	 * Filters that use live data. A server that has not been checked yet is never hidden by "Hide"
	 * (it may turn out fine) but is not shown by "Only" (it is not known to match).
	 */
	boolean matchesLive(ServerEntry e) {
		if (hideOffline && e.state == ServerEntry.State.OFFLINE) return false;
		if (!pass(full, isFull(e))) return false;
		if (!pass(empty, isEmpty(e))) return false;
		if (!pass(locked, isLocked(e))) return false;
		if (!pass(mods, e.missingMods > 0)) return false;
		return !(compatOnly && compat(e) == 0);
	}

	private static boolean pass(Mode mode, boolean matches) {
		return switch (mode) {
			case SHOW -> true;
			case HIDE -> !matches;
			case ONLY -> matches;
		};
	}
}

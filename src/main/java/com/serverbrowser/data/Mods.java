package com.serverbrowser.data;

import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Checks which mods a server needs that this client does not have installed. */
public final class Mods {
	private static final Set<String> IGNORED = Set.of("minecraft", "java", "forge", "neoforge", "fml", "fabricloader", "fabric-api");

	private Mods() {}

	/** Recounts the missing mods for a server (from the list's requiredMods and from what its ping announced). */
	public static void refresh(ServerEntry e) {
		Set<String> need = new LinkedHashSet<>(e.requiredMods);
		need.addAll(e.pingMods);
		int missing = 0;
		for (String id : need) {
			String key = id.toLowerCase(Locale.ROOT);
			if (IGNORED.contains(key)) continue;
			if (!FabricLoader.getInstance().isModLoaded(key)) missing++;
		}
		e.missingMods = missing;
	}
}

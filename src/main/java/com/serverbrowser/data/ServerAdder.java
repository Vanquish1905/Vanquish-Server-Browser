package com.serverbrowser.data;

import com.serverbrowser.ServerBrowserClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Adds servers to the player's normal server list (servers.dat), the same one vanilla uses. */
public final class ServerAdder {
	public enum Result { ADDED, ALREADY, FAILED }

	private ServerAdder() {}

	public static String normalize(String ip) {
		String s = ip.trim().toLowerCase(Locale.ROOT);
		if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
		if (s.endsWith(":25565")) s = s.substring(0, s.length() - 6);
		return s;
	}

	public static Set<String> existing(Minecraft mc) {
		Set<String> out = new HashSet<>();
		try {
			ServerList list = new ServerList(mc);
			list.load();
			for (int i = 0; i < list.size(); i++) out.add(normalize(list.get(i).ip));
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not read server list", e);
		}
		return out;
	}

	public static Result add(Minecraft mc, ServerEntry entry) {
		try {
			ServerList list = new ServerList(mc);
			list.load();
			String key = normalize(entry.ip);
			for (int i = 0; i < list.size(); i++) {
				if (normalize(list.get(i).ip).equals(key)) return Result.ALREADY;
			}
			list.add(new ServerData(entry.name, entry.ip, ServerData.Type.OTHER), false);
			list.save();
			return Result.ADDED;
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not add server {}", entry.ip, e);
			return Result.FAILED;
		}
	}
}

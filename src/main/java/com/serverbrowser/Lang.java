package com.serverbrowser;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.serverbrowser.config.ModConfig;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The mod's own language system.
 * <p>
 * "auto" uses Minecraft's language (the normal translation system). Any other setting uses the language pack with
 * that code, whatever language the game is set to. Built-in packs live in assets/serverbrowser/lang/. Extra packs
 * can be dropped into config/serverbrowser/lang/ as {@code <code>.json} (same format as en_us.json).
 */
public final class Lang {
	public static final String AUTO = "auto";

	private static final String[] BUILTIN = {
			"en_us", "de_de", "fr_fr", "es_es", "es_mx", "pt_br", "it_it", "nl_nl",
			"pl_pl", "ru_ru", "tr_tr", "ja_jp", "ko_kr", "zh_cn", "zh_tw"
	};
	private static final String FALLBACK = "en_us";
	private static final String NAME_KEY = "serverbrowser.language.name";
	private static final Map<String, Map<String, String>> PACKS = new LinkedHashMap<>();

	private Lang() {}

	public static synchronized void load() {
		PACKS.clear();
		for (String code : BUILTIN) {
			try (InputStream in = Lang.class.getResourceAsStream("/assets/serverbrowser/lang/" + code + ".json")) {
				if (in != null) PACKS.put(code, read(new InputStreamReader(in, StandardCharsets.UTF_8)));
			} catch (Exception e) {
				ServerBrowserClient.LOGGER.warn("Could not read built-in language {}", code, e);
			}
		}

		// Language packs added by the player: config/serverbrowser/lang/<code>.json
		Path dir = ModConfig.dir().resolve("lang");
		try {
			Files.createDirectories(dir);
			List<Path> files;
			try (Stream<Path> stream = Files.list(dir)) {
				files = stream.sorted().toList();
			}
			for (Path p : files) {
				String file = p.getFileName().toString().toLowerCase(Locale.ROOT);
				if (!file.endsWith(".json")) continue;
				String code = file.substring(0, file.length() - 5);
				if (!code.matches("[a-z0-9_]{2,16}")) continue;
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					Map<String, String> merged = new HashMap<>(PACKS.getOrDefault(code, Map.of()));
					merged.putAll(read(r)); // a custom pack can override a built-in one; missing keys stay as they were
					PACKS.put(code, merged);
				} catch (Exception e) {
					ServerBrowserClient.LOGGER.warn("Could not read language pack {}: {}", file, e.toString());
				}
			}
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not scan language pack folder: {}", e.toString());
		}
	}

	private static Map<String, String> read(Reader reader) {
		JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
		Map<String, String> map = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
			if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
		}
		return map;
	}

	/** "auto" followed by every available language code. */
	public static List<String> codes() {
		List<String> out = new ArrayList<>();
		out.add(AUTO);
		out.addAll(PACKS.keySet());
		return out;
	}

	/** The next language in the list after {@code current}, wrapping around. */
	public static String next(String current) {
		List<String> list = codes();
		int idx = list.indexOf(current);
		return list.get((idx + 1) % list.size());
	}

	/** A language's own name (for example "Deutsch"), or its code if the pack doesn't give one. */
	public static String displayName(String code) {
		Map<String, String> pack = PACKS.get(code);
		String name = pack == null ? null : pack.get(NAME_KEY);
		return name != null && !name.isBlank() ? name : code;
	}

	/** Translated text for a key in the language chosen in the mod's settings. */
	public static Component tr(String key, Object... args) {
		String code = ModConfig.get().language;
		Map<String, String> pack = AUTO.equals(code) ? null : PACKS.get(code);
		if (pack == null) return Component.translatable(key, args);

		String text = pack.get(key);
		if (text == null) text = PACKS.getOrDefault(FALLBACK, Map.of()).get(key);
		if (text == null) return Component.literal(key);
		return Component.literal(format(text, args));
	}

	private static String format(String text, Object[] args) {
		StringBuilder sb = new StringBuilder();
		int arg = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '%' && i + 1 < text.length()) {
				char n = text.charAt(i + 1);
				if (n == 's') {
					sb.append(arg < args.length ? asString(args[arg++]) : "");
					i++;
					continue;
				}
				if (n == '%') {
					sb.append('%');
					i++;
					continue;
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private static String asString(Object o) {
		return o instanceof Component c ? c.getString() : String.valueOf(o);
	}
}

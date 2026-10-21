package com.serverbrowser.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.serverbrowser.ServerBrowserClient;
import com.serverbrowser.data.ServerEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;

/**
 * Turns the icon PNG from a server ping into a texture, using the same helper class vanilla uses for the
 * normal server list. That class is looked up by name so a rename in a future version only means "no icons",
 * never a crash. Must be called on the render thread.
 */
final class IconCache {
	private static boolean resolved;
	private static Method forServer, upload, textureLocation, close;

	private static final int PER_FRAME = 3;
	private static int budget = PER_FRAME;

	private IconCache() {}

	/** Call once per frame. Limits how many new icons are turned into textures in that frame. */
	static void beginFrame() {
		budget = PER_FRAME;
	}

	private static Method find(Class<?> type, String name, int params) throws NoSuchMethodException {
		for (Method m : type.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == params) return m;
		}
		throw new NoSuchMethodException(type.getName() + "." + name);
	}

	private static synchronized boolean resolve() {
		if (!resolved) {
			resolved = true;
			try {
				Class<?> c = Class.forName("net.minecraft.client.gui.screens.FaviconTexture");
				forServer = find(c, "forServer", 2);
				upload = find(c, "upload", 1);
				textureLocation = find(c, "textureLocation", 0);
				close = find(c, "close", 0);
			} catch (Throwable t) {
				forServer = null;
				ServerBrowserClient.LOGGER.warn("Server icons unavailable in this Minecraft version: {}", t.toString());
			}
		}
		return forServer != null;
	}

	/** The icon's texture id, or null if the server has no (valid) icon. */
	static Identifier get(Minecraft mc, ServerEntry e) {
		Object id = e.iconId;
		if (id != null) return (Identifier) id;

		byte[] png = e.iconBytes;
		if (png == null || e.iconTried) return null;
		if (budget <= 0) return null; // try again next frame
		budget--;
		e.iconTried = true;
		if (!resolve()) return null;

		NativeImage image = null;
		try {
			image = NativeImage.read(new ByteArrayInputStream(png));
			if (image.getWidth() != 64 || image.getHeight() != 64) {
				image.close(); // vanilla only accepts 64x64 icons
				return null;
			}
			// Own key so we never touch the texture the normal multiplayer screen uses for the same address.
			Object texture = forServer.invoke(null, mc.getTextureManager(), "serverbrowser:" + e.ip);
			upload.invoke(texture, image); // the texture takes over the image from here
			e.iconTexture = texture;
			e.iconId = textureLocation.invoke(texture);
			return (Identifier) e.iconId;
		} catch (Throwable t) {
			ServerBrowserClient.LOGGER.debug("Could not load icon for {}", e.ip, t);
			return null;
		}
	}

	/** Frees the texture. Safe to call any time; the icon is rebuilt from the saved bytes if needed again. */
	static void release(ServerEntry e) {
		Object texture = e.iconTexture;
		e.iconTexture = null;
		e.iconId = null;
		e.iconTried = false;
		if (texture != null && close != null) {
			try {
				close.invoke(texture);
			} catch (Throwable ignored) {
			}
		}
	}
}

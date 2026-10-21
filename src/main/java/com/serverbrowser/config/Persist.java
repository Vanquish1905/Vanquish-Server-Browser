package com.serverbrowser.config;

import com.serverbrowser.ServerBrowserClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One background thread for all disk work, so saving never makes the game stutter. */
public final class Persist {
	private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "ServerBrowser-IO");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	private Persist() {}

	public static void run(Runnable task) {
		IO.execute(task);
	}

	public static ScheduledFuture<?> later(Runnable task, long delayMs) {
		return IO.schedule(task, delayMs, TimeUnit.MILLISECONDS);
	}

	/** Writes a file on the IO thread. The content is created on that thread too. */
	public static void writeAsync(Path file, Supplier<String> content) {
		IO.execute(() -> writeNow(file, content.get()));
	}

	/** Writes through a temp file so a crash never leaves a half-written file behind. */
	public static void writeNow(Path file, String text) {
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, text, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			ServerBrowserClient.LOGGER.warn("Could not write {}: {}", file.getFileName(), e.toString());
		}
	}
}

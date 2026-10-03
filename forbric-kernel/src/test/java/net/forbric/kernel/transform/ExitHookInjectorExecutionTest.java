/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link ExitHookInjector}'s output, run: every return of the client's {@code Minecraft.close()} and of the dedicated
 * server's {@code onServerExit()} calls the shutdown hook with that class's own loader, after the method's own work.
 *
 * <p>The hook is a recording stand-in under {@code ClientShutdown}'s name, defined child-first, because the real one
 * stops executors, starts an exit guard and runs once per JVM. What is under test here is the injector's half: that
 * the edited classes link against the hook and reach it on each way out.
 */
@ExecutesInjector(ExitHookInjector.class)
class ExitHookInjectorExecutionTest {
	private static final String CLIENT = "net.minecraft.client.Minecraft";
	private static final String SERVER = "net.minecraft.server.dedicated.DedicatedServer";

	private static final Map<String, String> STAND_INS = Map.of(
			ExitHookInjector.HOOK_OWNER.replace('/', '.'), """
					package net.forbric.kernel.interop;

					import java.util.ArrayList;
					import java.util.List;

					public final class ClientShutdown {
						public static final List<Object> calls = new ArrayList<>();

						public static void stopLeakedBackgroundExecutors(ClassLoader loader) {
							calls.add(loader);
						}
					}
					""",
			CLIENT, """
					package net.minecraft.client;

					import java.util.ArrayList;
					import java.util.List;

					public class Minecraft {
						public static final List<String> done = new ArrayList<>();
						private boolean closed;

						/** Two ways out: an early return when already closed, and the normal one. */
						public void close() {
							if (closed) {
								done.add("already closed");
								return;
							}
							closed = true;
							done.add("closed");
						}
					}
					""",
			SERVER, """
					package net.minecraft.server.dedicated;

					import java.util.ArrayList;
					import java.util.List;

					public class DedicatedServer {
						public static final List<String> done = new ArrayList<>();

						public void onServerExit() {
							done.add("Stopping server");
						}
					}
					""");

	@Test void eachWayOutOfEachSidesEndOfLifeCallsTheHookWithItsOwnLoader(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(CLIENT, SERVER)) {
			String internal = target.replace('.', '/');
			classes.put(internal, InjectorExecution.transform(new ExitHookInjector(), target, original.get(internal), EnvType.CLIENT));
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(CLIENT, SERVER)) {
			assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		}
		List<?> calls = (List<?>) InjectorExecution.getStatic(loader.loadClass(ExitHookInjector.HOOK_OWNER.replace('/', '.')), "calls");

		Object minecraft = InjectorExecution.construct(loader.loadClass(CLIENT));
		InjectorExecution.invoke(minecraft, "close");
		assertEquals(List.of(loader), calls, "the normal return calls the hook with the game class's own loader");
		InjectorExecution.invoke(minecraft, "close");
		assertEquals(List.of(loader, loader), calls, "and so does the early one");
		assertEquals(List.of("closed", "already closed"), InjectorExecution.getStatic(loader.loadClass(CLIENT), "done"),
				"close() still does its own work first");

		InjectorExecution.invoke(InjectorExecution.construct(loader.loadClass(SERVER)), "onServerExit");
		assertEquals(3, calls.size(), "a dedicated server's exit calls it too, or the JVM sits after Stopping server");

		ClassLoader merged = InjectorExecution.load(original);
		InjectorExecution.invoke(InjectorExecution.construct(merged.loadClass(CLIENT)), "close");
		assertEquals(List.of(), InjectorExecution.getStatic(merged.loadClass(ExitHookInjector.HOOK_OWNER.replace('/', '.')), "calls"),
				"premise: as merged, nothing stops the leaked executors");
	}

	@Test void otherClassesAreLeftAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] hook = original.get(ExitHookInjector.HOOK_OWNER);
		assertSame(hook, InjectorExecution.transform(new ExitHookInjector(), ExitHookInjector.HOOK_OWNER.replace('/', '.'), hook,
				EnvType.CLIENT));
	}
}

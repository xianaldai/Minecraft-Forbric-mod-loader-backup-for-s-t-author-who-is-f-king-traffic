/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link NeoScreenEventsInjector}'s output, run: when the merged {@code Gui.setScreen} changes screens, NeoForge mods
 * are told (Opening, then Closing for the old screen) after MinecraftForge's, and a NeoForge listener can refuse a
 * screen or replace it, as JEI and Controlling do; where as merged only MinecraftForge was asked.
 *
 * <p>The hook is the kernel's real {@code KernelScreenEvents}, compiled from {@code src/runtime/java} against stand-ins
 * for {@code Screen}, NeoForge's {@code ScreenEvent} and bus; the stand-in {@code setScreen} has the shape of the merged
 * body around MinecraftForge's {@code onScreenOpening} and {@code onScreenClose}.
 */
@ExecutesInjector(NeoScreenEventsInjector.class)
class NeoScreenEventsInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelScreenEvents.java");
	private static final String GUI = NeoScreenEventsInjector.GUI;
	private static final String SCREEN = "net.minecraft.client.gui.screens.Screen";
	private static final String EVENT = "net.neoforged.neoforge.client.event.ScreenEvent";

	private static final Map<String, String> STAND_INS = Map.of(
			"fixture.Trace", "package fixture; public final class Trace { public static final java.util.List<String> events = new java.util.ArrayList<>(); }",
			SCREEN, """
					package net.minecraft.client.gui.screens;

					public class Screen {
						private final String title;

						public Screen(String title) {
							this.title = title;
						}

						public void removed() {
							fixture.Trace.events.add("removed " + title);
						}

						@Override
						public String toString() {
							return title;
						}
					}
					""",
			"net.minecraftforge.client.event.ForgeEventFactoryClient", """
					package net.minecraftforge.client.event;

					import fixture.Trace;
					import net.minecraft.client.gui.screens.Screen;

					public class ForgeEventFactoryClient {
						public static Screen onScreenOpening(Screen old, Screen next) {
							Trace.events.add("MinecraftForge opening " + next);
							return next;
						}

						public static void onScreenClose(Screen old) {
							Trace.events.add("MinecraftForge closing " + old);
						}
					}
					""",
			EVENT, """
					package net.neoforged.neoforge.client.event;

					import net.minecraft.client.gui.screens.Screen;

					public abstract class ScreenEvent {
						private final Screen screen;
						private boolean canceled;

						protected ScreenEvent(Screen screen) {
							this.screen = screen;
						}

						public Screen getScreen() {
							return screen;
						}

						public boolean isCanceled() {
							return canceled;
						}

						public void setCanceled(boolean canceled) {
							this.canceled = canceled;
						}

						public static class Opening extends ScreenEvent {
							private final Screen current;
							private Screen newScreen;

							public Opening(Screen current, Screen screen) {
								super(screen);
								this.current = current;
								this.newScreen = screen;
							}

							public Screen getCurrentScreen() {
								return current;
							}

							public Screen getNewScreen() {
								return newScreen;
							}

							public void setNewScreen(Screen screen) {
								this.newScreen = screen;
							}
						}

						public static class Closing extends ScreenEvent {
							public Closing(Screen screen) {
								super(screen);
							}
						}
					}
					""",
			"net.neoforged.neoforge.common.NeoForge", """
					package net.neoforged.neoforge.common;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.Consumer;

					public class NeoForge {
						public static class Bus {
							public final List<Consumer<Object>> listeners = new ArrayList<>();

							public <T> T post(T event) {
								for (Consumer<Object> listener : listeners) listener.accept(event);
								return event;
							}
						}

						public static final Bus EVENT_BUS = new Bus();
					}
					""",
			GUI, """
					package net.minecraft.client.gui;

					import net.minecraft.client.gui.screens.Screen;
					import net.minecraftforge.client.event.ForgeEventFactoryClient;

					public class Gui {
						public Screen screen;

						public void setScreen(Screen screen) {
							Screen old = this.screen;
							Screen opening = ForgeEventFactoryClient.onScreenOpening(old, screen);
							if (opening == null) return;
							screen = opening;
							if (old != null) {
								ForgeEventFactoryClient.onScreenClose(old);
								old.removed();
							}
							this.screen = screen;
						}
					}
					""");

	private static Object screen(ClassLoader loader, String title) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(SCREEN), title);
	}

	@SuppressWarnings("unchecked")
	private static void listen(ClassLoader loader, Consumer<Object> listener) throws ReflectiveOperationException {
		Object bus = InjectorExecution.getStatic(loader.loadClass("net.neoforged.neoforge.common.NeoForge"), "EVENT_BUS");
		((List<Consumer<Object>>) bus.getClass().getField("listeners").get(bus)).add(listener);
	}

	private static List<?> trace(ClassLoader loader) throws ReflectiveOperationException {
		return (List<?>) InjectorExecution.getStatic(loader.loadClass("fixture.Trace"), "events");
	}

	private static Object call(Object target, String method, Object... args) {
		try {
			return InjectorExecution.invoke(target, method, args);
		} catch (Throwable failed) {
			throw new AssertionError(failed);
		}
	}

	@Test void neoForgeModsAreToldAndCanRefuseOrReplaceAScreen(@TempDir Path work) throws Throwable {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelScreenEvents.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		String internal = GUI.replace('.', '/');
		byte[] posted = InjectorExecution.transform(new NeoScreenEventsInjector(), GUI, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, posted);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(posted, loader));

		Object controls = screen(loader, "controls");
		List<String> neoForge = new ArrayList<>();
		listen(loader, event -> {
			String kind = event.getClass().getSimpleName();
			Object subject = call(event, "getScreen");
			neoForge.add(kind + " " + subject);
			if (kind.equals("Opening") && subject.toString().equals("blocked")) call(event, "setCanceled", true);
			if (kind.equals("Opening") && subject.toString().equals("vanilla controls")) call(event, "setNewScreen", controls);
		});
		Object gui = InjectorExecution.construct(loader.loadClass(GUI));
		Object title = screen(loader, "title");
		call(gui, "setScreen", title);
		call(gui, "setScreen", screen(loader, "options"));
		assertEquals(List.of("Opening title", "Opening options", "Closing title"), neoForge,
				"NeoForge mods hear of every change, the old screen's Closing included");
		assertEquals(List.of("MinecraftForge opening title", "MinecraftForge opening options", "MinecraftForge closing title",
				"removed title"), trace(loader), "MinecraftForge's own hooks still run first, and the old screen is still removed");

		Object options = gui.getClass().getField("screen").get(gui);
		call(gui, "setScreen", screen(loader, "blocked"));
		assertSame(options, gui.getClass().getField("screen").get(gui), "a NeoForge listener refused the screen");
		call(gui, "setScreen", screen(loader, "vanilla controls"));
		assertSame(controls, gui.getClass().getField("screen").get(gui), "a NeoForge listener replaced the screen (Controlling)");

		ClassLoader merged = InjectorExecution.load(original);
		List<String> unheard = new ArrayList<>();
		listen(merged, event -> unheard.add("told"));
		call(InjectorExecution.construct(merged.loadClass(GUI)), "setScreen", screen(merged, "title"));
		assertEquals(List.of(), unheard, "premise: as merged, NeoForge mods are never told");
		assertSame(posted, InjectorExecution.transform(new NeoScreenEventsInjector(), GUI, posted, EnvType.CLIENT),
				"a body that already posts NeoForge's events is left alone");
	}
}

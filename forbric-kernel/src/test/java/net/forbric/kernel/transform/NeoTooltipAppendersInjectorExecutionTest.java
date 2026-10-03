/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.KernelLifecycle;

/**
 * {@link NeoTooltipAppendersInjector}'s output, run: NeoForge's tooltip registration reaches every mod even when one
 * mod's registration throws, and Fabric's component tooltip providers draw before and after a vanilla component's
 * lines — where as merged the first failure cost every later mod its tooltip lines, and Fabric's providers drew nothing.
 *
 * <p>The stand-in {@code ItemTooltipHandler} keeps the two shapes the edits key on: {@code init} posting the event it
 * just built to {@code ModLoader.postEvent}, which stops at the first mod that throws, and
 * {@code addDataComponentAppenders} adding each vanilla appender, cast from its map, to {@code MIDDLE_APPENDERS}. The
 * kernel's {@code KernelNeoTooltips} delivers through KernelLifecycle and reaches fabric-item-api by method handles; a
 * stand-in under its name delivers to each mod on its own and draws Fabric's lines around the appender it is given.
 */
@ExecutesInjector(NeoTooltipAppendersInjector.class)
@ResourceLock("system-properties")
class NeoTooltipAppendersInjectorExecutionTest {
	private static final String HANDLER = NeoTooltipAppendersInjector.HANDLER;

	private static final Map<String, String> STAND_INS = Map.of(
			"net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public class Event { }",
			"net.minecraft.core.component.DataComponentType", "package net.minecraft.core.component; public record DataComponentType(String id) { }",
			"net.neoforged.neoforge.common.tooltip.TooltipAppender", """
					package net.neoforged.neoforge.common.tooltip;

					import java.util.List;

					public interface TooltipAppender {
						void append(String stack, List<String> out);
					}
					""",
			"net.neoforged.neoforge.event.RegisterTooltipAppendersEvent", """
					package net.neoforged.neoforge.event;

					import java.util.List;
					import net.neoforged.bus.api.Event;
					import net.neoforged.neoforge.common.tooltip.TooltipAppender;

					public class RegisterTooltipAppendersEvent extends Event {
						private final List<TooltipAppender> appenders;

						public RegisterTooltipAppendersEvent(List<TooltipAppender> appenders) {
							this.appenders = appenders;
						}

						public void registerAppender(TooltipAppender appender) {
							if (appenders.contains(appender)) throw new IllegalStateException("appender registered twice");
							appenders.add(appender);
						}
					}
					""",
			"net.neoforged.fml.ModLoader", """
					package net.neoforged.fml;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.Consumer;
					import net.neoforged.bus.api.Event;

					public class ModLoader {
						/** One listener per mod container, in load order. */
						public static final List<Consumer<Event>> modBus = new ArrayList<>();

						/** NeoForge's: the first mod that throws ends the post. */
						public static void postEvent(Event event) {
							for (Consumer<Event> mod : modBus) mod.accept(event);
						}
					}
					""",
			"net.forbric.kernel.runtime.KernelNeoTooltips", """
					package net.forbric.kernel.runtime;

					import java.util.function.Consumer;
					import net.minecraft.core.component.DataComponentType;
					import net.neoforged.bus.api.Event;
					import net.neoforged.fml.ModLoader;
					import net.neoforged.neoforge.common.tooltip.TooltipAppender;
					import net.neoforged.neoforge.event.RegisterTooltipAppendersEvent;

					public final class KernelNeoTooltips {
						/** To each mod on its own: one mod's failure is that mod's. */
						public static void postRegisterAppenders(RegisterTooltipAppendersEvent event) {
							for (Consumer<Event> mod : ModLoader.modBus) {
								try {
									mod.accept(event);
								} catch (RuntimeException failed) {
									// reported per mod in the real one
								}
							}
						}

						/** Fabric's before and after providers around a vanilla component's lines. */
						public static TooltipAppender around(TooltipAppender inner, DataComponentType type) {
							return (stack, out) -> {
								out.add("fabric before " + type.id());
								inner.append(stack, out);
								out.add("fabric after " + type.id());
							};
						}
					}
					""",
			HANDLER, """
					package net.neoforged.neoforge.common.tooltip;

					import java.util.ArrayList;
					import java.util.LinkedHashMap;
					import java.util.List;
					import java.util.SequencedMap;
					import net.minecraft.core.component.DataComponentType;
					import net.neoforged.fml.ModLoader;
					import net.neoforged.neoforge.event.RegisterTooltipAppendersEvent;

					public class ItemTooltipHandler {
						public static final List<TooltipAppender> MIDDLE_APPENDERS = new ArrayList<>();
						public static final List<TooltipAppender> MOD_APPENDERS = new ArrayList<>();

						/** NeoForge's: vanilla's component appenders, then the mods' registration. */
						public static void init() {
							SequencedMap<DataComponentType, TooltipAppender> vanilla = new LinkedHashMap<>();
							vanilla.put(new DataComponentType("minecraft:enchantments"), (stack, out) -> out.add("Sharpness V"));
							addDataComponentAppenders(vanilla);
							ModLoader.postEvent(new RegisterTooltipAppendersEvent(MOD_APPENDERS));
						}

						private static void addDataComponentAppenders(SequencedMap<DataComponentType, TooltipAppender> appenders) {
							for (DataComponentType type : appenders.sequencedKeySet()) {
								MIDDLE_APPENDERS.add(appenders.get(type));
							}
						}

						public static List<String> tooltip(String stack) {
							List<String> out = new ArrayList<>(List.of(stack));
							MIDDLE_APPENDERS.forEach(appender -> appender.append(stack, out));
							MOD_APPENDERS.forEach(appender -> appender.append(stack, out));
							return out;
						}
					}
					""",
			"fixture.Mods", """
					package fixture;

					import net.neoforged.fml.ModLoader;
					import net.neoforged.neoforge.common.tooltip.TooltipAppender;
					import net.neoforged.neoforge.event.RegisterTooltipAppendersEvent;

					/** Two NeoForge mods: the first registers its appender twice and throws; the second is fine. */
					public class Mods {
						public static void register() {
							TooltipAppender clumsy = (stack, out) -> out.add("clumsy line");
							ModLoader.modBus.add(event -> {
								if (event instanceof RegisterTooltipAppendersEvent tooltips) {
									tooltips.registerAppender(clumsy);
									tooltips.registerAppender(clumsy);
								}
							});
							ModLoader.modBus.add(event -> {
								if (event instanceof RegisterTooltipAppendersEvent tooltips) tooltips.registerAppender((stack, out) -> out.add("Durability 12/250"));
							});
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(KernelLifecycle.NEO_TOOLTIP_APPENDERS);
		System.clearProperty(GuestInjectorPruner.FABRIC_TOOLTIP_BRIDGE);
	}

	/** The tooltip of a sword once the handler is built, or the failure that built nothing. */
	private static Object tooltip(ClassLoader loader) throws Throwable {
		InjectorExecution.invokeStatic(loader.loadClass("fixture.Mods"), "register");
		Class<?> handler = loader.loadClass(HANDLER);
		try {
			InjectorExecution.invokeStatic(handler, "init");
		} catch (IllegalStateException failed) {
			return failed.getMessage() + " | " + InjectorExecution.invokeStatic(handler, "tooltip", "Diamond Sword");
		}
		return InjectorExecution.invokeStatic(handler, "tooltip", "Diamond Sword");
	}

	private static ClassLoader game(Map<String, byte[]> original) {
		String internal = HANDLER.replace('.', '/');
		byte[] out = InjectorExecution.transform(new NeoTooltipAppendersInjector(), HANDLER, original.get(internal), EnvType.CLIENT);
		assertNotSame(original.get(internal), out, "init's post is the reviewed shape");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, out);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(out, loader));
		return loader;
	}

	@Test void everyModsTooltipLinesAndFabricsAroundVanillasAreDrawn(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		assertEquals(List.of("Diamond Sword", "fabric before minecraft:enchantments", "Sharpness V", "fabric after minecraft:enchantments",
				"clumsy line", "Durability 12/250"), tooltip(game(original)),
				"the mod after the failing one still adds its line, and Fabric draws around the vanilla component");

		assertEquals("appender registered twice | [Diamond Sword, Sharpness V, clumsy line]", tooltip(InjectorExecution.load(original)),
				"premise: as merged, the first failure stops the post and Fabric draws nothing");
		String internal = HANDLER.replace('.', '/');
		byte[] once = InjectorExecution.transform(new NeoTooltipAppendersInjector(), HANDLER, original.get(internal), EnvType.CLIENT);
		assertSame(once, InjectorExecution.transform(new NeoTooltipAppendersInjector(), HANDLER, once, EnvType.CLIENT), "edited once");
	}

	@Test void withoutTheFabricBridgeOnlyTheDeliveryChanges(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		System.setProperty(GuestInjectorPruner.FABRIC_TOOLTIP_BRIDGE, "off");
		assertEquals(List.of("Diamond Sword", "Sharpness V", "clumsy line", "Durability 12/250"), tooltip(game(original)));
	}

	@Test void switchedOffTheHandlerIsLeftAsShipped(@TempDir Path work) throws Exception {
		byte[] bytes = InjectorExecution.compile(work, STAND_INS).get(HANDLER.replace('.', '/'));
		System.setProperty(KernelLifecycle.NEO_TOOLTIP_APPENDERS, "off");
		assertSame(bytes, InjectorExecution.transform(new NeoTooltipAppendersInjector(), HANDLER, bytes, EnvType.CLIENT));
	}
}

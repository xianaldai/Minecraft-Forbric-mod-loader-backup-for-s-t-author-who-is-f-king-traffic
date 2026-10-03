/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link ForgeClientConsumersInjector}'s output, run: the four client consumers the merge left asking NeoForge alone —
 * entity model layers, tooltip components, world preset editors and block tint caches — now also take what MinecraftForge
 * mods registered, where as merged a MinecraftForge mod's model layer and tint resolver were missing, its tooltip
 * component threw "Unknown TooltipComponent", and its preset editor was never offered.
 *
 * <p>The hooks are the kernel's real {@code KernelForgeClientConsumers} and {@code ForgeClientConsumerFlow}, compiled from
 * {@code src/runtime/java} against stand-ins for both families' managers, the four host classes in the shapes the rules
 * key on (three static calls and one method reference), and Guava's {@code ImmutableMap} and fastutil's
 * {@code Object2ObjectArrayMap}, which the test classpath does not carry.
 */
@ExecutesInjector(ForgeClientConsumersInjector.class)
@ResourceLock("system-properties")
class ForgeClientConsumersInjectorExecutionTest {
	private static final Path RUNTIME = Path.of("src/runtime/java/net/forbric/kernel/runtime");
	private static final List<String> HOSTS = ForgeClientConsumersInjector.RULES.stream().map(ForgeClientConsumersInjector.Rule::host).toList();

	private static String manager(String pkg, String name, String body) {
		return "package " + pkg + ";\n\npublic class " + name + " {\n" + body + "\n}\n";
	}

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"com.google.common.collect.ImmutableMap", """
					package com.google.common.collect;

					import java.util.AbstractMap;
					import java.util.LinkedHashMap;
					import java.util.Map;
					import java.util.Set;

					public final class ImmutableMap<K, V> extends AbstractMap<K, V> {
						private final Map<K, V> entries;

						private ImmutableMap(Map<K, V> entries) {
							this.entries = entries;
						}

						public static <K, V> Builder<K, V> builder() {
							return new Builder<>();
						}

						@Override
						public Set<Map.Entry<K, V>> entrySet() {
							return entries.entrySet();
						}

						public static final class Builder<K, V> {
							private final Map<K, V> entries = new LinkedHashMap<>();

							public Builder<K, V> put(K key, V value) {
								entries.put(key, value);
								return this;
							}

							public ImmutableMap<K, V> build() {
								return new ImmutableMap<>(Map.copyOf(entries));
							}
						}
					}
					""",
			"it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap",
			"package it.unimi.dsi.fastutil.objects; public class Object2ObjectArrayMap<K, V> extends java.util.LinkedHashMap<K, V> { }",
			"net.minecraft.client.model.geom.ModelLayerLocation", "package net.minecraft.client.model.geom; public record ModelLayerLocation(String id) { }",
			"net.minecraft.client.model.geom.builders.LayerDefinition", "package net.minecraft.client.model.geom.builders; public record LayerDefinition(String mesh) { }",
			"net.minecraft.world.inventory.tooltip.TooltipComponent", "package net.minecraft.world.inventory.tooltip; public record TooltipComponent(String kind) { }",
			"net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent", """
					package net.minecraft.client.gui.screens.inventory.tooltip;

					import net.minecraft.world.inventory.tooltip.TooltipComponent;
					import net.neoforged.neoforge.client.gui.ClientTooltipComponentManager;

					public interface ClientTooltipComponent {
						String draw();

						/** NeoForge's: vanilla's own kinds, then its manager, else unknown. */
						static ClientTooltipComponent create(TooltipComponent component) {
							if (component.kind().equals("bundle")) return () -> "vanilla bundle";
							ClientTooltipComponent result = ClientTooltipComponentManager.createClientTooltipComponent(component);
							if (result != null) return result;
							throw new IllegalArgumentException("Unknown TooltipComponent");
						}
					}
					""",
			"net.minecraft.world.level.levelgen.presets.WorldPreset", "package net.minecraft.world.level.levelgen.presets; public class WorldPreset { }",
			"net.minecraft.resources.ResourceKey", "package net.minecraft.resources; public record ResourceKey<T>(String id) { }",
			"net.minecraft.client.gui.screens.worldselection.PresetEditor", """
					package net.minecraft.client.gui.screens.worldselection;

					import java.util.Map;
					import java.util.Optional;
					import net.minecraft.resources.ResourceKey;
					import net.minecraft.world.level.levelgen.presets.WorldPreset;

					public interface PresetEditor {
						Map<Optional<ResourceKey<WorldPreset>>, PresetEditor> EDITORS = Map.of();

						String name();
					}
					""",
			"net.minecraft.world.level.ColorResolver", "package net.minecraft.world.level; public record ColorResolver(String id) { }"));

	static {
		STAND_INS.put("net.minecraft.client.color.block.BlockTintCache",
				"package net.minecraft.client.color.block; public record BlockTintCache(String resolver) { }");
		String layers = """
				public static void loadLayerDefinitions(com.google.common.collect.ImmutableMap.Builder<net.minecraft.client.model.geom.ModelLayerLocation,
						net.minecraft.client.model.geom.builders.LayerDefinition> builder) {
					builder.put(new net.minecraft.client.model.geom.ModelLayerLocation("%s"), new net.minecraft.client.model.geom.builders.LayerDefinition("%s mesh"));
				}
				""";
		STAND_INS.put("net.neoforged.neoforge.client.ClientHooks", manager("net.neoforged.neoforge.client", "ClientHooks",
				layers.formatted("neomod:wolf_armor", "neomod")));
		STAND_INS.put("net.minecraftforge.client.ForgeHooksClient", manager("net.minecraftforge.client", "ForgeHooksClient",
				layers.formatted("forgemod:golem", "forgemod")));
		String tooltips = """
				public static net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent createClientTooltipComponent(
						net.minecraft.world.inventory.tooltip.TooltipComponent component) {
					if (component.kind().equals("%s")) return () -> "%s tooltip";
					%s
				}
				""";
		STAND_INS.put("net.neoforged.neoforge.client.gui.ClientTooltipComponentManager", manager("net.neoforged.neoforge.client.gui",
				"ClientTooltipComponentManager", tooltips.formatted("neomod:pouch", "neomod", "return null;")));
		STAND_INS.put("net.minecraftforge.client.gui.ClientTooltipComponentManager", manager("net.minecraftforge.client.gui",
				"ClientTooltipComponentManager", tooltips.formatted("forgemod:backpack", "forgemod",
						"throw new IllegalArgumentException(\"Unknown TooltipComponent\");")));
		String presets = """
				public static net.minecraft.client.gui.screens.worldselection.PresetEditor get(
						net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> key) {
					return key != null && key.id().equals("%s") ? () -> "%s editor" : null;
				}
				""";
		STAND_INS.put("net.neoforged.neoforge.client.PresetEditorManager", manager("net.neoforged.neoforge.client", "PresetEditorManager",
				presets.formatted("neomod:islands", "neomod")));
		STAND_INS.put("net.minecraftforge.client.PresetEditorManager", manager("net.minecraftforge.client", "PresetEditorManager",
				presets.formatted("forgemod:skylands", "forgemod")));
		String tints = """
				public static void registerBlockTintCaches(net.minecraft.client.multiplayer.ClientLevel level,
						java.util.Map<net.minecraft.world.level.ColorResolver, net.minecraft.client.color.block.BlockTintCache> caches) {
					caches.put(new net.minecraft.world.level.ColorResolver("%s"), new net.minecraft.client.color.block.BlockTintCache("%s"));
				}
				""";
		STAND_INS.put("net.neoforged.neoforge.client.ColorResolverManager", manager("net.neoforged.neoforge.client", "ColorResolverManager",
				tints.formatted("neomod:sap", "neomod:sap")));
		STAND_INS.put("net.minecraftforge.client.ColorResolverManager", manager("net.minecraftforge.client", "ColorResolverManager",
				tints.formatted("forgemod:moss", "forgemod:moss")));
		STAND_INS.put("net.minecraft.client.model.geom.LayerDefinitions", """
				package net.minecraft.client.model.geom;

				import java.util.Map;
				import com.google.common.collect.ImmutableMap;
				import net.minecraft.client.model.geom.builders.LayerDefinition;
				import net.neoforged.neoforge.client.ClientHooks;

				public class LayerDefinitions {
					public static Map<ModelLayerLocation, LayerDefinition> createRoots() {
						ImmutableMap.Builder<ModelLayerLocation, LayerDefinition> builder = ImmutableMap.builder();
						builder.put(new ModelLayerLocation("minecraft:pig"), new LayerDefinition("vanilla mesh"));
						ClientHooks.loadLayerDefinitions(builder);
						return builder.build();
					}
				}
				""");
		STAND_INS.put("net.minecraft.client.gui.screens.worldselection.WorldCreationUiState", """
				package net.minecraft.client.gui.screens.worldselection;

				import java.util.Optional;
				import net.minecraft.resources.ResourceKey;
				import net.minecraft.world.level.levelgen.presets.WorldPreset;
				import net.neoforged.neoforge.client.PresetEditorManager;

				public class WorldCreationUiState {
					private final Optional<ResourceKey<WorldPreset>> preset;

					public WorldCreationUiState(String preset) {
						this.preset = Optional.of(new ResourceKey<>(preset));
					}

					public PresetEditor getPresetEditor() {
						return preset.map(PresetEditorManager::get).orElse(null);
					}
				}
				""");
		STAND_INS.put("net.minecraft.client.multiplayer.ClientLevel", """
				package net.minecraft.client.multiplayer;

				import java.util.function.Consumer;
				import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
				import net.minecraft.client.color.block.BlockTintCache;
				import net.minecraft.world.level.ColorResolver;
				import net.neoforged.neoforge.client.ColorResolverManager;

				public class ClientLevel {
					public final Object2ObjectArrayMap<ColorResolver, BlockTintCache> tintCaches;

					public ClientLevel() {
						this.tintCaches = make(new Object2ObjectArrayMap<>(), map -> {
							map.put(new ColorResolver("minecraft:grass"), new BlockTintCache("minecraft:grass"));
							ColorResolverManager.registerBlockTintCaches(this, map);
						});
					}

					private static <T> T make(T object, Consumer<? super T> init) {
						init.accept(object);
						return object;
					}
				}
				""");
	}

	@AfterEach void reset() {
		System.clearProperty("forbric.forgeClientConsumers");
		System.clearProperty("forbric.forgeClientInit");
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		for (String hook : List.of("KernelForgeClientConsumers", "ForgeClientConsumerFlow")) {
			Path source = RUNTIME.resolve(hook + ".java");
			assertTrue(Files.isRegularFile(source), "the game-side hook's source is part of the checkout: " + source.toAbsolutePath());
			sources.put("net/forbric/kernel/runtime/" + hook + ".java", Files.readString(source));
		}
		return InjectorExecution.compile(work, sources);
	}

	private static Object describe(ClassLoader loader, String what, Object... args) throws Throwable {
		return switch (what) {
			case "layers" -> ((Map<?, ?>) InjectorExecution.invokeStatic(loader.loadClass(HOSTS.get(0)), "createRoots")).keySet().stream()
					.map(key -> key.toString()).sorted().toList();
			case "tooltip" -> {
				Object component = InjectorExecution.construct(loader.loadClass("net.minecraft.world.inventory.tooltip.TooltipComponent"), args[0]);
				try {
					yield InjectorExecution.invoke(InjectorExecution.invokeStatic(loader.loadClass(HOSTS.get(1)), "create", component), "draw");
				} catch (IllegalArgumentException unknown) {
					yield unknown.getMessage();
				}
			}
			case "preset" -> {
				Object editor = InjectorExecution.invoke(InjectorExecution.construct(loader.loadClass(HOSTS.get(2)), args[0]), "getPresetEditor");
				yield editor == null ? null : InjectorExecution.invoke(editor, "name");
			}
			case "tints" -> {
				Object level = InjectorExecution.construct(loader.loadClass(HOSTS.get(3)));
				yield ((Map<?, ?>) level.getClass().getField("tintCaches").get(level)).keySet().stream().map(Object::toString).sorted().toList();
			}
			default -> throw new IllegalArgumentException(what);
		};
	}

	@Test void minecraftForgeModsReachTheFourClientConsumers(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String host : HOSTS) {
			String internal = host.replace('.', '/');
			byte[] out = InjectorExecution.transform(new ForgeClientConsumersInjector(), host, original.get(internal), EnvType.CLIENT);
			assertNotSame(original.get(internal), out, host + " is the reviewed shape");
			classes.put(internal, out);
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String host : HOSTS) assertEquals("", InjectorExecution.verify(classes.get(host.replace('.', '/')), loader), host);

		assertEquals(List.of("ModelLayerLocation[id=forgemod:golem]", "ModelLayerLocation[id=minecraft:pig]",
				"ModelLayerLocation[id=neomod:wolf_armor]"), describe(loader, "layers"), "both families' model layers are baked");
		assertEquals("forgemod tooltip", describe(loader, "tooltip", "forgemod:backpack"), "a MinecraftForge tooltip component draws");
		assertEquals("neomod tooltip", describe(loader, "tooltip", "neomod:pouch"));
		assertEquals("Unknown TooltipComponent", describe(loader, "tooltip", "nobody:thing"), "one nobody registered is unknown, as before");
		assertEquals("forgemod editor", describe(loader, "preset", "forgemod:skylands"), "a MinecraftForge preset editor is offered");
		assertEquals("neomod editor", describe(loader, "preset", "neomod:islands"));
		assertEquals(List.of("ColorResolver[id=forgemod:moss]", "ColorResolver[id=minecraft:grass]", "ColorResolver[id=neomod:sap]"),
				describe(loader, "tints"), "both families' tint resolvers get caches");

		ClassLoader stock = InjectorExecution.load(original);
		assertEquals(List.of("ModelLayerLocation[id=minecraft:pig]", "ModelLayerLocation[id=neomod:wolf_armor]"), describe(stock, "layers"),
				"premise: as merged, a MinecraftForge mod's layer is missing");
		assertEquals("Unknown TooltipComponent", describe(stock, "tooltip", "forgemod:backpack"),
				"premise: as merged, a MinecraftForge tooltip component throws");
		assertNull(describe(stock, "preset", "forgemod:skylands"), "premise: as merged, its preset editor is never offered");
		assertEquals(List.of("ColorResolver[id=minecraft:grass]", "ColorResolver[id=neomod:sap]"), describe(stock, "tints"),
				"premise: as merged, its tint resolver has no cache");
		for (String host : HOSTS) {
			byte[] once = classes.get(host.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new ForgeClientConsumersInjector(), host, once, EnvType.CLIENT),
					host + " is not funnelled twice");
		}
	}

	@Test void switchedOffEveryHostIsLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = compile(work);
		for (String property : List.of("forbric.forgeClientConsumers", "forbric.forgeClientInit")) {
			System.setProperty(property, "off");
			for (String host : HOSTS) {
				byte[] bytes = original.get(host.replace('.', '/'));
				assertSame(bytes, InjectorExecution.transform(new ForgeClientConsumersInjector(), host, bytes, EnvType.CLIENT), property + " " + host);
			}
			System.clearProperty(property);
		}
	}
}

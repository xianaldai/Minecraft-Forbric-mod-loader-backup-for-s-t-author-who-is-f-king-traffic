/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;

/**
 * {@link ForgeWorldModifierInjector}'s output, run: MinecraftForge's spawn-settings builder copies a biome's spawns, its
 * {@code forge:remove_spawns} removes the mobs it names, and NeoForge's {@code runModifiers} applies MinecraftForge's
 * biome and structure modifiers with its own — where as merged the copy threw {@code NoSuchMethodError} (the
 * {@code addAll(Iterable)} default the merge lost), the removal threw {@code ClassCastException} inside Forge's lambda,
 * and MinecraftForge's modifiers never reached the world.
 *
 * <p>The merged {@code WeightedList$Builder} has vanilla's {@code addAll(Collection)} and a {@code removeIf} over
 * weighted entries, with the descriptor MinecraftForge's lost {@code removeIf(Predicate<E>)} had. MinecraftForge's two
 * classes are compiled against a builder that still has {@code addAll(Iterable)}, which is then taken out of the class
 * file. The game-side {@code KernelForgeWorldgen} reaches both carriers' modifier registries; a stand-in under its name
 * has the real {@code removeIfValue}'s one line and appends a MinecraftForge modifier to each list.
 */
@ExecutesInjector(ForgeWorldModifierInjector.class)
@ResourceLock("system-properties")
class ForgeWorldModifierInjectorExecutionTest {
	private static final String SPAWNS = "net.minecraftforge.common.world.MobSpawnSettingsBuilder";
	private static final String REMOVE = "net.minecraftforge.common.world.ForgeBiomeModifiers$RemoveSpawnsBiomeModifier";
	private static final String HOOKS = "net.neoforged.neoforge.server.ServerLifecycleHooks";
	private static final String BUILDER = ForgeWorldModifierInjector.WEIGHTED_BUILDER;
	private static final List<String> TARGETS = List.of(SPAWNS, REMOVE, HOOKS);

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.util.random.Weighted", "package net.minecraft.util.random; public record Weighted<E>(E value, int weight) { }",
			"net.minecraft.util.random.WeightedList", """
					package net.minecraft.util.random;

					import java.util.ArrayList;
					import java.util.Collection;
					import java.util.List;
					import java.util.function.Predicate;

					public class WeightedList {
						public static class Builder<E> {
							public final List<Weighted<E>> entries = new ArrayList<>();

							/** Vanilla's. */
							public Builder<E> addAll(Collection<Weighted<E>> weighted) {
								entries.addAll(weighted);
								return this;
							}

							/** MinecraftForge's interface default, which the merged class does not have: removed from the class file. */
							public Builder<E> addAll(Iterable<Weighted<E>> weighted) {
								throw new AssertionError("taken out of the merged class");
							}

							/** The merged one: over weighted entries, under the descriptor MinecraftForge's removeIf(Predicate<E>) had. */
							public Builder<E> removeIf(Predicate<Weighted<E>> predicate) {
								entries.removeIf(predicate);
								return this;
							}
						}
					}
					""",
			"net.minecraft.world.level.biome.SpawnerData", "package net.minecraft.world.level.biome; public record SpawnerData(String type) { }",
			SPAWNS, """
					package net.minecraftforge.common.world;

					import java.util.List;
					import net.minecraft.util.random.Weighted;
					import net.minecraft.util.random.WeightedList;
					import net.minecraft.world.level.biome.SpawnerData;

					public class MobSpawnSettingsBuilder {
						/** MinecraftForge's copy of a biome's spawns, compiled against its addAll(Iterable) default. */
						public static WeightedList.Builder<SpawnerData> copy(List<Weighted<SpawnerData>> spawns) {
							WeightedList.Builder<SpawnerData> builder = new WeightedList.Builder<>();
							builder.addAll((Iterable<Weighted<SpawnerData>>) spawns);
							return builder;
						}
					}
					""",
			"net/minecraftforge/common/world/ForgeBiomeModifiers.java", """
					package net.minecraftforge.common.world;

					import java.util.Set;
					import java.util.function.Predicate;
					import net.minecraft.util.random.WeightedList;
					import net.minecraft.world.level.biome.SpawnerData;

					public class ForgeBiomeModifiers {
						/** forge:remove_spawns, compiled against removeIf(Predicate<E>). */
						public record RemoveSpawnsBiomeModifier(Set<String> types) {
							@SuppressWarnings({"rawtypes", "unchecked"})
							public void modify(WeightedList.Builder<SpawnerData> spawns) {
								Predicate<SpawnerData> named = data -> types.contains(data.type());
								spawns.removeIf((Predicate) named);
							}
						}
					}
					""",
			"net.neoforged.neoforge.registries.NeoForgeRegistries", """
					package net.neoforged.neoforge.registries;

					public class NeoForgeRegistries {
						public static class Keys {
							/** Registry keys, read with getstatic as NeoForge's ResourceKeys are (a String constant would be inlined). */
							public static final Object BIOME_MODIFIERS = "neoforge:biome_modifier";
							public static final Object STRUCTURE_MODIFIERS = "neoforge:structure_modifier";
						}
					}
					""",
			"net.minecraft.server.MinecraftServer", """
					package net.minecraft.server;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.Map;

					public class MinecraftServer {
						public final Map<Object, List<String>> registries = Map.of("neoforge:biome_modifier", List.of("neomod:add_crabs"),
								"neoforge:structure_modifier", List.of("neomod:bigger_huts"));
						public final List<String> applied = new ArrayList<>();
					}
					""",
			HOOKS, """
					package net.neoforged.neoforge.server;

					import java.util.List;
					import net.minecraft.server.MinecraftServer;
					import net.neoforged.neoforge.registries.NeoForgeRegistries;

					public class ServerLifecycleHooks {
						/** NeoForge's: both modifier lists materialised from the registries, then applied. */
						public static void runModifiers(MinecraftServer server) {
							java.util.Map<Object, List<String>> registries = server.registries;
							List<String> biomeModifiers = registries.get(NeoForgeRegistries.Keys.BIOME_MODIFIERS).stream().toList();
							List<String> structureModifiers = registries.get(NeoForgeRegistries.Keys.STRUCTURE_MODIFIERS).stream().toList();
							server.applied.addAll(biomeModifiers);
							server.applied.addAll(structureModifiers);
						}
					}
					""",
			"net.forbric.kernel.runtime.KernelForgeWorldgen", """
					package net.forbric.kernel.runtime;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.Predicate;
					import net.minecraft.util.random.Weighted;
					import net.minecraft.util.random.WeightedList;

					public final class KernelForgeWorldgen {
						public static <E> WeightedList.Builder<E> removeIfValue(WeightedList.Builder<E> builder, Predicate<E> predicate) {
							return builder.removeIf((Weighted<E> weighted) -> predicate.test(weighted.value()));
						}

						public static <T> List<T> withMinecraftForgeBiomeModifiers(List<T> neoforge) {
							return appended(neoforge, "forgemod:add_yetis");
						}

						public static <T> List<T> withMinecraftForgeStructureModifiers(List<T> neoforge) {
							return appended(neoforge, "forgemod:snowy_huts");
						}

						@SuppressWarnings("unchecked")
						private static <T> List<T> appended(List<T> neoforge, String forge) {
							List<T> out = new ArrayList<>(neoforge);
							out.add((T) forge);
							return out;
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(ForgeWorldModifierInjector.PROPERTY);
	}

	/** The merged shape: the builder without MinecraftForge's addAll(Iterable). */
	private static Map<String, byte[]> merged(Path work) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		ClassNode node = new ClassNode();
		new ClassReader(classes.get(BUILDER)).accept(node, 0);
		assertTrue(node.methods.removeIf(m -> m.name.equals("addAll") && m.desc.startsWith("(Ljava/lang/Iterable;)")));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		classes.put(BUILDER, writer.toByteArray());
		return classes;
	}

	/** A biome's spawns copied by MinecraftForge's builder, then forge:remove_spawns of zombies: the mobs left. */
	private static Object spawns(ClassLoader loader) throws Throwable {
		Class<?> data = loader.loadClass("net.minecraft.world.level.biome.SpawnerData");
		Class<?> weighted = loader.loadClass("net.minecraft.util.random.Weighted");
		List<Object> spawns = List.of(InjectorExecution.construct(weighted, InjectorExecution.construct(data, "minecraft:zombie"), 100),
				InjectorExecution.construct(weighted, InjectorExecution.construct(data, "minecraft:sheep"), 12));
		Object builder = InjectorExecution.invokeStatic(loader.loadClass(SPAWNS), "copy", spawns);
		InjectorExecution.invoke(InjectorExecution.construct(loader.loadClass(REMOVE), Set.of("minecraft:zombie")), "modify", builder);
		return ((List<?>) builder.getClass().getField("entries").get(builder)).stream().map(Object::toString).toList();
	}

	private static Object modifiersApplied(ClassLoader loader) throws Throwable {
		Object server = InjectorExecution.construct(loader.loadClass("net.minecraft.server.MinecraftServer"));
		InjectorExecution.invokeStatic(loader.loadClass(HOOKS), "runModifiers", server);
		return server.getClass().getField("applied").get(server);
	}

	@Test void minecraftForgeWorldModifiersRunInsideNeoForgesPass(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : TARGETS) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new ForgeWorldModifierInjector(), target, original.get(internal), EnvType.SERVER);
			assertNotSame(original.get(internal), out, target + " is the merged shape");
			classes.put(internal, out);
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : TARGETS) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);

		assertEquals(List.of("Weighted[value=SpawnerData[type=minecraft:sheep], weight=12]"), spawns(loader),
				"MinecraftForge's builder copies the spawns and forge:remove_spawns takes the zombies out");
		assertEquals(List.of("neomod:add_crabs", "forgemod:add_yetis", "neomod:bigger_huts", "forgemod:snowy_huts"), modifiersApplied(loader),
				"both families' biome and structure modifiers are applied in NeoForge's pass");

		ClassLoader stock = InjectorExecution.load(original);
		assertThrows(NoSuchMethodError.class, () -> spawns(stock), "premise: as merged, MinecraftForge's builder cannot link");
		Map<String, byte[]> onlyBuilder = new HashMap<>(original);
		onlyBuilder.put(SPAWNS.replace('.', '/'), classes.get(SPAWNS.replace('.', '/')));
		assertThrows(ClassCastException.class, () -> spawns(InjectorExecution.load(onlyBuilder)),
				"premise: as merged, forge:remove_spawns casts a weighted entry inside Forge's own lambda");
		assertEquals(List.of("neomod:add_crabs", "neomod:bigger_huts"), modifiersApplied(stock),
				"premise: as merged, MinecraftForge's modifiers never reach the world");
		for (String target : TARGETS) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new ForgeWorldModifierInjector(), target, once, EnvType.SERVER), target);
		}
	}

	@Test void switchedOffAllThreeClassesAreLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = merged(work);
		System.setProperty(ForgeWorldModifierInjector.PROPERTY, "off");
		for (String target : TARGETS) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new ForgeWorldModifierInjector(), target, bytes, EnvType.SERVER), target);
		}
	}
}

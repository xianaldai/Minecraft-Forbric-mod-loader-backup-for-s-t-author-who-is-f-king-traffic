/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;

/**
 * {@link RegistryWrapperAccessInjector}'s output, run: a mod looks a method up on the block registry's runtime class and
 * invokes it, as Meow Anti-Xray's {@code MinecraftCompat.findBlockById} does —
 * {@code BLOCK.getClass().getMethod("getOptional", Identifier.class).invoke(BLOCK, id)} — from its own package.
 *
 * <p>The registries, the wrappers and the mod are stand-ins compiled under their names, with the access each really has:
 * vanilla's two public, MinecraftForge's two package-private, built by a public factory in their own package as
 * {@code RegistryManager} builds them. {@code RegistryWrapperAccessInjectorTest} runs the real wrappers from the staged
 * carrier.
 */
@ExecutesInjector(RegistryWrapperAccessInjector.class)
@ResourceLock("system-properties")
class RegistryWrapperAccessInjectorExecutionTest {
	private static final String WRAPPER = "net.minecraftforge.registries.NamespacedWrapper";
	private static final String DEFAULTED_WRAPPER = "net.minecraftforge.registries.NamespacedDefaultedWrapper";
	private static final String MOD = "com.example.antixray.compat.MinecraftCompat";

	private static final Map<String, String> SOURCES = Map.of(
			"net.minecraft.resources.Identifier", """
					package net.minecraft.resources;

					public record Identifier(String namespace, String path) {
					}
					""",
			"net.minecraft.core.MappedRegistry", """
					package net.minecraft.core;

					import java.util.HashMap;
					import java.util.Map;
					import java.util.Optional;
					import net.minecraft.resources.Identifier;

					public class MappedRegistry<T> {
						protected final Map<Identifier, T> byId = new HashMap<>();

						public void register(Identifier id, T value) {
							byId.put(id, value);
						}

						public Optional<T> getOptional(Identifier id) {
							return Optional.ofNullable(byId.get(id));
						}
					}
					""",
			"net.minecraft.core.DefaultedMappedRegistry", """
					package net.minecraft.core;

					public class DefaultedMappedRegistry<T> extends MappedRegistry<T> {
					}
					""",
			WRAPPER, """
					package net.minecraftforge.registries;

					import java.util.HashMap;
					import java.util.Map;
					import java.util.Optional;
					import net.minecraft.core.MappedRegistry;
					import net.minecraft.resources.Identifier;

					class NamespacedWrapper<T> extends MappedRegistry<T> {
						private final Map<Identifier, T> delegate = new HashMap<>();

						NamespacedWrapper() {
						}

						@Override
						public void register(Identifier id, T value) {
							delegate.put(id, value);
						}

						@Override
						public Optional<T> getOptional(Identifier id) {
							return Optional.ofNullable(delegate.get(id));
						}

						void lock() {
						}
					}
					""",
			DEFAULTED_WRAPPER, """
					package net.minecraftforge.registries;

					import net.minecraft.resources.Identifier;

					class NamespacedDefaultedWrapper<T> extends NamespacedWrapper<T> {
						NamespacedDefaultedWrapper() {
						}

						public Identifier getDefaultKey() {
							return new Identifier("minecraft", "air");
						}
					}
					""",
			"net.minecraftforge.registries.RegistryManager", """
					package net.minecraftforge.registries;

					import net.minecraft.core.MappedRegistry;

					public final class RegistryManager {
						public static MappedRegistry<Object> block() {
							return new NamespacedDefaultedWrapper<>();
						}
					}
					""",
			MOD, """
					package com.example.antixray.compat;

					import java.util.Optional;
					import net.minecraft.core.MappedRegistry;
					import net.minecraft.resources.Identifier;

					public final class MinecraftCompat {
						public static Optional<?> findBlockById(MappedRegistry<?> blocks, Identifier id) throws ReflectiveOperationException {
							return (Optional<?>) blocks.getClass().getMethod("getOptional", Identifier.class).invoke(blocks, id);
						}

						public static Object defaultKey(MappedRegistry<?> blocks) throws ReflectiveOperationException {
							return blocks.getClass().getMethod("getDefaultKey").invoke(blocks);
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(RegistryWrapperAccessInjector.PROPERTY);
	}

	private static String internal(String binaryName) {
		return binaryName.replace('.', '/');
	}

	private static ClassLoader game(Map<String, byte[]> compiled, boolean repaired) {
		Map<String, byte[]> classes = new HashMap<>(compiled);
		if (repaired) {
			for (String wrapper : RegistryWrapperAccessInjector.WRAPPERS) {
				classes.put(internal(wrapper), InjectorExecution.transform(new RegistryWrapperAccessInjector(), wrapper, compiled.get(internal(wrapper)), EnvType.SERVER));
			}
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String wrapper : RegistryWrapperAccessInjector.WRAPPERS) assertEquals("", InjectorExecution.verify(classes.get(internal(wrapper)), loader));
		return loader;
	}

	/** The registry the mod is handed, with diamond ore in it: a wrapper on the merged game, vanilla's own otherwise. */
	private static Object blocks(ClassLoader loader, boolean wrapped) throws Throwable {
		Object blocks = wrapped
				? InjectorExecution.invokeStatic(loader.loadClass("net.minecraftforge.registries.RegistryManager"), "block")
				: InjectorExecution.construct(loader.loadClass("net.minecraft.core.DefaultedMappedRegistry"));
		InjectorExecution.invoke(blocks, "register", id(loader), "diamond ore");
		return blocks;
	}

	private static Object id(ClassLoader loader) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.minecraft.resources.Identifier"), "minecraft", "diamond_ore");
	}

	private static Object findBlockById(ClassLoader loader, Object blocks) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass(MOD), "findBlockById", blocks, id(loader));
	}

	private static Object defaultKey(ClassLoader loader, Object blocks) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass(MOD), "defaultKey", blocks);
	}

	@Test void aModInvokesWhatItLookedUpOnTheBlockRegistrysClass(@TempDir Path work) throws Throwable {
		Map<String, byte[]> compiled = InjectorExecution.compile(work, SOURCES);

		ClassLoader vanilla = game(compiled, false);
		assertEquals(Optional.of("diamond ore"), findBlockById(vanilla, blocks(vanilla, false)),
				"premise: on vanilla's public registry the mod's lookup works");

		ClassLoader merged = game(compiled, false);
		IllegalAccessException refused = assertThrows(IllegalAccessException.class, () -> findBlockById(merged, blocks(merged, true)),
				"control: as the carrier ships the wrappers, invoking a public method found on one is refused");
		assertEquals("class " + MOD + " cannot access a member of class " + WRAPPER + " with modifiers \"public\"", refused.getMessage());

		ClassLoader repaired = game(compiled, true);
		assertEquals(Optional.of("diamond ore"), findBlockById(repaired, blocks(repaired, true)));
		// A method the defaulted wrapper declares itself is refused on its own class, not NamespacedWrapper's.
		assertTrue(assertThrows(IllegalAccessException.class, () -> defaultKey(merged, blocks(merged, true))).getMessage()
				.contains("a member of class " + DEFAULTED_WRAPPER + " with modifiers \"public\""));
		assertEquals("Identifier[namespace=minecraft, path=air]", String.valueOf(defaultKey(repaired, blocks(repaired, true))));
	}

	/** Only the class header changes: the mod still cannot build a wrapper, and Forge's package-private members stay so. */
	@Test void onlyTheWrappersThemselvesBecomePublic(@TempDir Path work) throws Throwable {
		Map<String, byte[]> compiled = InjectorExecution.compile(work, SOURCES);
		ClassLoader repaired = game(compiled, true);
		for (String wrapper : RegistryWrapperAccessInjector.WRAPPERS) {
			Class<?> type = repaired.loadClass(wrapper);
			assertTrue(Modifier.isPublic(type.getModifiers()), wrapper);
			assertFalse(Modifier.isPublic(type.getDeclaredConstructor().getModifiers()), wrapper + "'s constructor keeps its access");

			ClassNode before = node(compiled.get(internal(wrapper)));
			ClassNode after = node(InjectorExecution.transform(new RegistryWrapperAccessInjector(), wrapper, compiled.get(internal(wrapper)), EnvType.SERVER));
			assertEquals(before.access | org.objectweb.asm.Opcodes.ACC_PUBLIC, after.access);
			assertEquals(before.methods.stream().map(m -> m.access + " " + m.name + m.desc).toList(),
					after.methods.stream().map(m -> m.access + " " + m.name + m.desc).toList());
			assertEquals(before.fields.stream().map(f -> f.access + " " + f.name).toList(), after.fields.stream().map(f -> f.access + " " + f.name).toList());
		}
		assertThrows(IllegalAccessException.class, () -> repaired.loadClass(DEFAULTED_WRAPPER).getDeclaredConstructor().newInstance());
	}

	@Test void aSecondPassTheSwitchAndEveryOtherClassAreLeftAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> compiled = InjectorExecution.compile(work, SOURCES);
		byte[] original = compiled.get(internal(WRAPPER));
		byte[] once = InjectorExecution.transform(new RegistryWrapperAccessInjector(), WRAPPER, original, EnvType.SERVER);
		assertNotSame(original, once);
		assertSame(once, InjectorExecution.transform(new RegistryWrapperAccessInjector(), WRAPPER, once, EnvType.SERVER));

		byte[] mapped = compiled.get("net/minecraft/core/MappedRegistry");
		assertSame(mapped, InjectorExecution.transform(new RegistryWrapperAccessInjector(), "net.minecraft.core.MappedRegistry", mapped, EnvType.SERVER));

		System.setProperty(RegistryWrapperAccessInjector.PROPERTY, "off");
		assertSame(original, InjectorExecution.transform(new RegistryWrapperAccessInjector(), WRAPPER, original, EnvType.SERVER));
		assertTrue(new RegistryWrapperAccessInjector().anchors().anchors().isEmpty(), "switched off, it claims no anchor");
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}

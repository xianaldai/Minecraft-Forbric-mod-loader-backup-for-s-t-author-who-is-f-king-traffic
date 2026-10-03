/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link SoundRegistryIdentityInjector}'s output, run: MinecraftForge's sound event registry takes two distinct sound
 * events with equal contents under two names and tells them apart, as vanilla's identity-keyed registry does — where as
 * merged its equality-keyed {@code HashBiMap} refused the second with "value already present". Every other registry
 * keeps MinecraftForge's own maps.
 *
 * <p>The hook is the kernel's real {@code IdentityValueBiMap}, compiled from {@code src/runtime/java}. Guava is not on
 * the test classpath, so {@code BiMap} and {@code HashBiMap} are stand-ins with Guava's contract for what the registry
 * uses: {@code put} refuses a value already bound to another key, {@code forcePut} moves it, and {@code inverse} is a
 * live view. The stand-in {@code ForgeRegistry} constructor fills its four indexes and its delegate map as
 * MinecraftForge's does, with the registry name as its second parameter.
 */
@ExecutesInjector(SoundRegistryIdentityInjector.class)
@ResourceLock("system-properties")
class SoundRegistryIdentityInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/IdentityValueBiMap.java");
	private static final String REGISTRY = SoundRegistryIdentityInjector.TARGET;
	private static final String PROPERTY = "forbric.soundRegistryIdentity";

	private static final Map<String, String> STAND_INS = Map.of(
			"com.google.common.collect.BiMap", """
					package com.google.common.collect;

					import java.util.Map;
					import java.util.Set;

					public interface BiMap<K, V> extends Map<K, V> {
						V forcePut(K key, V value);

						BiMap<V, K> inverse();

						@Override
						Set<V> values();
					}
					""",
			"com.google.common.collect.HashBiMap", """
					package com.google.common.collect;

					import java.util.AbstractMap;
					import java.util.Collections;
					import java.util.HashMap;
					import java.util.LinkedHashMap;
					import java.util.Map;
					import java.util.Objects;
					import java.util.Set;

					/** Guava's contract, by equality both ways. */
					public final class HashBiMap<K, V> extends AbstractMap<K, V> implements BiMap<K, V> {
						private final Map<K, V> forward;
						private final Map<V, K> backward;
						private final HashBiMap<V, K> inverse;

						public static <K, V> HashBiMap<K, V> create() {
							return new HashBiMap<>();
						}

						private HashBiMap() {
							forward = new LinkedHashMap<>();
							backward = new HashMap<>();
							inverse = new HashBiMap<>(this);
						}

						private HashBiMap(HashBiMap<V, K> original) {
							forward = original.backward;
							backward = original.forward;
							inverse = original;
						}

						@Override
						public V get(Object key) {
							return forward.get(key);
						}

						@Override
						public boolean containsKey(Object key) {
							return forward.containsKey(key);
						}

						@Override
						public V put(K key, V value) {
							return put(key, value, false);
						}

						@Override
						public V forcePut(K key, V value) {
							return put(key, value, true);
						}

						private V put(K key, V value, boolean force) {
							if (backward.containsKey(value) && !Objects.equals(backward.get(value), key)) {
								if (!force) throw new IllegalArgumentException("value already present: " + value);
								forward.remove(backward.get(value));
							}
							V old = forward.put(key, value);
							if (old != null) backward.remove(old);
							backward.put(value, key);
							return old;
						}

						@Override
						public V remove(Object key) {
							V old = forward.remove(key);
							if (old != null) backward.remove(old);
							return old;
						}

						@Override
						public BiMap<V, K> inverse() {
							return inverse;
						}

						@Override
						public Set<V> values() {
							return inverse.keySet();
						}

						@Override
						public Set<Map.Entry<K, V>> entrySet() {
							return Collections.unmodifiableSet(forward.entrySet());
						}
					}
					""",
			"net.minecraft.resources.Identifier", """
					package net.minecraft.resources;

					public record Identifier(String id) {
						@Override
						public String toString() {
							return id;
						}
					}
					""",
			"net.minecraft.resources.ResourceKey", "package net.minecraft.resources; public record ResourceKey(Identifier location) { }",
			"net.minecraft.sounds.SoundEvent", "package net.minecraft.sounds; public record SoundEvent(net.minecraft.resources.Identifier location) { }",
			"net.minecraftforge.registries.RegistryManager", "package net.minecraftforge.registries; public class RegistryManager { }",
			"net.minecraftforge.registries.RegistryBuilder", "package net.minecraftforge.registries; public class RegistryBuilder<V> { }",
			REGISTRY, """
					package net.minecraftforge.registries;

					import java.util.HashMap;
					import java.util.Map;
					import com.google.common.collect.BiMap;
					import com.google.common.collect.HashBiMap;
					import net.minecraft.resources.Identifier;
					import net.minecraft.resources.ResourceKey;

					public class ForgeRegistry<V> {
						private final Identifier name;
						private final BiMap<Integer, V> ids;
						private final BiMap<Identifier, V> names;
						private final BiMap<ResourceKey, V> keys;
						private final BiMap<Identifier, V> owners;
						private final Map<V, String> delegatesByValue;

						public ForgeRegistry(RegistryManager stage, Identifier name, RegistryBuilder<V> builder) {
							this.name = name;
							this.ids = HashBiMap.create();
							this.names = HashBiMap.create();
							this.keys = HashBiMap.create();
							this.owners = HashBiMap.create();
							this.delegatesByValue = new HashMap<>();
						}

						public void register(int id, Identifier key, V value) {
							ids.put(id, value);
							names.put(key, value);
							keys.put(new ResourceKey(key), value);
							owners.put(key, value);
							delegatesByValue.put(value, "holder of " + key);
						}

						public Identifier getKey(V value) {
							return names.inverse().get(value);
						}

						public Integer getId(V value) {
							return ids.inverse().get(value);
						}

						public String delegate(V value) {
							return delegatesByValue.get(value);
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/IdentityValueBiMap.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	private static Object identifier(ClassLoader loader, String id) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.minecraft.resources.Identifier"), id);
	}

	private static Object registry(ClassLoader loader, String name) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(REGISTRY), InjectorExecution.construct(loader.loadClass("net.minecraftforge.registries.RegistryManager")),
				identifier(loader, name), InjectorExecution.construct(loader.loadClass("net.minecraftforge.registries.RegistryBuilder")));
	}

	/** Two distinct sound events that are equal: the same location, as two mods' identical sound definitions are. */
	private static Object[] twins(ClassLoader loader) throws Throwable {
		Class<?> sound = loader.loadClass("net.minecraft.sounds.SoundEvent");
		Object location = identifier(loader, "minecraft:block.stone.hit");
		return new Object[] {InjectorExecution.construct(sound, location), InjectorExecution.construct(sound, location)};
	}

	@Test void equalSoundEventsRegisterUnderTwoNamesAndStayApart(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = REGISTRY.replace('.', '/');
		byte[] repaired = InjectorExecution.transform(new SoundRegistryIdentityInjector(), REGISTRY, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), repaired, "the constructor was edited");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		Object sounds = registry(loader, "minecraft:sound_event");
		Object[] twins = twins(loader);
		assertEquals(twins[0], twins[1], "premise: the two are equal and distinct");
		InjectorExecution.invoke(sounds, "register", 1, identifier(loader, "mod_a:hit"), twins[0]);
		InjectorExecution.invoke(sounds, "register", 2, identifier(loader, "mod_b:hit"), twins[1]);
		assertEquals(identifier(loader, "mod_a:hit"), InjectorExecution.invoke(sounds, "getKey", twins[0]), "each has its own name");
		assertEquals(identifier(loader, "mod_b:hit"), InjectorExecution.invoke(sounds, "getKey", twins[1]));
		assertEquals(2, InjectorExecution.invoke(sounds, "getId", twins[1]), "and its own id");
		assertEquals("holder of mod_a:hit", InjectorExecution.invoke(sounds, "delegate", twins[0]), "and its own holder");
		assertEquals("holder of mod_b:hit", InjectorExecution.invoke(sounds, "delegate", twins[1]));

		Object items = registry(loader, "minecraft:item");
		Object[] items2 = twins(loader);
		InjectorExecution.invoke(items, "register", 1, identifier(loader, "mod_a:thing"), items2[0]);
		assertThrows(IllegalArgumentException.class,
				() -> InjectorExecution.invoke(items, "register", 2, identifier(loader, "mod_b:thing"), items2[1]),
				"every other registry keeps MinecraftForge's own equality-keyed maps");

		ClassLoader stock = InjectorExecution.load(original);
		Object mergedSounds = registry(stock, "minecraft:sound_event");
		Object[] mergedTwins = twins(stock);
		InjectorExecution.invoke(mergedSounds, "register", 1, identifier(stock, "mod_a:hit"), mergedTwins[0]);
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> InjectorExecution.invoke(mergedSounds, "register", 2, identifier(stock, "mod_b:hit"), mergedTwins[1]),
				"premise: as merged, the second sound event is refused");
		assertTrue(refused.getMessage().startsWith("value already present"), refused.getMessage());
		assertSame(repaired, InjectorExecution.transform(new SoundRegistryIdentityInjector(), REGISTRY, repaired, EnvType.SERVER),
				"a constructor that already asks the kernel is left alone");
	}

	@Test void switchedOffTheRegistryIsLeftAsMinecraftForgeHasIt(@TempDir Path work) throws Exception {
		byte[] bytes = compile(work).get(REGISTRY.replace('.', '/'));
		System.setProperty(PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new SoundRegistryIdentityInjector(), REGISTRY, bytes, EnvType.SERVER));
	}
}

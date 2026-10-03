/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

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
 * {@link ForgeLootPoolConditionsInjector}'s output, run: a pool a MinecraftForge mod builds with
 * {@code when(ICondition)} keeps its condition, and a loot table pool whose {@code forge:condition} is false loads as
 * an empty pool in its place, as MinecraftForge's {@code LootTable} has it — where as merged the first was dropped and
 * the second parsed and never judged. A pool without the key, and one NeoForge's own conditions drop, are as before.
 *
 * <p>The stand-ins keep the merged shapes: a {@code LootPool} with NeoForge's constructor and MinecraftForge's
 * {@code forge_condition} field, its {@code Builder} with MinecraftForge's {@code when}, and NeoForge's
 * {@code CommonHooks.lootPoolsCodec} wrapping {@code LootPool.CODEC} in its conditional codec. DataFixerUpper's
 * {@code Codec} is reduced to one decode method. The game-side {@code KernelForgeConditions} reaches MinecraftForge's
 * condition registry; a stand-in under its name gives {@code poolElementCodec} the real one's answer:
 * {@code LootPool.CONDITIONAL_CODEC}.
 */
@ExecutesInjector(ForgeLootPoolConditionsInjector.class)
@ResourceLock("system-properties")
class ForgeLootPoolConditionsInjectorExecutionTest {
	private static final String BUILDER = ForgeLootPoolConditionsInjector.BUILDER;
	private static final String HOOKS = ForgeLootPoolConditionsInjector.HOOKS;

	private static final Map<String, String> STAND_INS = Map.of(
			"com.mojang.serialization.Codec", """
					package com.mojang.serialization;

					public interface Codec<A> {
						A decode(java.util.Map<String, Object> input);
					}
					""",
			"net.minecraftforge.common.crafting.conditions.ICondition", """
					package net.minecraftforge.common.crafting.conditions;

					public record ICondition(boolean test) {
					}
					""",
			"net.minecraft.world.level.storage.loot.providers.number.NumberProvider",
			"package net.minecraft.world.level.storage.loot.providers.number; public class NumberProvider { }",
			"net.minecraft.world.level.storage.loot.LootPool", """
					package net.minecraft.world.level.storage.loot;

					import java.util.List;
					import java.util.Map;
					import java.util.Optional;
					import com.mojang.serialization.Codec;
					import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
					import net.minecraftforge.common.crafting.conditions.ICondition;

					public class LootPool {
						public static final Codec<LootPool> CODEC = input -> new LootPool(List.of(input.get("entries")), List.of(), List.of(), null, null,
								Optional.ofNullable((String) input.get("name")));
						/** MinecraftForge's: an empty pool in place of one whose forge:condition is false. */
						public static final Codec<LootPool> CONDITIONAL_CODEC = input -> input.get("forge:condition") instanceof Boolean test && !test
								? new LootPool(List.of(), List.of(), List.of(), null, null, Optional.ofNullable((String) input.get("name")))
								: CODEC.decode(input);

						public final List<?> entries;
						public final Optional<String> name;
						/** MinecraftForge's, which only its encoder reads. */
						private Optional<ICondition> forge_condition;

						/** NeoForge's, which does not take the condition. */
						public LootPool(List<?> entries, List<?> conditions, List<?> functions, NumberProvider rolls, NumberProvider bonusRolls,
								Optional<String> name) {
							this.entries = entries;
							this.name = name;
						}

						public Optional<ICondition> encodedCondition() {
							return forge_condition;
						}

						public static class Builder {
							private Optional<String> name = Optional.empty();
							private ICondition forge_condition;

							public Builder name(String name) {
								this.name = Optional.of(name);
								return this;
							}

							/** MinecraftForge's. */
							public Builder when(ICondition condition) {
								this.forge_condition = condition;
								return this;
							}

							/** NeoForge's body. */
							public LootPool build() {
								return new LootPool(List.of("entry"), List.of(), List.of(), null, null, name);
							}
						}
					}
					""",
			"net.neoforged.neoforge.common.conditions.ConditionalOps", """
					package net.neoforged.neoforge.common.conditions;

					import java.util.Optional;
					import com.mojang.serialization.Codec;

					public class ConditionalOps {
						/** neoforge:conditions, judged first. */
						public static <T> Codec<Optional<T>> createConditionalCodec(Codec<T> element) {
							return input -> Boolean.FALSE.equals(input.get("neoforge:conditions")) ? Optional.empty() : Optional.of(element.decode(input));
						}
					}
					""",
			HOOKS, """
					package net.neoforged.neoforge.common;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.Map;
					import java.util.Optional;
					import java.util.function.BiConsumer;
					import com.mojang.serialization.Codec;
					import net.minecraft.world.level.storage.loot.LootPool;
					import net.neoforged.neoforge.common.conditions.ConditionalOps;

					public class CommonHooks {
						/** NeoForge's pool list codec: each element through its conditional wrapper around LootPool.CODEC. */
						public static Codec<List<LootPool>> lootPoolsCodec(BiConsumer<LootPool, String> nameSetter) {
							Codec<Optional<LootPool>> element = ConditionalOps.createConditionalCodec(LootPool.CODEC);
							return input -> {
								List<LootPool> pools = new ArrayList<>();
								for (Object pool : (List<?>) input.get("pools")) {
									@SuppressWarnings("unchecked")
									Optional<LootPool> decoded = element.decode((Map<String, Object>) pool);
									decoded.ifPresent(pools::add);
								}
								return pools;
							};
						}
					}
					""",
			"net.forbric.kernel.runtime.KernelForgeConditions", """
					package net.forbric.kernel.runtime;

					import com.mojang.serialization.Codec;
					import net.minecraft.world.level.storage.loot.LootPool;

					public final class KernelForgeConditions {
						public static Codec<LootPool> poolElementCodec(Codec<LootPool> neoforge) {
							return LootPool.CONDITIONAL_CODEC;
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(ForgeLootPoolConditionsInjector.PROPERTY);
	}

	private static Map<String, byte[]> transformed(Map<String, byte[]> original) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(BUILDER, HOOKS)) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new ForgeLootPoolConditionsInjector(), target, original.get(internal), EnvType.SERVER);
			assertNotSame(original.get(internal), out, target + " is the merged shape");
			classes.put(internal, out);
		}
		return classes;
	}

	/** A MinecraftForge mod's pool built with {@code when(condition)}: the condition its pool carries to the encoder. */
	private static Object built(ClassLoader loader) throws Throwable {
		Object condition = InjectorExecution.construct(loader.loadClass("net.minecraftforge.common.crafting.conditions.ICondition"), false);
		Object builder = InjectorExecution.construct(loader.loadClass(BUILDER));
		InjectorExecution.invoke(builder, "when", condition);
		Object pool = InjectorExecution.invoke(builder, "build");
		Object encoded = InjectorExecution.invoke(pool, "encodedCondition");
		return encoded == null ? Optional.empty() : ((Optional<?>) encoded).map(Object::toString);
	}

	/** A loot table's pools, each named, decoded through NeoForge's pool list codec: name and entry count per pool. */
	private static List<String> decoded(ClassLoader loader) throws Throwable {
		Object codec = InjectorExecution.invokeStatic(loader.loadClass(HOOKS), "lootPoolsCodec", (java.util.function.BiConsumer<Object, String>) (p, n) -> { });
		Map<String, Object> table = Map.of("pools", List.of(
				Map.of("name", "always", "entries", "gold"),
				Map.of("name", "forge-false", "entries", "diamond", "forge:condition", false),
				Map.of("name", "forge-true", "entries", "emerald", "forge:condition", true),
				Map.of("name", "neoforge-false", "entries", "netherite", "neoforge:conditions", false)));
		List<String> out = new java.util.ArrayList<>();
		Object pools = loader.loadClass("com.mojang.serialization.Codec").getMethod("decode", Map.class).invoke(codec, table);
		for (Object pool : (List<?>) pools) {
			out.add(((Optional<?>) pool.getClass().getField("name").get(pool)).map(Object::toString).orElse("?") + ":" + ((List<?>) pool.getClass().getField("entries").get(pool)).size());
		}
		return out;
	}

	@Test void minecraftForgePoolConditionsAreKeptAndJudged(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = transformed(original);
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(BUILDER, HOOKS)) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);

		assertEquals(Optional.of("ICondition[test=false]"), built(loader), "the when(ICondition) pool carries its condition, as MinecraftForge's does");
		assertEquals(List.of("always:1", "forge-false:0", "forge-true:1"), decoded(loader),
				"a false forge:condition empties its pool in place; NeoForge's own conditions still drop theirs first");

		ClassLoader stock = InjectorExecution.load(original);
		assertEquals(Optional.empty(), built(stock), "premise: as merged, the condition is dropped");
		assertEquals(List.of("always:1", "forge-false:1", "forge-true:1"), decoded(stock),
				"premise: as merged, forge:condition is parsed and never judged");
		for (String target : List.of(BUILDER, HOOKS)) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new ForgeLootPoolConditionsInjector(), target, once, EnvType.SERVER), target);
		}
	}

	@Test void switchedOffBothClassesAreLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		System.setProperty(ForgeLootPoolConditionsInjector.PROPERTY, "off");
		for (String target : List.of(BUILDER, HOOKS)) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new ForgeLootPoolConditionsInjector(), target, bytes, EnvType.SERVER), target);
		}
	}
}

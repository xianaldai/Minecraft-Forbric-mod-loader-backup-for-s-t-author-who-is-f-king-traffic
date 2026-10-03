/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;

/**
 * {@link ParticleGroupsInjector}'s output, run: a MinecraftForge mod's {@code registerParticleGroup} no longer throws,
 * and the engine NeoForge's constructor builds holds that group after NeoForge's own, without overriding a type a
 * NeoForge listener claimed.
 *
 * <p>The merged {@code ParticleEngine} pairs MinecraftForge's static API (a static {@code factories} map whose
 * initialiser lost the merge, and a static read of {@code particleRenderOrder}) with NeoForge's instance field of that
 * name and NeoForge's constructor. Java source cannot read an instance field statically, so the stand-in reads a
 * placeholder static that is renamed in the class file. The hook is the kernel's real {@code KernelParticleGroups},
 * compiled from {@code src/runtime/java}.
 */
@ExecutesInjector(ParticleGroupsInjector.class)
class ParticleGroupsInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelParticleGroups.java");
	private static final String ENGINE = ParticleGroupsInjector.ENGINE;
	private static final String ENGINE_INTERNAL = ParticleGroupsInjector.ENGINE_INTERNAL;

	private static final Map<String, String> STAND_INS = Map.of(
			"net.neoforged.neoforge.client.event.RegisterParticleGroupsEvent", """
					package net.neoforged.neoforge.client.event;

					import java.util.List;
					import java.util.Map;

					public class RegisterParticleGroupsEvent {
						public final Map<Object, Object> factories;
						public final List<Object> order;

						public RegisterParticleGroupsEvent(Map<Object, Object> factories, List<Object> order) {
							this.factories = factories;
							this.order = order;
						}
					}
					""",
			"net.neoforged.fml.ModLoader", """
					package net.neoforged.fml;

					import net.neoforged.neoforge.client.event.RegisterParticleGroupsEvent;

					public class ModLoader {
						/** NeoForge's listeners: one group of its own, and a claim on a type a MinecraftForge mod also uses. */
						public static void postEvent(Object event) {
							RegisterParticleGroupsEvent groups = (RegisterParticleGroupsEvent) event;
							groups.factories.put("neo:glow", "neoforge glow factory");
							groups.order.add("neo:glow");
							groups.factories.put("shared:spark", "neoforge spark factory");
							groups.order.add("shared:spark");
						}
					}
					""",
			ENGINE, """
					package net.minecraft.client.particle;

					import java.util.ArrayList;
					import java.util.HashMap;
					import java.util.List;
					import java.util.Map;
					import net.neoforged.fml.ModLoader;
					import net.neoforged.neoforge.client.event.RegisterParticleGroupsEvent;

					public class ParticleEngine {
						/** MinecraftForge's static map; its initialiser was in the <clinit> that lost the merge. */
						private static Map<Object, Object> factories;
						/** Stands in for the static read of particleRenderOrder; renamed in the class file. */
						private static List<Object> forgeOrderPlaceholder;
						private static final Object CLINIT = new Object();

						public Map<Object, Object> providers;
						private List<Object> particleRenderOrder;

						public ParticleEngine(Object level, Object textures) {
							Map<Object, Object> map = new HashMap<>();
							List<Object> list = new ArrayList<>();
							ModLoader.postEvent(new RegisterParticleGroupsEvent(map, list));
							this.providers = map;
							this.particleRenderOrder = list;
						}

						public static void registerParticleGroup(Object type, Object factory) {
							factories.putIfAbsent(type, factory);
							forgeOrderPlaceholder.add(type);
						}

						public List<Object> renderOrder() {
							return particleRenderOrder;
						}
					}
					""");

	/** javac's classes, with the placeholder read turned into the merged static read of NeoForge's instance field. */
	private static Map<String, byte[]> merged(Path work) throws Exception {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelParticleGroups.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, sources));
		ClassNode node = new ClassNode();
		new ClassReader(classes.get(ENGINE_INTERNAL)).accept(node, 0);
		node.fields.removeIf(f -> f.name.equals("forgeOrderPlaceholder"));
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode field && field.name.equals("forgeOrderPlaceholder")) field.name = "particleRenderOrder";
			}
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		classes.put(ENGINE_INTERNAL, writer.toByteArray());
		return classes;
	}

	private static Object engine(ClassLoader loader) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(ENGINE), new Object(), new Object());
	}

	@Test void aMinecraftForgeGroupRegistersAndReachesTheEngineAfterNeoForgesOwn(@TempDir Path work) throws Throwable {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, byte[]> original = merged(work);
		byte[] repaired = InjectorExecution.transform(new ParticleGroupsInjector(), ENGINE, original.get(ENGINE_INTERNAL), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(ENGINE_INTERNAL, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		Class<?> engineClass = loader.loadClass(ENGINE);
		InjectorExecution.invokeStatic(engineClass, "registerParticleGroup", "forge:sparkle", "forge sparkle factory");
		InjectorExecution.invokeStatic(engineClass, "registerParticleGroup", "shared:spark", "forge spark factory");
		Object engine = engine(loader);
		assertEquals(Map.of("neo:glow", "neoforge glow factory", "shared:spark", "neoforge spark factory",
				"forge:sparkle", "forge sparkle factory"), engine.getClass().getField("providers").get(engine),
				"a type NeoForge's listener claimed keeps NeoForge's factory");
		assertEquals(List.of("neo:glow", "shared:spark", "forge:sparkle"), InjectorExecution.invoke(engine, "renderOrder"),
				"MinecraftForge's groups go at the end of the render order, each once");

		Class<?> stock = InjectorExecution.load(original).loadClass(ENGINE);
		assertThrows(NullPointerException.class,
				() -> InjectorExecution.invokeStatic(stock, "registerParticleGroup", "forge:sparkle", "factory"),
				"premise: as merged, a MinecraftForge mod registering a particle group throws");
		assertSame(repaired, InjectorExecution.transform(new ParticleGroupsInjector(), ENGINE, repaired, EnvType.CLIENT),
				"a repaired engine is left alone");
	}
}

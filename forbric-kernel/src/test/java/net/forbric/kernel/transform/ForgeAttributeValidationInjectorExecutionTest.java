/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link ForgeAttributeValidationInjector}'s output, run: MinecraftForge's freeze-time attribute validation waits while
 * the kernel holds a client's attribute events, and runs once they are released; where as merged it ran on the first
 * freeze, asking every entity type for attributes nobody had posted yet (and setting off Better Nether's lazy entity
 * registration against a frozen registry).
 *
 * <p>The hook is the kernel's real {@code KernelForgeAttributes}, compiled from {@code src/runtime/java} against
 * stand-ins for the entity, attribute and hook types it names; the stand-in {@code DefaultAttributes.validate} records
 * that it ran.
 */
@ExecutesInjector(ForgeAttributeValidationInjector.class)
@ResourceLock("system-properties")
class ForgeAttributeValidationInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeAttributes.java");
	private static final String CALLBACKS = ForgeAttributeValidationInjector.TARGET;
	private static final String RUNTIME = "net.forbric.kernel.runtime.KernelForgeAttributes";

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.entity.LivingEntity", "package net.minecraft.world.entity; public class LivingEntity { }",
			"net.minecraft.world.entity.EntityType", "package net.minecraft.world.entity; public class EntityType<T> { }",
			"net.minecraft.world.entity.ai.attributes.AttributeSupplier",
			"package net.minecraft.world.entity.ai.attributes; public class AttributeSupplier { }",
			"net.minecraft.world.entity.ai.attributes.DefaultAttributes", """
					package net.minecraft.world.entity.ai.attributes;

					public class DefaultAttributes {
						public static final java.util.List<String> runs = new java.util.ArrayList<>();

						public static void validate() {
							runs.add("validated");
						}
					}
					""",
			"net.minecraftforge.common.ForgeHooks", """
					package net.minecraftforge.common;

					import java.util.Map;
					import net.minecraft.world.entity.EntityType;
					import net.minecraft.world.entity.LivingEntity;
					import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

					public class ForgeHooks {
						public static void modifyAttributes() {
						}

						public static Map<EntityType<? extends LivingEntity>, AttributeSupplier> getAttributesView() {
							return Map.of();
						}
					}
					""",
			"net.neoforged.neoforge.common.CommonHooks", """
					package net.neoforged.neoforge.common;

					import java.util.Map;
					import net.minecraft.world.entity.EntityType;
					import net.minecraft.world.entity.LivingEntity;
					import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

					public class CommonHooks {
						public static Map<EntityType<? extends LivingEntity>, AttributeSupplier> getAttributesView() {
							return Map.of();
						}
					}
					""",
			"net.minecraftforge.registries.GameData", """
					package net.minecraftforge.registries;

					import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

					public class GameData {
						public static class AttributeCallbacks {
							public void onValidate(Object owner, Object stage) {
								DefaultAttributes.validate();
							}
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(ForgeAttributeValidationInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelForgeAttributes.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	private static List<?> freeze(ClassLoader loader) throws Throwable {
		InjectorExecution.invoke(InjectorExecution.construct(loader.loadClass(CALLBACKS)), "onValidate", "registry", "freeze");
		return List.copyOf((List<?>) InjectorExecution.getStatic(
				loader.loadClass("net.minecraft.world.entity.ai.attributes.DefaultAttributes"), "runs"));
	}

	@Test void validationWaitsWhileTheAttributeEventsAreHeld(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = CALLBACKS.replace('.', '/');
		byte[] routed = InjectorExecution.transform(new ForgeAttributeValidationInjector(), CALLBACKS, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, routed);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(routed, loader));

		Class<?> kernel = loader.loadClass(RUNTIME);
		InjectorExecution.invokeStatic(kernel, "holdValidation");
		assertEquals(List.of(), freeze(loader), "the client's first freeze, before MinecraftForge's mods exist: no validation");
		InjectorExecution.invokeStatic(kernel, "releaseValidation");
		assertEquals(List.of("validated"), freeze(loader), "the freeze that closes the entrypoint window validates");

		ClassLoader merged = InjectorExecution.load(original);
		InjectorExecution.invokeStatic(merged.loadClass(RUNTIME), "holdValidation");
		assertEquals(List.of("validated"), freeze(merged), "premise: as merged, the hold is never consulted");
		assertSame(routed, InjectorExecution.transform(new ForgeAttributeValidationInjector(), CALLBACKS, routed, EnvType.CLIENT),
				"nothing left to route");
	}

	@Test void switchedOffTheCallbackIsLeftAsMinecraftForgeHasIt(@TempDir Path work) throws Exception {
		byte[] bytes = compile(work).get(CALLBACKS.replace('.', '/'));
		System.setProperty(ForgeAttributeValidationInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new ForgeAttributeValidationInjector(), CALLBACKS, bytes, EnvType.CLIENT));
	}
}

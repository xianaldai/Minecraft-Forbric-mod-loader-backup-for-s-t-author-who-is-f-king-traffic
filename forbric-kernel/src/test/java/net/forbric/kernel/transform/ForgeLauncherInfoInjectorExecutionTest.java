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

/**
 * {@link ForgeLauncherInfoInjector}'s output, run: a MinecraftForge mod whose static initialiser reaches a private
 * member through {@code ObfuscationReflectionHelper} initialises, instead of dying on ModLauncher's null
 * {@code Launcher.INSTANCE} and staying dead for the run.
 *
 * <p>The stand-in {@code FMLLoader}'s three methods open with {@code Launcher.INSTANCE}, as the carrier's do (which
 * {@code ForgeLauncherInfoInjectorTest} checks on the staged jar); the stand-in helper's {@code remapName} treats an
 * empty name function as "use the name as given", as Forge's does.
 */
@ExecutesInjector(ForgeLauncherInfoInjector.class)
@ResourceLock("system-properties")
class ForgeLauncherInfoInjectorExecutionTest {
	private static final String FML_LOADER = "net.minecraftforge.fml.loading.FMLLoader";
	private static final String MOD = "fixture.PhysicsMod";

	private static final Map<String, String> STAND_INS = Map.of(
			"cpw.mods.modlauncher.Launcher", """
					package cpw.mods.modlauncher;

					import java.util.List;
					import java.util.Optional;
					import java.util.function.Function;

					public class Launcher {
						/** Written only by ModLauncher's own constructor, which the kernel never runs. */
						public static Launcher INSTANCE;

						public Optional<Function<String, String>> findNameMapping(String naming) {
							return Optional.empty();
						}

						public String info() {
							return "ModLauncher";
						}

						public List<String> mods() {
							return List.of();
						}
					}
					""",
			FML_LOADER, """
					package net.minecraftforge.fml.loading;

					import java.util.List;
					import java.util.Optional;
					import java.util.function.Function;
					import cpw.mods.modlauncher.Launcher;

					public class FMLLoader {
						public static Optional<Function<String, String>> getNameFunction(String naming) {
							return Launcher.INSTANCE.findNameMapping(naming);
						}

						public static String getLauncherInfo() {
							return Launcher.INSTANCE.info();
						}

						public static List<String> modLauncherModList() {
							return Launcher.INSTANCE.mods();
						}
					}
					""",
			"net.minecraftforge.fml.util.ObfuscationReflectionHelper", """
					package net.minecraftforge.fml.util;

					import java.lang.reflect.Field;
					import net.minecraftforge.fml.loading.FMLLoader;

					public class ObfuscationReflectionHelper {
						public static String remapName(String name) {
							return FMLLoader.getNameFunction("srg").map(f -> f.apply(name)).orElse(name);
						}

						public static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
							Field field = owner.getDeclaredField(remapName(name));
							field.setAccessible(true);
							return field;
						}
					}
					""",
			"fixture.Vanilla", "package fixture; public class Vanilla { private static int gravity = 8; }",
			MOD, """
					package fixture;

					import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

					public class PhysicsMod {
						public static final int GRAVITY;

						static {
							try {
								GRAVITY = ObfuscationReflectionHelper.findField(Vanilla.class, "gravity").getInt(null);
							} catch (ReflectiveOperationException e) {
								throw new IllegalStateException(e);
							}
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(ForgeLauncherInfoInjector.PROPERTY);
	}

	@Test void aModReachingAPrivateMemberFromItsStaticInitialiserLoads(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] loader = original.get(FML_LOADER.replace('.', '/'));
		byte[] answered = InjectorExecution.transform(new ForgeLauncherInfoInjector(), FML_LOADER, loader, EnvType.SERVER);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(FML_LOADER.replace('.', '/'), answered);
		ClassLoader game = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(answered, game));

		assertEquals(8, InjectorExecution.getStatic(Class.forName(MOD, true, game), "GRAVITY"),
				"the mod initialises, and the name it asked for is used as given");
		Class<?> fml = game.loadClass(FML_LOADER);
		assertEquals(java.util.Optional.empty(), InjectorExecution.invokeStatic(fml, "getNameFunction", "srg"));
		assertEquals("Forbric kernel (no ModLauncher)", InjectorExecution.invokeStatic(fml, "getLauncherInfo"));
		assertEquals(List.of(), InjectorExecution.invokeStatic(fml, "modLauncherModList"));

		ClassLoader stock = InjectorExecution.load(original);
		ExceptionInInitializerError first = assertThrows(ExceptionInInitializerError.class, () -> Class.forName(MOD, true, stock));
		assertInstanceOf(NullPointerException.class, first.getCause(), "premise: Launcher.INSTANCE is null under the kernel");
		assertThrows(NoClassDefFoundError.class, () -> Class.forName(MOD, true, stock),
				"premise: and the mod stays dead for the rest of the run");
	}

	@Test void switchedOffOrWithoutModLauncherTheClassIsLeftAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] helper = original.get("net/minecraftforge/fml/util/ObfuscationReflectionHelper");
		assertSame(helper, InjectorExecution.transform(new ForgeLauncherInfoInjector(), FML_LOADER, helper, EnvType.SERVER),
				"no reference to ModLauncher, nothing to answer");
		byte[] loader = original.get(FML_LOADER.replace('.', '/'));
		System.setProperty(ForgeLauncherInfoInjector.PROPERTY, "off");
		assertSame(loader, InjectorExecution.transform(new ForgeLauncherInfoInjector(), FML_LOADER, loader, EnvType.SERVER));
	}
}

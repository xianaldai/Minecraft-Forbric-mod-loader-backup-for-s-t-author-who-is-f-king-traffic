/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link ForgeBindingsLookupInjector}'s output, run: MinecraftForge's {@code Bindings} initialises and finds the
 * provider its jar declares in {@code META-INF/services}, where untransformed it asks a game module layer the kernel
 * never builds and dies in its class initialiser.
 *
 * <p>The stand-ins are compiled with the lookup shape the edit keys on,
 * {@code ServiceLoader.load(FMLLoader.getGameLayer(), IBindingsProvider.class)}. The game loader here also serves the
 * services file, as the carrier jar does, because that file is what the classpath lookup is supposed to find.
 */
@ExecutesInjector(ForgeBindingsLookupInjector.class)
class ForgeBindingsLookupInjectorExecutionTest {
	private static final String BINDINGS = "net.minecraftforge.fml.Bindings";
	private static final String PROVIDER = "net.minecraftforge.fml.IBindingsProvider";

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraftforge.fml.loading.FMLLoader", """
					package net.minecraftforge.fml.loading;

					public class FMLLoader {
						/** FML's layer manager is never filled under the kernel. */
						public static ModuleLayer getGameLayer() {
							throw new IllegalStateException("no game layer");
						}
					}
					""",
			PROVIDER, """
					package net.minecraftforge.fml;

					public interface IBindingsProvider {
						String configEvents();
					}
					""",
			"net.minecraftforge.fml.ForgeBindings", """
					package net.minecraftforge.fml;

					public class ForgeBindings implements IBindingsProvider {
						@Override
						public String configEvents() {
							return "forge config events";
						}
					}
					""",
			BINDINGS, """
					package net.minecraftforge.fml;

					import java.util.ServiceLoader;
					import net.minecraftforge.fml.loading.FMLLoader;

					public class Bindings {
						private static final IBindingsProvider PROVIDER =
								ServiceLoader.load(FMLLoader.getGameLayer(), IBindingsProvider.class).findFirst().orElseThrow();

						public static String configEvents() {
							return PROVIDER.configEvents();
						}
					}
					""");

	@Test void bindingsFindsTheProviderItsJarDeclares(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] bindings = original.get(BINDINGS.replace('.', '/'));
		byte[] onClasspath = InjectorExecution.transform(new ForgeBindingsLookupInjector(), BINDINGS, bindings, EnvType.SERVER);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(BINDINGS.replace('.', '/'), onClasspath);
		Path services = Files.createDirectories(work.resolve("services")).resolve(PROVIDER);
		Files.writeString(services, "net.minecraftforge.fml.ForgeBindings\n");

		ClassLoader game = new Carrier(classes, services);
		assertEquals("", InjectorExecution.verify(onClasspath, game));
		assertEquals("forge config events", InjectorExecution.invokeStatic(Class.forName(BINDINGS, true, game), "configEvents"));

		ExceptionInInitializerError dead = assertThrows(ExceptionInInitializerError.class,
				() -> Class.forName(BINDINGS, true, new Carrier(original, services)));
		assertEquals("no game layer", dead.getCause().getMessage(),
				"premise: untransformed, every use of MinecraftForge's config events dies in this initialiser");
		assertSame(onClasspath, InjectorExecution.transform(new ForgeBindingsLookupInjector(), BINDINGS, onClasspath,
				EnvType.SERVER), "a lookup that no longer asks the layer is left alone");
	}

	/** Defines {@code classes} itself, asks the test's loader for the rest, and serves the carrier's services file. */
	private static final class Carrier extends ClassLoader {
		private final Map<String, byte[]> classes;
		private final Path services;

		Carrier(Map<String, byte[]> classes, Path services) {
			super(ForgeBindingsLookupInjectorExecutionTest.class.getClassLoader());
			this.classes = classes;
			this.services = services;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> defined = findLoadedClass(name);
				return defined != null ? defined : defineClass(name, bytes, 0, bytes.length);
			}
		}

		@Override
		protected Enumeration<URL> findResources(String name) throws IOException {
			return name.equals("META-INF/services/" + PROVIDER)
					? Collections.enumeration(java.util.List.of(services.toUri().toURL())) : Collections.emptyEnumeration();
		}
	}
}

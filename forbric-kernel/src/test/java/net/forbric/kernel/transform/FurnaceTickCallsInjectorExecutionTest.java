/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;

/**
 * {@link FurnaceTickCallsInjector}'s output, run: a furnace with something to smelt ticks, where as merged its first
 * tick throws {@code IncompatibleClassChangeError}, and a mod's furnace that overrides one of the methods is asked
 * through its override.
 *
 * <p>The merged class pairs NeoForge's static {@code serverTick} (which calls {@code canBurn} and {@code burn} with
 * {@code invokestatic}) with MinecraftForge's instance {@code canBurn} and {@code burn} of the same descriptors. Java
 * source cannot say that, so the stand-in is written with both and the class file is then made into the merged
 * shape: the static pair is dropped and the instance pair takes its names.
 */
@ExecutesInjector(FurnaceTickCallsInjector.class)
class FurnaceTickCallsInjectorExecutionTest {
	private static final String FURNACE = FurnaceTickCallsInjector.FURNACE;
	private static final String FURNACE_INTERNAL = FURNACE.replace('.', '/');

	private static final Map<String, String> STAND_INS = Map.of(
			FURNACE, """
					package net.minecraft.world.level.block.entity;

					public class AbstractFurnaceBlockEntity {
						public int fuel = 1;
						public int smelted;
						public String lastBurn = "";

						public static void serverTick(Object level, AbstractFurnaceBlockEntity furnace) {
							if (canBurn("iron_ore")) burn("iron_ore");
							furnace.fuel--;
						}

						private static boolean canBurn(String input) {
							throw new AssertionError("NeoForge's static canBurn is not in the merged class");
						}

						private static void burn(String input) {
							throw new AssertionError("NeoForge's static burn is not in the merged class");
						}

						protected boolean forgeCanBurn(String input) {
							return fuel > 0;
						}

						protected void forgeBurn(String input) {
							smelted++;
							lastBurn = input;
						}
					}
					""",
			"fixture.ModFurnace", """
					package fixture;

					import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

					public class ModFurnace extends AbstractFurnaceBlockEntity {
						@Override
						protected void forgeBurn(String input) {
							super.forgeBurn(input);
							lastBurn = "mod:" + input;
						}
					}
					""");

	/** The merged shape: the static pair gone, MinecraftForge's instance pair under the names serverTick calls. */
	private static Map<String, byte[]> merged(Path work) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		Map<String, String> names = Map.of(FURNACE_INTERNAL + ".forgeCanBurn(Ljava/lang/String;)Z", "canBurn",
				FURNACE_INTERNAL + ".forgeBurn(Ljava/lang/String;)V", "burn");
		for (String name : new String[] {FURNACE_INTERNAL, "fixture/ModFurnace"}) {
			ClassNode node = new ClassNode();
			new ClassReader(classes.get(name)).accept(node, 0);
			node.methods.removeIf(m -> (m.access & Opcodes.ACC_STATIC) != 0 && (m.name.equals("canBurn") || m.name.equals("burn")));
			ClassWriter writer = new ClassWriter(0);
			node.accept(new ClassRemapper(writer, new SimpleRemapper(names) {
				@Override
				public String mapMethodName(String owner, String method, String descriptor) {
					// The override in the mod's class is renamed with the method it overrides.
					return super.mapMethodName(FURNACE_INTERNAL, method, descriptor);
				}
			}));
			classes.put(name, writer.toByteArray());
		}
		return classes;
	}

	private static Object tick(ClassLoader loader, String type) throws Throwable {
		Object furnace = InjectorExecution.construct(loader.loadClass(type));
		InjectorExecution.invokeStatic(loader.loadClass(FURNACE), "serverTick", new Object(), furnace);
		return furnace;
	}

	@Test void aFurnaceWithSomethingToSmeltTicks(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		byte[] furnace = original.get(FURNACE_INTERNAL);
		byte[] repaired = InjectorExecution.transform(new FurnaceTickCallsInjector(), FURNACE, furnace, EnvType.SERVER);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(FURNACE_INTERNAL, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		Object vanilla = tick(loader, FURNACE);
		assertEquals(1, vanilla.getClass().getField("smelted").getInt(vanilla));
		assertEquals("iron_ore", vanilla.getClass().getField("lastBurn").get(vanilla));
		assertEquals(0, vanilla.getClass().getField("fuel").getInt(vanilla));
		Object mod = tick(loader, "fixture.ModFurnace");
		assertEquals("mod:iron_ore", mod.getClass().getField("lastBurn").get(mod),
				"the call goes to the ticked furnace, so a mod's override is the one that runs");

		IncompatibleClassChangeError thrown = assertThrows(IncompatibleClassChangeError.class,
				() -> tick(InjectorExecution.load(original), FURNACE));
		assertTrue(thrown.getMessage().contains("Expected static method"),
				"premise: as merged, the first smelt throws: " + thrown.getMessage());
		assertSame(repaired, InjectorExecution.transform(new FurnaceTickCallsInjector(), FURNACE, repaired, EnvType.SERVER),
				"a bridged tick is left alone");
	}
}

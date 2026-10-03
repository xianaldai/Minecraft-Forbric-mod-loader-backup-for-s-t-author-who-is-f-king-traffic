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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import net.fabricmc.api.EnvType;

/**
 * {@link DragonPartsInjector}'s output, run: a world's NeoForge-typed tracking callback adds an Ender Dragon and gets its
 * parts, each one a NeoForge {@code PartEntity}, where as merged NeoForge's {@code getParts()} answered null and adding
 * (or removing) the dragon threw. MinecraftForge's {@code getParts()} answers an empty array, and the debug hitboxes
 * still draw the parts.
 *
 * <p>The merged {@code Entity} has one {@code getParts()} per family, differing only in return type, which Java source
 * cannot declare; NeoForge's is written as {@code getNeoParts} and renamed in the class files. {@code NeoTracking} is
 * the shape of the server's callback, NeoForge's body.
 */
@ExecutesInjector(DragonPartsInjector.class)
@ResourceLock("system-properties")
class DragonPartsInjectorExecutionTest {
	private static final String PART = DragonPartsInjector.PART;
	private static final String DRAGON = DragonPartsInjector.DRAGON;
	private static final String HITBOXES = DragonPartsInjector.HITBOXES;
	private static final List<String> TARGETS = List.of(PART, DRAGON, HITBOXES);

	private static String partEntity(String pkg) {
		return """
				package %s;

				import net.minecraft.world.entity.Entity;

				public class PartEntity<T extends Entity> extends Entity {
					private final T parent;

					public PartEntity(T parent) {
						this.parent = parent;
					}

					public T getParent() {
						return parent;
					}
				}
				""".formatted(pkg);
	}

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.entity.Entity", """
					package net.minecraft.world.entity;

					public class Entity {
						public boolean isMultipartEntity() {
							return false;
						}

						public String describe() {
							return getClass().getSimpleName();
						}

						/** MinecraftForge's interface default. */
						public net.minecraftforge.entity.PartEntity<?>[] getParts() {
							return null;
						}

						/** NeoForge's interface default, renamed getParts in the class file. */
						public net.neoforged.neoforge.entity.PartEntity<?>[] getNeoParts() {
							return null;
						}
					}
					""",
			DragonPartsInjector.FORGE_PART.replace('/', '.'), partEntity("net.minecraftforge.entity"),
			DragonPartsInjector.NEO_PART.replace('/', '.'), partEntity("net.neoforged.neoforge.entity"),
			PART, """
					package net.minecraft.world.entity.boss.enderdragon;

					import net.minecraftforge.entity.PartEntity;

					public class EnderDragonPart extends PartEntity<EnderDragon> {
						public final String name;

						public EnderDragonPart(EnderDragon parent, String name) {
							super(parent);
							this.name = name;
						}

						@Override
						public String describe() {
							return name;
						}
					}
					""",
			DRAGON, """
					package net.minecraft.world.entity.boss.enderdragon;

					import net.minecraft.world.entity.Entity;
					import net.minecraftforge.entity.PartEntity;

					public class EnderDragon extends Entity {
						public final EnderDragonPart[] subEntities;

						public EnderDragon() {
							subEntities = new EnderDragonPart[] {new EnderDragonPart(this, "head"), new EnderDragonPart(this, "neck"),
									new EnderDragonPart(this, "body")};
						}

						@Override
						public boolean isMultipartEntity() {
							return true;
						}

						@Override
						public PartEntity<?>[] getParts() {
							return this.subEntities;
						}
					}
					""",
			HITBOXES, """
					package net.minecraft.client.renderer.debug;

					import java.util.ArrayList;
					import java.util.List;
					import net.minecraft.world.entity.Entity;
					import net.minecraftforge.entity.PartEntity;

					public class EntityHitboxDebugRenderer {
						/** MinecraftForge's body: the entity's box, then each part's. */
						public static List<String> showHitboxes(Entity entity) {
							List<String> drawn = new ArrayList<>();
							drawn.add(entity.describe());
							if (entity.isMultipartEntity()) {
								for (PartEntity<?> part : entity.getParts()) drawn.add(part.describe());
							}
							return drawn;
						}
					}
					""",
			"fixture.NeoTracking", """
					package fixture;

					import java.util.ArrayList;
					import java.util.List;
					import net.minecraft.world.entity.Entity;
					import net.neoforged.neoforge.entity.PartEntity;

					/** The shape of the server's onTrackingStart, NeoForge's body, and of Level.getEntities' cast. */
					public class NeoTracking {
						public final List<PartEntity<?>> dragonParts = new ArrayList<>();

						public void onTrackingStart(Entity entity) {
							if (entity.isMultipartEntity()) {
								for (PartEntity<?> part : entity.getNeoParts()) dragonParts.add(part);
							}
						}

						public static boolean isNeoForgePart(Object part) {
							return part instanceof PartEntity<?>;
						}

						public static int minecraftForgeParts(Entity entity) {
							return entity.getParts().length;
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(DragonPartsInjector.PROPERTY);
	}

	/** The merged shape: NeoForge's getter under the name it shares with MinecraftForge's. */
	private static Map<String, byte[]> merged(Path work) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		SimpleRemapper rename = new SimpleRemapper(Map.of(
				DragonPartsInjector.ENTITY + ".getNeoParts" + DragonPartsInjector.NEO_GET_PARTS, "getParts"));
		for (String name : new String[] {DragonPartsInjector.ENTITY, "fixture/NeoTracking"}) {
			ClassWriter writer = new ClassWriter(0);
			new ClassReader(classes.get(name)).accept(new ClassRemapper(writer, rename), 0);
			classes.put(name, writer.toByteArray());
		}
		return classes;
	}

	private static Map<String, byte[]> repaired(Map<String, byte[]> original, List<String> targets) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : targets) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new DragonPartsInjector(), target, original.get(internal), EnvType.CLIENT);
			assertNotSame(original.get(internal), out, target + " is the reviewed shape");
			classes.put(internal, out);
		}
		return classes;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> hitboxes(ClassLoader loader, Object entity) throws Throwable {
		return (List<Object>) InjectorExecution.invokeStatic(loader.loadClass(HITBOXES), "showHitboxes", entity);
	}

	@Test void addingADragonTracksItsPartsAsNeoForgeParts(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		Map<String, byte[]> classes = repaired(original, TARGETS);
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : TARGETS) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);

		Object dragon = InjectorExecution.construct(loader.loadClass(DRAGON));
		Class<?> tracking = loader.loadClass("fixture.NeoTracking");
		Object level = InjectorExecution.construct(tracking);
		InjectorExecution.invoke(level, "onTrackingStart", dragon);
		List<?> tracked = (List<?>) tracking.getField("dragonParts").get(level);
		assertArrayEquals((Object[]) dragon.getClass().getField("subEntities").get(dragon), tracked.toArray(),
				"NeoForge's getParts() answers the dragon's own parts");
		for (Object part : tracked) {
			assertEquals(true, InjectorExecution.invokeStatic(tracking, "isNeoForgePart", part), "a part is a NeoForge PartEntity");
			assertSame(dragon, InjectorExecution.invoke(part, "getParent"));
		}
		assertEquals(0, InjectorExecution.invokeStatic(tracking, "minecraftForgeParts", dragon),
				"MinecraftForge's getParts() is empty: no Forge-typed part exists any more");
		assertEquals(List.of("EnderDragon", "head", "neck", "body"), hitboxes(loader, dragon), "F3+B still draws the parts");

		ClassLoader stock = InjectorExecution.load(original);
		Object mergedDragon = InjectorExecution.construct(stock.loadClass(DRAGON));
		Object mergedLevel = InjectorExecution.construct(stock.loadClass("fixture.NeoTracking"));
		assertThrows(NullPointerException.class, () -> InjectorExecution.invoke(mergedLevel, "onTrackingStart", mergedDragon),
				"premise: as merged, adding a dragon to a world throws");
		Object mergedPart = ((Object[]) mergedDragon.getClass().getField("subEntities").get(mergedDragon))[0];
		assertEquals(false, InjectorExecution.invokeStatic(stock.loadClass("fixture.NeoTracking"), "isNeoForgePart", mergedPart),
				"premise: as merged, a part is not the NeoForge PartEntity every consumer casts it to");

		Map<String, byte[]> withoutRenderer = repaired(original, List.of(PART, DRAGON));
		Object partial = InjectorExecution.construct(InjectorExecution.load(withoutRenderer).loadClass(DRAGON));
		assertEquals(List.of("EnderDragon"), hitboxes(partial.getClass().getClassLoader(), partial),
				"premise: without the renderer's edit, MinecraftForge's loop meets the empty array and draws no part");
		for (String target : TARGETS) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new DragonPartsInjector(), target, once, EnvType.CLIENT),
					target + " is left alone the second time");
		}
	}

	@Test void switchedOffAllThreeClassesAreLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = merged(work);
		System.setProperty(DragonPartsInjector.PROPERTY, "off");
		for (String target : TARGETS) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new DragonPartsInjector(), target, bytes, EnvType.CLIENT), target);
		}
	}
}

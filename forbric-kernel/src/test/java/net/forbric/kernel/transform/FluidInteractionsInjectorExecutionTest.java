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
 * {@link FluidInteractionsInjector}'s output, run: once a MinecraftForge mod adds a fluid interaction, a block changing
 * next to a liquid runs it — through NeoForge's {@code canInteract}, at the neighbour where NeoForge's own rules ran out
 * — where as merged it never ran. A MinecraftForge mod's rule at an earlier neighbour beats vanilla's at a later one, as
 * on MinecraftForge, and NeoForge's own match still returns at once.
 *
 * <p>The hook is the kernel's real {@code KernelFluidInteractions}, compiled from {@code src/runtime/java} against
 * stand-ins for the level, positions, fluid state and both registries. NeoForge's {@code canInteract} walks the flow
 * directions and, at each neighbour, its rules (vanilla's lava-and-water); MinecraftForge's initializer adds its own copy
 * of that rule, and a stand-in mod adds lava-and-honey afterwards.
 */
@ExecutesInjector(FluidInteractionsInjector.class)
@ResourceLock("system-properties")
class FluidInteractionsInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFluidInteractions.java");
	private static final String NEO = FluidInteractionsInjector.NEO;
	private static final String FORGE = FluidInteractionsInjector.FORGE;

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.core.Direction", """
					package net.minecraft.core;

					public enum Direction {
						DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

						final int dx, dy, dz;

						Direction(int dx, int dy, int dz) {
							this.dx = dx;
							this.dy = dy;
							this.dz = dz;
						}

						public Direction getOpposite() {
							return values()[ordinal() ^ 1];
						}
					}
					""",
			"net.minecraft.core.BlockPos", """
					package net.minecraft.core;

					public record BlockPos(int x, int y, int z) {
						public BlockPos relative(Direction direction) {
							return new BlockPos(x + direction.dx, y + direction.dy, z + direction.dz);
						}
					}
					""",
			"net.minecraft.world.level.material.FluidState", """
					package net.minecraft.world.level.material;

					public record FluidState(String fluid) {
						/** MinecraftForge's. */
						public net.minecraftforge.fluids.FluidType getFluidType() {
							return new net.minecraftforge.fluids.FluidType(fluid);
						}
					}
					""",
			"net.minecraft.world.level.Level", """
					package net.minecraft.world.level;

					import java.util.HashMap;
					import java.util.Map;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.level.material.FluidState;

					public class Level {
						public final Map<BlockPos, String> blocks = new HashMap<>();

						public String at(BlockPos pos) {
							return blocks.getOrDefault(pos, "minecraft:air");
						}

						public FluidState getFluidState(BlockPos pos) {
							return new FluidState(at(pos));
						}

						public void setBlock(BlockPos pos, String block) {
							blocks.put(pos, block);
						}
					}
					""",
			"net.minecraftforge.fluids.FluidType", """
					package net.minecraftforge.fluids;

					public record FluidType(String id) {
					}
					""",
			FORGE, """
					package net.minecraftforge.fluids;

					import java.util.ArrayList;
					import java.util.HashMap;
					import java.util.List;
					import java.util.Map;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.level.Level;
					import net.minecraft.world.level.material.FluidState;

					public final class FluidInteractionRegistry {
						private static final Map<FluidType, List<InteractionInformation>> INTERACTIONS = new HashMap<>();

						static {
							addInteraction(new FluidType("minecraft:lava"), new InteractionInformation(
									(level, pos, relative, state) -> level.at(relative).equals("minecraft:water"),
									(level, pos, relative, state) -> level.setBlock(pos, "minecraftforge-copy:obsidian")));
						}

						public static void addInteraction(FluidType source, InteractionInformation information) {
							INTERACTIONS.computeIfAbsent(source, s -> new ArrayList<>()).add(information);
						}

						/** What MinecraftForge's onPlace asks; not edited here. */
						public static boolean canInteract(Level level, BlockPos pos) {
							return false;
						}

						public record InteractionInformation(HasFluidInteraction predicate, FluidInteraction interaction) {
						}

						public interface HasFluidInteraction {
							boolean test(Level level, BlockPos currentPos, BlockPos relativePos, FluidState currentState);
						}

						public interface FluidInteraction {
							void interact(Level level, BlockPos currentPos, BlockPos relativePos, FluidState currentState);
						}
					}
					""",
			NEO, """
					package net.neoforged.neoforge.fluids;

					import java.util.ArrayList;
					import java.util.HashMap;
					import java.util.List;
					import java.util.Map;
					import net.minecraft.core.BlockPos;
					import net.minecraft.core.Direction;
					import net.minecraft.world.level.Level;
					import net.minecraft.world.level.material.FluidState;

					public final class FluidInteractionRegistry {
						static final List<Direction> POSSIBLE_FLOW_DIRECTIONS = List.of(Direction.DOWN, Direction.SOUTH, Direction.NORTH,
								Direction.EAST, Direction.WEST);
						private static final Map<String, List<InteractionInformation>> INTERACTIONS = new HashMap<>();

						static {
							INTERACTIONS.put("minecraft:lava", new ArrayList<>(List.of(new InteractionInformation(
									(level, pos, relative, state) -> level.at(relative).equals("minecraft:water"),
									(level, pos, relative, state) -> level.setBlock(pos, "minecraft:obsidian")))));
						}

						/** NeoForge's walk: at each neighbour, every rule; the first match runs and returns. */
						public static boolean canInteract(Level level, BlockPos pos) {
							FluidState state = level.getFluidState(pos);
							for (Direction direction : POSSIBLE_FLOW_DIRECTIONS) {
								BlockPos relativePos = pos.relative(direction.getOpposite());
								List<InteractionInformation> interactions = INTERACTIONS.getOrDefault(state.fluid(), List.of());
								for (InteractionInformation interaction : interactions) {
									if (interaction.predicate().test(level, pos, relativePos, state)) {
										interaction.interaction().interact(level, pos, relativePos, state);
										return true;
									}
								}
							}
							return false;
						}

						public record InteractionInformation(HasFluidInteraction predicate, FluidInteraction interaction) {
						}

						public interface HasFluidInteraction {
							boolean test(Level level, BlockPos currentPos, BlockPos relativePos, FluidState currentState);
						}

						public interface FluidInteraction {
							void interact(Level level, BlockPos currentPos, BlockPos relativePos, FluidState currentState);
						}
					}
					""",
			"fixture.HoneyMod", """
					package fixture;

					import net.minecraftforge.fluids.FluidInteractionRegistry;
					import net.minecraftforge.fluids.FluidType;

					/** A MinecraftForge mod adding a rule: lava next to honey becomes honeystone. */
					public class HoneyMod {
						public static void register() {
							FluidInteractionRegistry.addInteraction(new FluidType("minecraft:lava"), new FluidInteractionRegistry.InteractionInformation(
									(level, pos, relative, state) -> level.at(relative).equals("mod:honey"),
									(level, pos, relative, state) -> level.setBlock(pos, "mod:honeystone")));
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(FluidInteractionsInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelFluidInteractions.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	private static Map<String, byte[]> repaired(Map<String, byte[]> original, List<String> targets) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : targets) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new FluidInteractionsInjector(), target, original.get(internal), EnvType.SERVER);
			assertNotSame(original.get(internal), out, target + " is the reviewed shape");
			classes.put(internal, out);
		}
		return classes;
	}

	/** The game, with the MinecraftForge mod's rule added after MinecraftForge's registry initialised. */
	private static ClassLoader game(Map<String, byte[]> classes) throws Throwable {
		ClassLoader loader = InjectorExecution.load(classes);
		InjectorExecution.invokeStatic(loader.loadClass("fixture.HoneyMod"), "register");
		return loader;
	}

	/**
	 * Lava at the origin with {@code neighbours} (block per position) around it: whether NeoForge's {@code canInteract}
	 * reacted, then what the lava became.
	 */
	private static List<Object> neighbourChanged(ClassLoader loader, Map<List<Integer>, String> neighbours) throws Throwable {
		Class<?> posType = loader.loadClass("net.minecraft.core.BlockPos");
		Object level = InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.Level"));
		Object origin = InjectorExecution.construct(posType, 0, 0, 0);
		InjectorExecution.invoke(level, "setBlock", origin, "minecraft:lava");
		for (var neighbour : neighbours.entrySet()) {
			List<Integer> at = neighbour.getKey();
			InjectorExecution.invoke(level, "setBlock", InjectorExecution.construct(posType, at.get(0), at.get(1), at.get(2)), neighbour.getValue());
		}
		Object reacted = InjectorExecution.invokeStatic(loader.loadClass(NEO), "canInteract", level, origin);
		return List.of(reacted, InjectorExecution.invoke(level, "at", origin));
	}

	// The neighbours in NeoForge's order: down's opposite (above) first, south's opposite (north) third.
	private static final List<Integer> FIRST = List.of(0, 1, 0), THIRD = List.of(0, 0, 1);

	@Test void aMinecraftForgeModsRuleRunsWhereNeoForgesRunOut(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		Map<String, byte[]> classes = repaired(original, List.of(NEO, FORGE));
		ClassLoader loader = game(classes);
		for (String target : List.of(NEO, FORGE)) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);

		assertEquals(List.of(true, "mod:honeystone"), neighbourChanged(loader, Map.of(FIRST, "mod:honey")),
				"lava next to honey runs the MinecraftForge mod's rule");
		assertEquals(List.of(true, "mod:honeystone"), neighbourChanged(loader, Map.of(FIRST, "mod:honey", THIRD, "minecraft:water")),
				"a mod's rule at an earlier neighbour beats vanilla's at a later one, as on MinecraftForge");
		assertEquals(List.of(true, "minecraft:obsidian"), neighbourChanged(loader, Map.of(FIRST, "minecraft:water", THIRD, "mod:honey")),
				"NeoForge's own match returns at once: the liquid reacts once, with vanilla's rule");
		assertEquals(List.of(false, "minecraft:lava"), neighbourChanged(loader, Map.of(FIRST, "minecraft:stone")));

		assertEquals(List.of(false, "minecraft:lava"), neighbourChanged(game(original), Map.of(FIRST, "mod:honey")),
				"premise: as merged, a neighbour change never runs a MinecraftForge mod's rule");
		assertEquals(List.of(false, "minecraft:lava"),
				neighbourChanged(game(repaired(original, List.of(NEO))), Map.of(FIRST, "mod:honey")),
				"premise: without MinecraftForge's registry handing its rules over, NeoForge's walk has nothing to ask");
		for (String target : List.of(NEO, FORGE)) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new FluidInteractionsInjector(), target, once, EnvType.SERVER),
					target + " is left alone the second time");
		}
	}

	@Test void switchedOffBothRegistriesAreLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = compile(work);
		System.setProperty(FluidInteractionsInjector.PROPERTY, "off");
		for (String target : List.of(NEO, FORGE)) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new FluidInteractionsInjector(), target, bytes, EnvType.SERVER), target);
		}
	}
}

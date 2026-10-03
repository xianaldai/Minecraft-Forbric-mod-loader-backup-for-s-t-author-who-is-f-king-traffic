/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link ForgeBrewingRecipesInjector}'s output, run: a MinecraftForge mod's brewing recipe added through the merged
 * {@code PotionBrewing.Builder} brews, where as merged the first brewing-stand check throws {@code ClassCastException}.
 *
 * <p>The stand-in builder keeps the merged pairing: MinecraftForge's {@code add} appends to the one recipe list that a
 * NeoForge-typed reader walks. The hook is the kernel's real {@code KernelBrewing}, compiled from
 * {@code src/runtime/java} against stand-ins for the two {@code IBrewingRecipe} interfaces and {@code ItemStack}.
 */
@ExecutesInjector(ForgeBrewingRecipesInjector.class)
class ForgeBrewingRecipesInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelBrewing.java");
	private static final String BUILDER = ForgeBrewingRecipesInjector.BUILDER;
	private static final String STACK = "net.minecraft.world.item.ItemStack";

	private static String recipeInterface(String pkg) {
		return """
				package %s;

				import net.minecraft.world.item.ItemStack;

				public interface IBrewingRecipe {
					boolean isInput(ItemStack input);

					boolean isIngredient(ItemStack ingredient);

					ItemStack getOutput(ItemStack input, ItemStack ingredient);
				}
				""".formatted(pkg);
	}

	private static final Map<String, String> STAND_INS = Map.of(
			STACK, "package net.minecraft.world.item; public record ItemStack(String item) { }",
			"net.minecraftforge.common.brewing.IBrewingRecipe", recipeInterface("net.minecraftforge.common.brewing"),
			"net.neoforged.neoforge.common.brewing.IBrewingRecipe", recipeInterface("net.neoforged.neoforge.common.brewing"),
			"net.minecraft.world.item.alchemy.PotionBrewing", """
					package net.minecraft.world.item.alchemy;

					import java.util.ArrayList;
					import java.util.List;
					import net.minecraft.world.item.ItemStack;

					public class PotionBrewing {
						@SuppressWarnings({"rawtypes", "unchecked"})
						public static class Builder {
							private final List recipes = new ArrayList();

							/** MinecraftForge's add: the recipe goes into the list unconverted. */
							public Builder add(net.minecraftforge.common.brewing.IBrewingRecipe recipe) {
								recipes.add(recipe);
								return this;
							}

							/** NeoForge's registry reads the same list as its own interface. */
							public ItemStack brew(ItemStack input, ItemStack ingredient) {
								for (Object entry : recipes) {
									net.neoforged.neoforge.common.brewing.IBrewingRecipe recipe = (net.neoforged.neoforge.common.brewing.IBrewingRecipe) entry;
									if (recipe.isInput(input) && recipe.isIngredient(ingredient)) return recipe.getOutput(input, ingredient);
								}
								return input;
							}
						}
					}
					""",
			"fixture.ModRecipe", """
					package fixture;

					import net.minecraft.world.item.ItemStack;

					public class ModRecipe implements net.minecraftforge.common.brewing.IBrewingRecipe {
						public boolean isInput(ItemStack input) {
							return input.item().equals("awkward_potion");
						}

						public boolean isIngredient(ItemStack ingredient) {
							return ingredient.item().equals("mod:glow_berry");
						}

						public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
							return new ItemStack("mod:glowing_potion");
						}
					}
					""");

	private static Object brew(ClassLoader loader, String ingredient) throws Throwable {
		Object builder = InjectorExecution.construct(loader.loadClass(BUILDER));
		InjectorExecution.invoke(builder, "add", InjectorExecution.construct(loader.loadClass("fixture.ModRecipe")));
		Class<?> stack = loader.loadClass(STACK);
		return InjectorExecution.invoke(builder, "brew", InjectorExecution.construct(stack, "awkward_potion"),
				InjectorExecution.construct(stack, ingredient));
	}

	@Test void aMinecraftForgeRecipeBrewsThroughNeoForgesReader(@TempDir Path work) throws Throwable {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelBrewing.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		String internal = BUILDER.replace('.', '/');
		byte[] wrapped = InjectorExecution.transform(new ForgeBrewingRecipesInjector(), BUILDER, original.get(internal), EnvType.SERVER);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, wrapped);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(wrapped, loader));

		assertEquals("ItemStack[item=mod:glowing_potion]", String.valueOf(brew(loader, "mod:glow_berry")));
		assertEquals("ItemStack[item=awkward_potion]", String.valueOf(brew(loader, "minecraft:sugar")),
				"an ingredient the recipe does not take brews nothing");

		assertThrows(ClassCastException.class, () -> brew(InjectorExecution.load(original), "mod:glow_berry"),
				"premise: as merged, the first brewing-stand check throws");
		assertSame(wrapped, InjectorExecution.transform(new ForgeBrewingRecipesInjector(), BUILDER, wrapped, EnvType.SERVER),
				"an add that already wraps is left alone");
	}
}

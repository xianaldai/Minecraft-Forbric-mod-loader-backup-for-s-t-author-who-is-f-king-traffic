/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The real merged {@code SessionSearchTrees}: exactly vanilla's two producers and MinecraftForge's reader are handed
 * to {@code KernelCreativeSearch}, NeoForge's keyed methods — the ones the creative screen calls — are left as they
 * are, and NeoForge's own patched class, whose producers already delegate, is not touched.
 */
@ResourceLock("system-properties")
class CreativeSearchTreesInjectorTest {
	private static final Path MERGED = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEOFORGE_PATCHED = TestFixtures.stagedRoot().resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path HELPER_CLASS = Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelCreativeSearch.class");
	private static final String TREES = CreativeSearchTreesInjector.TREES;

	@AfterEach void reset() { System.clearProperty(CreativeSearchTreesInjector.PROPERTY); }

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst()
				.orElseThrow(() -> new AssertionError(node.name + " has no " + name + desc));
	}

	private static List<String> calls(MethodNode method) {
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
		}
		return calls;
	}

	@Test void theMergedClassHandsVanillasProducersAndForgesReaderToTheHelper() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, TREES);
		byte[] out = new CreativeSearchTreesInjector().transform(CreativeSearchTreesInjector.TARGET, original, null);
		assertNotSame(original, out, "the staged merged class is the shape the repair was written for");
		ClassNode before = node(original);
		ClassNode after = node(out);

		String[][] rewritten = {
				{"updateCreativeTooltips", CreativeSearchTreesInjector.NAMES_DESC, "updateNames"},
				{"updateCreativeTags", CreativeSearchTreesInjector.TAGS_DESC, "updateTags"},
				{"getSearchTree", CreativeSearchTreesInjector.READ_DESC, "tree"}};
		for (String[] row : rewritten) {
			assertTrue(calls(method(before, row[0], row[1])).stream()
					.anyMatch(call -> call.startsWith(CreativeSearchTreesInjector.FORGE_REGISTRY + ".")
							|| row[0].equals("getSearchTree")),
					"premise: as merged, " + row[0] + " is MinecraftForge's body");
			MethodNode repaired = method(after, row[0], row[1]);
			List<String> calls = calls(repaired);
			assertEquals(1, calls.size(), row[0] + " is now one call: " + calls);
			assertTrue(calls.get(0).startsWith(CreativeSearchTreesInjector.HELPER + "." + row[2] + "("), calls::toString);
			new Analyzer<>(new BasicVerifier()).analyze(TREES, repaired);
		}

		// What the creative screen calls is exactly as merged.
		for (String[] keyed : new String[][] {
				{"updateCreativeTooltips", "(Lnet/minecraft/core/HolderLookup$Provider;Ljava/util/List;"
						+ CreativeSearchTreesInjector.KEY + ")V"},
				{"updateCreativeTags", "(Ljava/util/List;" + CreativeSearchTreesInjector.KEY + ")V"},
				{"creativeNameSearch", "(" + CreativeSearchTreesInjector.KEY + ")" + CreativeSearchTreesInjector.TREE},
				{"creativeTagSearch", "(" + CreativeSearchTreesInjector.KEY + ")" + CreativeSearchTreesInjector.TREE}}) {
			assertEquals(calls(method(before, keyed[0], keyed[1])), calls(method(after, keyed[0], keyed[1])), keyed[0]);
		}
		assertEquals(before.methods.size(), after.methods.size(), "no method added or removed");

		assertSame(out, new CreativeSearchTreesInjector().transform(CreativeSearchTreesInjector.TARGET, out, null),
				"a second pass changes nothing");
		System.setProperty(CreativeSearchTreesInjector.PROPERTY, "off");
		assertSame(original, new CreativeSearchTreesInjector().transform(CreativeSearchTreesInjector.TARGET, original, null));
	}

	/**
	 * The rewrite leaves MinecraftForge's two lambdas unreachable beside NeoForge's live ones of the same names, which
	 * is what DuplicateLambdaPruneInjector exists to drop -- but only if it runs after the rewrite. KernelBoot
	 * registers this repair first for that reason; the premise half shows the prune alone cannot see them.
	 */
	@Test void thePruneDropsTheLambdasTheRewriteOrphans() {
		byte[] original = NativeCoremodParityTest.read(MERGED, TREES);
		byte[] pruneFirst = new CreativeSearchTreesInjector().transform(CreativeSearchTreesInjector.TARGET,
				new DuplicateLambdaPruneInjector().transform(CreativeSearchTreesInjector.TARGET, original, null), null);
		byte[] repairFirst = new DuplicateLambdaPruneInjector().transform(CreativeSearchTreesInjector.TARGET,
				new CreativeSearchTreesInjector().transform(CreativeSearchTreesInjector.TARGET, original, null), null);
		assertFalse(forgeLambdas(node(pruneFirst)).isEmpty(), "premise: a prune that runs first still sees them called");
		assertEquals(List.of(), forgeLambdas(node(repairFirst)), "after the rewrite they are orphans and go");
	}

	/** MinecraftForge's producer lambdas: instance methods that take the registry map's entries. */
	private static List<String> forgeLambdas(ClassNode node) {
		return node.methods.stream()
				.filter(m -> m.name.startsWith("lambda$updateCreative") && m.desc.startsWith("(Ljava/util/Map$Entry;"))
				.map(m -> m.name + m.desc).toList();
	}

	@Test void neoForgesOwnPatchedClassIsLeftAlone() {
		byte[] neo = NativeCoremodParityTest.read(NEOFORGE_PATCHED, TREES);
		assertSame(neo, new CreativeSearchTreesInjector().transform(CreativeSearchTreesInjector.TARGET, neo, null),
				"NeoForge's vanilla producers already delegate to the keyed ones");
	}

	/** Every call the repair writes resolves to a static method of the compiled game-side helper. */
	@Test void theHelperHasEveryMethodTheRepairCalls() throws Exception {
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(HELPER_CLASS),
				"compile the staged runtime source set before this test: " + HELPER_CLASS);
		ClassNode helper = node(Files.readAllBytes(HELPER_CLASS));
		ClassNode repaired = node(new CreativeSearchTreesInjector().transform(CreativeSearchTreesInjector.TARGET,
				NativeCoremodParityTest.read(MERGED, TREES), null));
		int linked = 0;
		for (MethodNode method : repaired.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(CreativeSearchTreesInjector.HELPER)) continue;
				MethodNode target = method(helper, call.name, call.desc);
				assertTrue((target.access & Opcodes.ACC_STATIC) != 0 && (target.access & Opcodes.ACC_PUBLIC) != 0,
						call.name + " is public static");
				linked++;
			}
		}
		assertEquals(3, linked);
	}
}

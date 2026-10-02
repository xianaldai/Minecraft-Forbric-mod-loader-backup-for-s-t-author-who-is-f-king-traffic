/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** NeoForge's real BrandingControl and the merged base's real F3 version line name Forbric's release. */
@ResourceLock("system-properties")
class ForbricBrandingInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEOFORGE_RUNTIME = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");

	@AfterEach void reset() { System.clearProperty(ForbricBrandingInjector.PROPERTY); }

	@Test void theTitleScreenNamesForbricAndCountsEveryInstalledMod() throws Exception {
		assumeTrue(Files.isRegularFile(NEOFORGE_RUNTIME), "neoforge runtime not staged: " + NEOFORGE_RUNTIME);
		String internal = ForbricBrandingInjector.BRANDING_CONTROL.replace('.', '/');
		byte[] original = NativeCoremodParityTest.read(NEOFORGE_RUNTIME, internal);
		byte[] out = new ForbricBrandingInjector().transform(ForbricBrandingInjector.BRANDING_CONTROL, original, null);
		assertNotSame(original, out);
		MethodNode compute = method(out, "computeBranding");
		List<String> calls = calls(compute);
		assertTrue(calls.contains(ForbricBrandingInjector.BRANDING + ".installedModCount"), calls::toString);
		assertTrue(calls.contains(ForbricBrandingInjector.BRANDING + ".display"), calls::toString);
		assertFalse(calls.stream().anyMatch(c -> c.endsWith("ModList.get") || c.endsWith("NeoForgeVersion.getVersion")), calls::toString);
		assertEquals(1, indys(compute), "only the \"Minecraft \" concat is left");
		new Analyzer<>(new BasicVerifier()).analyze(internal, compute);
		assertSame(out, new ForbricBrandingInjector().transform(ForbricBrandingInjector.BRANDING_CONTROL, out, null), "a second pass adds nothing");
		System.setProperty(ForbricBrandingInjector.PROPERTY, "off");
		assertSame(original, new ForbricBrandingInjector().transform(ForbricBrandingInjector.BRANDING_CONTROL, original, null));
	}

	@Test void f3ShowsForbricsReleaseAndBrand() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED), "merged base not staged: " + MERGED);
		String internal = ForbricBrandingInjector.DEBUG_VERSION.replace('.', '/');
		byte[] original = NativeCoremodParityTest.read(MERGED, internal);
		byte[] out = new ForbricBrandingInjector().transform(ForbricBrandingInjector.DEBUG_VERSION, original, null);
		assertNotSame(original, out);
		MethodNode display = method(out, "display");
		List<String> calls = calls(display);
		assertEquals(List.of("net/minecraft/SharedConstants.getCurrentVersion", "net/minecraft/WorldVersion.name",
				ForbricBrandingInjector.BRANDING + ".display", ForbricBrandingInjector.BRANDING + ".brand",
				"net/minecraft/client/gui/components/debug/DebugScreenDisplayer.addPriorityLine"), calls);
		new Analyzer<>(new BasicVerifier()).analyze(internal, display);
		assertSame(out, new ForbricBrandingInjector().transform(ForbricBrandingInjector.DEBUG_VERSION, out, null));
		assertSame(original, new ForbricBrandingInjector().transform("net.minecraft.client.gui.screens.TitleScreen", original, null));
	}

	private static MethodNode method(byte[] bytes, String name) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
		return out;
	}

	private static int indys(MethodNode method) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof InvokeDynamicInsnNode) n++;
		return n;
	}
}

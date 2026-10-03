package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.TraceClassVisitor;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * MixinOverloadPin's diagnosis, made by the real pipeline and read where a player reads it: the mod's finding.
 *
 * <p>The fixture's {@code SkyPass} is shaped like a merged class — two bodies named {@code lambda$render$0}, the
 * vanilla one orphaned — and its config plugin installs the production DuplicateLambdaPruneInjector as the
 * pre-Mixin chain, which the harness does not do, so the vanilla shape is pruned and recorded as a real boot would.
 * The guest mixin is written for that vanilla shape. Nothing can make it bind: the surviving lambda takes two
 * Strings, so MixinHandlerShim declines to guess, and Mixin rejects the descriptor in both runs.
 *
 * <p>What the stage decides is only what the failure SAYS. With it on, the mod's finding carries why ("a lambda of
 * the render body the byte merge did not keep"); with {@code -Dforbric.mixinOverloadPin=off} it is the bare
 * InvalidInjectionException. And it decides nothing else: the woven class and what it returns are identical in
 * both runs, because a diagnosis that moved the injection would put it somewhere the mod did not ask for.
 */
class MixinOverloadPinWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/overloadpin");
	private static final String CONFIG = "overloadpin.mixins.json";
	private static final String MOD = "skyfixture";
	private static final String TARGET = "fixture/overloadpin/SkyPass";
	private static final String MIXIN = "fixture.overloadpin.mixin.SkyPassMixin";
	private static final String VANILLA_SHAPE = "(Ljava/lang/String;Ljava/lang/StringBuilder;)V";
	private static final String REASON = "its beforeSky targets lambda$render$0, a lambda of the render body the byte "
			+ "merge did not keep";
	private static final String BARE = "its mixin " + MIXIN + " failed to apply to fixture.overloadpin.SkyPass "
			+ "(InvalidInjectionException)";
	private static final String EXPLAINED_LOG = "SkyPassMixin.beforeSky cannot bind to " + TARGET + ".lambda$render$0";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result explained;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveBothWays() throws Exception {
		fixture = WeaveHarness.fixture(work, "overloadpin", List.of(
				SOURCES.resolve("fixture/overloadpin/SkyPass.java"),
				SOURCES.resolve("fixture/overloadpin/SkyProbe.java"),
				SOURCES.resolve("fixture/overloadpin/MergePrunePlugin.java"),
				SOURCES.resolve("fixture/overloadpin/mixin/SkyPassMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		explained = run("explained", "on");
		off = run("overload-pin-off", "off");
	}

	@Test void theFailureCarriesTheMergesReasonOnlyWhileTheStageIsOn() throws Exception {
		for (WeaveHarness.Result run : List.of(explained, off)) {
			// The stage's evidence must exist in BOTH runs, or the control would differ for the wrong reason.
			assertTrue(run.printed("[OverloadPinFixture] pre-Mixin chain installed"), run.describe());
			assertTrue(run.printed("dropped 1 orphaned bod(ies) so a mixin selecting by name cannot bind to the half "
					+ "that lost: lambda$render$0" + VANILLA_SHAPE), run.describe());
			assertFalse(declares(run.defined(TARGET), "lambda$render$0", VANILLA_SHAPE),
					"the pruner's drop did not reach the defined class — " + run.describe());

			// What the woven code did: the injection landed nowhere, in particular not on the surviving lambda.
			assertTrue(run.printed(WeaveHarnessMain.DONE + " drew sky+moon"), run.describe());
			assertFalse(run.printed("[sky hook ran]"), run.describe());
			// Mixin merges the handler before the descriptor check rejects it; what must not exist is a call to it.
			assertFalse(callsHandler(run.defined(TARGET)), "something calls beforeSky — " + run.describe());
			WeaveHarness.assertWovenAndVerified(run, TARGET, fixture);
			assertTrue(run.printed("InvalidInjectionException Invalid descriptor"), run.describe());

			List<WeaveHarness.Finding> losses = mixinLosses(run);
			assertEquals(1, losses.size(), run.label() + " findings: " + run.findings());
			assertTrue(losses.get(0).confirmedRequired(), losses.toString());
		}

		assertTrue(explainedHolds(explained), "with the stage on, the finding must say why: " + mixinLosses(explained)
				+ "\n" + explained.describe());
		assertEquals(BARE + " — " + REASON, mixinLosses(explained).get(0).detail());
		assertTrue(offHolds(off), "with the stage off, the finding must be Mixin's bare exception: " + mixinLosses(off)
				+ "\n" + off.describe());
	}

	/**
	 * The diagnosis rewrites nothing: the class the game defines is the same with the stage off. Compared as text
	 * because Mixin stamps a random session id on every merged member, and that id is the only byte allowed to differ.
	 */
	@Test void theDiagnosisChangesNoBytes() throws Exception {
		assertEquals(normalised(off.defined(TARGET)), normalised(explained.defined(TARGET)),
				"MixinOverloadPin changed what was woven into " + TARGET);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theOffControlFlipsEveryDiagnosisAssertion() {
		assertTrue(explainedHolds(explained) && !explainedHolds(off), "diagnosis predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(explained), "control predicate does not separate the runs");
	}

	private static boolean explainedHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = mixinLosses(run);
		return run.printed(EXPLAINED_LOG) && losses.size() == 1 && losses.get(0).detail().endsWith(" — " + REASON);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = mixinLosses(run);
		return !run.printed(EXPLAINED_LOG) && losses.size() == 1 && losses.get(0).detail().equals(BARE);
	}

	/** KernelMixinErrorHandler's row for the mixin as a whole, the one MixinOverloadPin's reason is written into. */
	private static List<WeaveHarness.Finding> mixinLosses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().equals("mixin:" + CONFIG + ":" + MIXIN)
				&& f.modId().equals(MOD)).toList();
	}

	private static boolean callsHandler(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node.methods.stream().flatMap(m -> Arrays.stream(m.instructions.toArray()))
				.anyMatch(insn -> insn instanceof MethodInsnNode call && call.name.endsWith("beforeSky"));
	}

	/** The class as text, with Mixin's per-session random id (stamped on every merged member) blanked out. */
	private static String normalised(byte[] bytes) {
		StringWriter text = new StringWriter();
		new ClassReader(bytes).accept(new TraceClassVisitor(new PrintWriter(text)), 0);
		return text.toString().replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<session>");
	}

	private static boolean declares(byte[] bytes, String name, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
		return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
	}

	private static WeaveHarness.Result run(String label, String pin) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.overloadpin.SkyProbe", "render", Map.of("forbric.mixinOverloadPin", pin));
	}
}

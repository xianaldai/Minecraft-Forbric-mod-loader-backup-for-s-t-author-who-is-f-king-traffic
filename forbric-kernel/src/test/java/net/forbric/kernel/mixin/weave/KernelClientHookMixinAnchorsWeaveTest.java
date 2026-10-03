package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.KernelClientHookMixinAnchors;

/**
 * KernelClientHookMixinAnchors through the real weave: FancyMenu's NeoForge client-init hook, a constructor
 * {@code @Inject} right after {@code ClientHooks.initClientHooks}, on a client whose constructor makes that call
 * through the kernel's relay instead (as the coremod pass leaves the merged base).
 *
 * <p>The probe builds the client and reports what its constructor ran, in order. Adapted, the hook's anchor follows
 * the relay and FancyMenu initialises right after the client hooks, before the window. With
 * {@code -Dforbric.clientHookMixinAnchors=off} the anchor names a call the constructor no longer makes: FancyMenu never
 * initialises, and the final audit names the lost injector.
 */
class KernelClientHookMixinAnchorsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/clienthookanchors");
	private static final String CONFIG = "clienthookanchors.mixins.json";
	private static final String MOD = "fancymenu";
	private static final String CLIENT = "net/minecraft/client/Minecraft";
	private static final String RELAY = "net/forbric/kernel/runtime/KernelForgeClientInit";
	private static final String HANDLER = "after_initClientHooks_NeoForge_FancyMenu";
	private static final String INITIALISED = WeaveHarnessMain.DONE + " [options(cfg), client hooks, fancymenu init, window]";
	private static final String NEVER_INITIALISED = WeaveHarnessMain.DONE + " [options(cfg), client hooks, window]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(6, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "clienthookanchors", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("anchors-off", Map.of(KernelClientHookMixinAnchors.PROPERTY, "off"));
	}

	@Test void fancyMenuInitialisesRightAfterTheRelayedClientHooks() throws Exception {
		assertTrue(adapted.printed(INITIALISED), adapted.describe());
		assertEquals(List.of(), losses(adapted), adapted.findings() + "\n" + adapted.describe());
		assertTrue(handlerFollowsRelay(adapted), "the handler is not woven right after the relay call — " + adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, CLIENT, fixture);
	}

	@Test void switchedOffTheHookNamesACallTheConstructorNoLongerMakes() throws Exception {
		assertTrue(off.printed(NEVER_INITIALISED), off.describe());
		assertEquals(1, losses(off).size(), off.findings() + "\n" + off.describe());
		assertTrue(losses(off).get(0).id().contains("#" + HANDLER + "("), losses(off).toString());
		assertFalse(handlerFollowsRelay(off), off.describe());
		WeaveHarness.assertWovenAndVerified(off, CLIENT, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAnchorAssertion() throws Exception {
		assertTrue(initialisedHolds(adapted) && !initialisedHolds(off), "the adapted predicate does not separate the runs");
		assertTrue(lostHolds(off) && !lostHolds(adapted), "the control predicate does not separate the runs");
	}

	private static boolean initialisedHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(INITIALISED) && losses(run).isEmpty() && handlerFollowsRelay(run);
	}

	private static boolean lostHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(NEVER_INITIALISED) && losses(run).size() == 1 && !handlerFollowsRelay(run);
	}

	/** Whether the first call after the relay call in the woven constructor is the merged FancyMenu handler. */
	private static boolean handlerFollowsRelay(WeaveHarness.Result run) throws Exception {
		ClassNode client = new ClassNode();
		new ClassReader(run.defined(CLIENT)).accept(client, 0);
		MethodNode constructor = client.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
		boolean relayed = false;
		for (AbstractInsnNode insn : constructor.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (relayed) return call.owner.equals(CLIENT) && call.name.endsWith(HANDLER);
			relayed = call.owner.equals(RELAY) && call.name.equals("initClientHooks");
		}
		return false;
	}

	/** The final audit's rows for the mod's required injectors that attached nowhere and are not settled either way. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.NEOFORGE, EnvType.CLIENT,
				"fixture.clienthookanchors.Probe", "probe", properties);
	}
}

package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.ForgeTransferShapeAudit;

/**
 * ForgeTransferShapeAudit through the real weave: the last stage before a class is defined, certifying the reviewed
 * transfer implementations by the fingerprint of their FINAL bytes.
 *
 * <p>A MinecraftForge mod's mixin hooks {@code receiveEnergy} in a stand-in under the name of MinecraftForge's
 * {@code EnergyStorage}, and in the same store under a name the audit does not review, and carries a method under the
 * certificate's own name. The fixture's bytes can never match the reviewed 26.2 fingerprint, so what this exercises is
 * the refusal: the audited class is defined without the marker the kernel's transfer adapters look for, and the
 * decline names the fingerprint of the bytes that were actually defined, after Mixin. The audit has no off switch;
 * the control is the unreviewed twin in the same run, woven by the same mixin, which keeps every method it was given.
 * The approval path needs the real 26.2 classes and is covered by the staged transfer tests.
 */
class ForgeTransferShapeAuditWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/transferaudit");
	private static final String CONFIG = "transferaudit.mixins.json";
	private static final String MOD = "energytweaks";
	private static final String STORAGE = "net/minecraftforge/energy/EnergyStorage";
	private static final String TWIN = "fixture/transferaudit/UnreviewedStorage";
	private static final Pattern REPORT = Pattern.compile(Pattern.quote(WeaveHarnessMain.DONE)
			+ " EnergyStorage\\[received=4 marker=false declined=transfer-critical bytecode differs from the reviewed 26\\.2 shape"
			+ " \\(([0-9a-f]{64})\\)\\] UnreviewedStorage\\[received=6 marker=true declined=the final definition did not receive a"
			+ " transfer-shape certificate\\] hooks=\\[EnergyStorage 4, UnreviewedStorage 6\\]");

	@TempDir static Path work;
	private static Path fixture;
	private static Path dump;
	private static WeaveHarness.Result run;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(5, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "transferaudit", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		dump = work.resolve("transfer-dump");
		run = WeaveHarness.run(work, "audited", fixture, CONFIG, MOD, Ecosystem.FORGE, EnvType.SERVER,
				"fixture.transferaudit.Probe", "probe", Map.of("forbric.transferShapeDump", dump.toString()));
	}

	@Test void theAuditedStoreLosesTheMarkerAndTheDeclineNamesItsDefinedBytes() throws Exception {
		String observed = declinedFingerprint(run);
		assertEquals(fingerprint(run.defined(STORAGE)), observed, "the decline must name the bytes that were defined");
		assertFalse(hasMarker(run, STORAGE), run.describe());
		// Woven all the same: the audit refuses the certificate, never the mod's change.
		assertTrue(WeaveHarness.hasMergedMethod(run.defined(STORAGE)), run.describe());

		List<String> audit = Files.readAllLines(dump.resolve(STORAGE + ".class.audit.txt"));
		assertEquals("observed=" + observed, audit.get(1), audit.toString());
		assertTrue(audit.get(0).matches("reviewed=[0-9a-f]{64}") && !audit.get(0).endsWith(observed), audit.toString());
		assertEquals(List.of(), unsettled(run), run.findings().toString());
		WeaveHarness.assertWovenAndVerified(run, STORAGE, fixture);
	}

	@Test void theUnreviewedTwinIsLeftAsMixinWoveIt() throws Exception {
		assertTrue(hasMarker(run, TWIN), "the audit touched a class it does not review — " + run.describe());
		assertTrue(WeaveHarness.hasMergedMethod(run.defined(TWIN)), run.describe());
		assertFalse(Files.exists(dump.resolve(TWIN + ".class.audit.txt")), "the audit dumped a class it does not review");
		WeaveHarness.assertWovenAndVerified(run, TWIN, fixture);
	}

	/** The two classes differ only in name, so the audit's predicate must hold for one and not the other. */
	@Test void theTwinFlipsEveryAuditAssertion() throws Exception {
		assertTrue(refused(STORAGE) && !refused(TWIN), "the refusal predicate does not separate the classes");
		assertTrue(untouched(TWIN) && !untouched(STORAGE), "the control predicate does not separate the classes");
	}

	private static boolean refused(String internalName) throws Exception {
		return !hasMarker(run, internalName) && Files.exists(dump.resolve(internalName + ".class.audit.txt"));
	}

	private static boolean untouched(String internalName) throws Exception {
		return hasMarker(run, internalName) && !Files.exists(dump.resolve(internalName + ".class.audit.txt"));
	}

	private static String declinedFingerprint(WeaveHarness.Result result) {
		Matcher matcher = REPORT.matcher(result.output());
		assertTrue(matcher.find(), result.describe());
		return matcher.group(1);
	}

	private static boolean hasMarker(WeaveHarness.Result result, String internalName) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(result.defined(internalName)).accept(node, 0);
		return node.methods.stream().anyMatch(m -> m.name.equals(ForgeTransferShapeAudit.MARKER));
	}

	/** The audit's own fingerprint of {@code bytes}; package-private there, so read reflectively. */
	private static String fingerprint(byte[] bytes) throws Exception {
		Method fingerprint = ForgeTransferShapeAudit.class.getDeclaredMethod("fingerprint", byte[].class);
		fingerprint.setAccessible(true);
		return (String) fingerprint.invoke(null, (Object) bytes);
	}

	private static List<WeaveHarness.Finding> unsettled(WeaveHarness.Result result) {
		return result.findings().stream().filter(f -> f.modId().equals(MOD) && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}
}

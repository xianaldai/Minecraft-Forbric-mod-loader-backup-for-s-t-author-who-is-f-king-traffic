/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.Set;
import java.util.TreeSet;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * The registries a mod is handed are public classes, as vanilla's are, so a method looked up on one reflectively can be
 * invoked.
 *
 * <p>On vanilla, Fabric and NeoForge every builtin registry is a {@code MappedRegistry} or {@code DefaultedMappedRegistry},
 * both public. On the merged game 27 of them — block, item, entity_type, … — are MinecraftForge's {@code NamespacedWrapper}
 * or {@code NamespacedDefaultedWrapper}, as are MinecraftForge's own three, and neither carries {@code public}: they are the only
 * classes in the merged base and both carriers that stand in for a vanilla registry, and the only non-public ones. A
 * method found through {@code registry.getClass().getMethod(...)} is then declared by a class outside the caller's package
 * that is not public, and {@code Method.invoke} refuses it however public the method is. Meow Anti-Xray resolves its
 * configured ores exactly so — {@code BuiltInRegistries.BLOCK.getClass().getMethod("getOptional", Identifier.class)} —
 * and threw "cannot access a member of class net.minecraftforge.registries.NamespacedWrapper with modifiers "public"" the
 * moment the server was Done; native Fabric runs it.
 *
 * <p>Both wrappers get {@code ACC_PUBLIC} as they load, and nothing else. Their constructors and package-private members
 * keep their access, so MinecraftForge's own package is the only one that can build or reach into one, as before; what
 * outside code gains is exactly what it has on {@code MappedRegistry} — the wrappers' public methods, by reflection. The
 * kernel reaches them through public interfaces or {@code setAccessible} either way, and nothing reads the modifier.
 * {@code -Dforbric.publicRegistryWrappers=off} leaves them as the carrier ships them.
 *
 * <p>Public is half of it: {@code getMethod} must also be able to list the public methods of each class it searches, and
 * it resolves every type they name. That is why {@code RegistrySyncParityInjector} adds fabric-api's {@code remap} to the
 * wrapper only when fabric-api is installed; {@code RegistryWrapperAccessInjectorTest} runs the lookup on the wrappers as
 * the whole chain of wrapper repairs leaves them, with fabric-api and without.
 */
public final class RegistryWrapperAccessInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.publicRegistryWrappers";
	/** Every carrier class that stands in for {@code MappedRegistry}; {@code RegistryWrapperAccessInjectorTest} re-derives it. */
	static final Set<String> WRAPPERS = Set.of(
			"net.minecraftforge.registries.NamespacedWrapper",
			"net.minecraftforge.registries.NamespacedDefaultedWrapper");

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-public-registry-wrappers"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("the registry wrappers explicitly left package-private with -D" + PROPERTY + "=off");
		String cost = "a mod that invokes a registry method it looked up on registry.getClass() gets IllegalAccessException "
				+ "on block, item and the other MinecraftForge-wrapped registries";
		return AnchorSet.of(new TreeSet<>(WRAPPERS).stream()
				.map(wrapper -> new AnchorSet.Anchor(wrapper, AnchorSet.Severity.REQUIRED, cost))
				.toArray(AnchorSet.Anchor[]::new));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !WRAPPERS.contains(className)) return bytes;
		ClassReader reader = new ClassReader(bytes);
		if ((reader.getAccess() & Opcodes.ACC_PUBLIC) != 0) return bytes;
		// The reader's pool and bodies are copied as they are; only the class header's access changes.
		ClassWriter writer = new ClassWriter(reader, 0);
		reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				super.visit(version, access | Opcodes.ACC_PUBLIC, name, signature, superName, interfaces);
			}
		}, 0);
		ForbricLog.info("[Forbric/Registries] made %s public, as the MappedRegistry it stands in for is — a registry method a "
				+ "mod looks up on registry.getClass() was declared by a class it could not access, and invoking it threw", className);
		return writer.toByteArray();
	}
}

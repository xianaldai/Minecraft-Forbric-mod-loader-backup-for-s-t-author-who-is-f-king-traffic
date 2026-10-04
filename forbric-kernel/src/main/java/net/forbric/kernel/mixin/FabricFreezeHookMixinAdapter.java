/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.FabricFreezePointInjector;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a Fabric mixin's HEAD and TAIL injectors on {@code BuiltInRegistries.freeze()} to the point a Fabric game with
 * fabric-api freezes at: {@link FabricFreezePointInjector#HEAD_HOOK} and {@link FabricFreezePointInjector#TAIL_HOOK},
 * which the kernel calls after the Fabric main and client entrypoints, around the freeze that closes their window.
 *
 * <p>Native {@code fabric-registry-sync-v0} redirects the {@code BuiltInRegistries.bootStrap()} call out of
 * {@code Bootstrap} and makes it after mod initialisation; the kernel keeps the freeze in {@code Bootstrap}
 * ({@link FabricRegistryInitializationMixinAdapter}). Everything a Fabric mod hangs on that freeze therefore ran before
 * any Fabric mod had initialised — Create Fly's TAIL injector initialised {@code CreateRegistries} against a frozen root
 * and the game could not start (issue #52). Only the freeze's own observers move; the freeze stays where it is.
 *
 * <p>Moved only when all of these hold, so nothing else changes:
 * <ul>
 *   <li>fabric-registry-sync is in the game ({@code fabric-registry-sync-v0.mixins.json} registered). Without it,
 *       Fabric freezes in {@code Bootstrap} too, before every main, which is what the kernel already does — and Create
 *       Fly's HEAD injector depends on running there;</li>
 *   <li>the mixin is a Fabric mod's, and its only target is {@code BuiltInRegistries}. A NeoForge mod's injector there
 *       (text_styles) runs where NeoForge freezes, in {@code Bootstrap};</li>
 *   <li>the injector is an {@code @Inject} at {@code HEAD}, {@code TAIL} or {@code RETURN}, naming {@code freeze()} and
 *       nothing else, with a static {@code (CallbackInfo)V} handler and no slice or locals capture — an empty hook has
 *       no inner instruction, local or second return to find — and, at HEAD, not cancellable: on the empty hook a
 *       cancel could no longer skip the freeze;</li>
 *   <li>the hooks exist ({@code -Dforbric.fabricFreezePoint=off} removes them, and this then stands down).</li>
 * </ul>
 * An injector at an instruction inside {@code freeze()}, or on {@code bootStrap()} (yumi, ViaFabricPlus), is left in
 * {@code Bootstrap}: nothing found depends on it running later.
 */
public final class FabricFreezeHookMixinAdapter {
	static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
	static final String REGISTRY_SYNC_CONFIG = "fabric-registry-sync-v0.mixins.json";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String HANDLER_DESC = "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
	private static final Set<String> FREEZE = Set.of("freeze", "freeze()V", "L" + BUILT_IN_REGISTRIES + ";freeze()V");
	private static final Set<String> HEAD = Set.of("HEAD");
	private static final Set<String> TAIL = Set.of("TAIL", "RETURN");

	/** {@code mixin#handler -> hook}, for the boot log and the tests. */
	private static final Set<String> MOVED = ConcurrentHashMap.newKeySet();

	/** Whether fabric-registry-sync is in this game; replaceable by tests that weave without one. */
	static volatile Supplier<Boolean> registrySyncPresent =
			() -> ForbricMixinService.registeredConfigNames().contains(REGISTRY_SYNC_CONFIG);

	private FabricFreezeHookMixinAdapter() {
	}

	/** Every injector moved so far, as {@code mixin#handler -> hook}. */
	public static List<String> moved() {
		return List.copyOf(new java.util.TreeSet<>(MOVED));
	}

	/** Rewrites eligible injectors of {@code mixin} in place; returns how many. Idempotent. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!FabricFreezePointInjector.enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		if (!List.of(BUILT_IN_REGISTRIES).equals(MixinFit.mixinTargets(mixin))) return 0;
		if (MixinStubRebind.ecosystemOf(mixin.name) != Ecosystem.FABRIC) return 0;
		if (!Boolean.TRUE.equals(registrySyncPresent.get())) return 0;

		List<MethodNode> head = new ArrayList<>(), tail = new ArrayList<>();
		for (MethodNode handler : mixin.methods) {
			AnnotationNode inject = injectOnFreeze(handler);
			if (inject == null) continue;
			String at = String.valueOf(MixinFit.value(MixinFit.atNodes(inject).getFirst(), "value"));
			(HEAD.contains(at) ? head : tail).add(handler);
		}
		if (head.isEmpty() && tail.isEmpty()) return 0;
		ClassNode target = targets.apply(BUILT_IN_REGISTRIES);
		if (target == null || !hasHook(target, FabricFreezePointInjector.HEAD_HOOK)
				|| !hasHook(target, FabricFreezePointInjector.TAIL_HOOK)) {
			return 0;
		}

		for (MethodNode handler : head) retarget(mixin, handler, FabricFreezePointInjector.HEAD_HOOK);
		for (MethodNode handler : tail) retarget(mixin, handler, FabricFreezePointInjector.TAIL_HOOK);
		List<String> handlers = new ArrayList<>();
		for (MethodNode m : head) handlers.add(m.name + " (HEAD)");
		for (MethodNode m : tail) handlers.add(m.name + " (TAIL)");
		ForbricLog.info("[Forbric/RegistrySync] %s: injector(s) on BuiltInRegistries.freeze() wait for Fabric's registry "
				+ "freeze point after the Fabric entrypoints, where fabric-registry-sync puts the freeze: %s",
				mixin.name, String.join(", ", handlers));
		return head.size() + tail.size();
	}

	/** The handler's {@code @Inject} when it is one this adapter may move; null otherwise. */
	static AnnotationNode injectOnFreeze(MethodNode handler) {
		AnnotationNode inject = MixinFit.injectorOf(handler);
		if (inject == null || !INJECT.equals(inject.desc)) return null;
		if ((handler.access & Opcodes.ACC_STATIC) == 0 || !HANDLER_DESC.equals(handler.desc)) return null;
		List<String> methods = MixinFit.stringList(MixinFit.value(inject, "method"));
		if (methods.isEmpty() || !FREEZE.containsAll(methods)) return null;
		for (int i = 0; i + 1 < inject.values.size(); i += 2) {
			Object key = inject.values.get(i);
			if ("target".equals(key) || "slice".equals(key)) return null;
			if ("locals".equals(key) && !(inject.values.get(i + 1) instanceof String[] e && e.length == 2
					&& "NO_CAPTURE".equals(e[1]))) {
				return null;
			}
		}
		List<AnnotationNode> ats = MixinFit.atNodes(inject);
		if (ats.size() != 1) return null;
		AnnotationNode at = ats.getFirst();
		Object value = MixinFit.value(at, "value");
		if (!(value instanceof String point) || !(HEAD.contains(point) || TAIL.contains(point))) return null;
		// A HEAD handler may cancel the freeze it runs in; on the empty hook its cancel would stop nothing.
		if (HEAD.contains(point) && Boolean.TRUE.equals(MixinFit.value(inject, "cancellable"))) return null;
		for (int i = 0; i + 1 < at.values.size(); i += 2) {
			Object key = at.values.get(i);
			Object v = at.values.get(i + 1);
			if ("value".equals(key) || "remap".equals(key) || "id".equals(key)) continue;
			if ("ordinal".equals(key) && v instanceof Integer n && (n == -1 || n == 0)) continue;
			return null;
		}
		return inject;
	}

	private static void retarget(ClassNode mixin, MethodNode handler, String hook) {
		AnnotationNode inject = MixinFit.injectorOf(handler);
		for (int i = 0; i + 1 < inject.values.size(); i += 2) {
			if ("method".equals(inject.values.get(i))) {
				inject.values.set(i + 1, new ArrayList<>(List.of(hook + FabricFreezePointInjector.HOOK_DESC)));
			}
		}
		MOVED.add(mixin.name + "#" + handler.name + " -> " + hook);
	}

	private static boolean hasHook(ClassNode target, String hook) {
		return target.methods.stream().anyMatch(m -> m.name.equals(hook)
				&& m.desc.equals(FabricFreezePointInjector.HOOK_DESC) && (m.access & Opcodes.ACC_STATIC) != 0);
	}
}

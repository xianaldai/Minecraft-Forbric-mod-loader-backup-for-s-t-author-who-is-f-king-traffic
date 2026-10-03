/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.tree.*;

/** Keep Create's entity processor setup, iteration and cleanup on the same live placement call. */
public final class CreateStructureMixinAdapter {
	public static final String PROPERTY = "forbric.createStructureMixin";
	static final String MIXIN = "com/zurrtum/create/mixin/StructureTemplateMixin";
	static final String TARGET = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
	static final String OLD = "placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;)V";
	static final String LIVE = "addEntitiesToWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/ProblemReporter;)V";
	private CreateStructureMixinAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!MIXIN.equals(mixin.name) || "off".equalsIgnoreCase(System.getProperty(PROPERTY))) return 0;
		ClassNode target = targets.apply(TARGET);
		MethodNode place = target == null ? null : CarpetMixinAdapter.selector(target, LIVE);
		MethodNode set = CarpetMixinAdapter.named(mixin, "setProcessors"), iterate = CarpetMixinAdapter.named(mixin, "getIterator"), clear = CarpetMixinAdapter.named(mixin, "clearProcessors");
		if (place == null || set == null || iterate == null || clear == null
				|| CarpetMixinAdapter.count(place, "Ljava/util/List;iterator()Ljava/util/Iterator;") != 1) return 0;
		int callers = 0;
		for (MethodNode method : target.methods) if (method.name.equals("placeInWorld")) callers += CarpetMixinAdapter.count(method, "L" + TARGET + ";" + LIVE);
		if (callers != 1) return 0;
		AnnotationNode a = MixinFit.injectorOf(set), b = MixinFit.injectorOf(iterate), c = MixinFit.injectorOf(clear);
		if (a == null || !CarpetMixinAdapter.selects(b, OLD) || !CarpetMixinAdapter.selects(c, OLD)) return 0;
		List<AnnotationNode> points = MixinFit.atNodes(a);
		if (points.size() != 1 || !("L" + TARGET + ";" + OLD).equals(MixinFit.value(points.getFirst(), "target"))) return 0;
		// Only selectors change: Level remains the first argument, so the iterator's args-only local is unchanged.
		CarpetMixinAdapter.set(points.getFirst(), "target", "L" + TARGET + ";" + LIVE);
		CarpetMixinAdapter.set(b, "method", List.of(LIVE));
		CarpetMixinAdapter.set(c, "method", List.of(LIVE));
		return 3;
	}
}

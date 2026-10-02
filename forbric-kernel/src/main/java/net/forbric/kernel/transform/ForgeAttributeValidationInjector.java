/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Sends MinecraftForge's freeze-time attribute validation through {@code KernelForgeAttributes.validate}, which
 * skips it while a client's attribute events are held.
 *
 * <p>{@code GameData$AttributeCallbacks.onValidate} calls {@code DefaultAttributes.validate()} on every freeze. On a
 * client the kernel freezes once before MinecraftForge's mods are constructed, so that validation asked every entity
 * type for attributes nobody had posted yet — and, being the first {@code hasSupplier} calls, it set off Better
 * Nether's lazy entity registration against a frozen registry. The call is swapped one for one;
 * {@code -Dforbric.forgeAttributeValidation=off} leaves it as MinecraftForge has it.
 */
public final class ForgeAttributeValidationInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeAttributeValidation";
	static final String TARGET = "net.minecraftforge.registries.GameData$AttributeCallbacks";
	static final String DEFAULT_ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/DefaultAttributes";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeAttributes";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-forge-attribute-validation";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a client's first freeze validates entity attributes before MinecraftForge's mods post them, and a mod "
						+ "registering entities from a hasSupplier hook (Better Nether) fails against the frozen registry"));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !TARGET.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int sites = repair(node);
		if (sites == 0) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/Attributes] MinecraftForge's freeze-time attribute validation waits while a client's "
				+ "attribute events are held for its MinecraftForge mods (%d site(s))", sites);
		return writer.toByteArray();
	}

	static int repair(ClassNode node) {
		int sites = 0;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("onValidate")) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& call.owner.equals(DEFAULT_ATTRIBUTES) && call.name.equals("validate") && call.desc.equals("()V")) {
					call.owner = RUNTIME;
					sites++;
				}
			}
		}
		return sites;
	}
}

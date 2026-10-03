/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.EventChainAudit;

/**
 * {@link EventChainAuditInjector}'s output, run against the kernel's real boot-side {@code EventChainAudit}: with the
 * audit on, NeoForge's bus and MinecraftForge's two buses report every dispatch, so a kernel bridge forwarding a
 * NeoForge event to MinecraftForge is seen — once, with its cancel carried, or with the cancel lost, or installed twice
 * — where the buses as shipped report nothing and the audit's report is empty whatever the bridges do. The buses
 * dispatch as before, and a throwing listener still throws.
 *
 * <p>The buses are stand-ins in the reviewed shapes: NeoForge's {@code EventBus.post(Event, EventListener[])} looping
 * {@code listeners[i].invoke(event)}, and MinecraftForge's bus records' {@code post} and {@code fire}. EventChainAuditTest
 * runs the same audit over the real buses when the staged carriers are present. The bridges are lambdas of this class,
 * which is kernel code, as the audit requires of a forward.
 */
@ExecutesInjector(EventChainAuditInjector.class)
@ResourceLock("system-properties")
@ResourceLock("EventChainAudit")
class EventChainAuditInjectorExecutionTest {
	private static final String NEO_BUS = EventChainAuditInjector.NEO_BUS;
	private static final String FORGE_CANCELLABLE = EventChainAuditInjector.FORGE_CANCELLABLE;
	private static final String FORGE_PLAIN = EventChainAuditInjector.FORGE_PLAIN;

	private static String forgeBus(String name, boolean cancellable) {
		return """
				package net.minecraftforge.eventbus.internal;

				import java.util.ArrayList;
				import java.util.List;
				import java.util.function.Predicate;

				public final class %s {
					private final List<Predicate<Object>> listeners = new ArrayList<>();

					public void addListener(Predicate<Object> listener) {
						listeners.add(listener);
					}

					/** True when a listener cancelled, on the cancellable bus. */
					public boolean post(Event event) {
						for (Predicate<Object> listener : listeners) if (listener.test(event) && %s) return true;
						return false;
					}

					public Event fire(Event event) {
						post(event);
						return event;
					}
				}
				""".formatted(name, cancellable);
	}

	private static final Map<String, String> STAND_INS = Map.of(
			"net.neoforged.bus.api.Event", "package net.neoforged.bus.api; public abstract class Event { }",
			"net.neoforged.bus.api.ICancellableEvent", """
					package net.neoforged.bus.api;

					public interface ICancellableEvent {
						boolean isCanceled();

						void setCanceled(boolean canceled);
					}
					""",
			"net.neoforged.bus.api.EventListener", """
					package net.neoforged.bus.api;

					public abstract class EventListener {
						public abstract void invoke(Event event);
					}
					""",
			NEO_BUS, """
					package net.neoforged.bus;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.Consumer;
					import net.neoforged.bus.api.Event;
					import net.neoforged.bus.api.EventListener;

					public class EventBus {
						private final List<EventListener> listeners = new ArrayList<>();

						public void addListener(Consumer<Object> listener) {
							listeners.add(new EventListener() {
								public void invoke(Event event) {
									listener.accept(event);
								}
							});
						}

						public Event post(Event event) {
							return post(event, listeners.toArray(new EventListener[0]));
						}

						/** The one loop every NeoForge post runs. */
						public Event post(Event event, EventListener[] listeners) {
							for (int i = 0; i < listeners.length; i++) listeners[i].invoke(event);
							return event;
						}
					}
					""",
			"net.minecraftforge.eventbus.internal.Event", "package net.minecraftforge.eventbus.internal; public interface Event { }",
			FORGE_CANCELLABLE, forgeBus("CancellableEventBusImpl", true),
			FORGE_PLAIN, forgeBus("EventBusImpl", false),
			"fixture.NeoProbe", """
					package fixture;

					import net.neoforged.bus.api.Event;
					import net.neoforged.bus.api.ICancellableEvent;

					public class NeoProbe extends Event implements ICancellableEvent {
						private boolean canceled;

						public boolean isCanceled() {
							return canceled;
						}

						public void setCanceled(boolean canceled) {
							this.canceled = canceled;
						}
					}
					""",
			"fixture.ForgeProbe", "package fixture; public class ForgeProbe implements net.minecraftforge.eventbus.internal.Event { }");

	private Object neoBus, forgeBus, plainBus;
	private Class<?> neoProbe, forgeProbe;

	@BeforeEach void on() throws Throwable {
		System.setProperty(EventChainAudit.PROPERTY, "unused-in-tests.json");
		InjectorExecution.invokeStatic(EventChainAudit.class, "reset");
	}

	@AfterEach void off() throws Throwable {
		System.clearProperty(EventChainAudit.PROPERTY);
		InjectorExecution.invokeStatic(EventChainAudit.class, "reset");
	}

	private void buses(Map<String, byte[]> classes) throws Throwable {
		ClassLoader loader = InjectorExecution.load(classes);
		neoBus = InjectorExecution.construct(loader.loadClass(NEO_BUS));
		forgeBus = InjectorExecution.construct(loader.loadClass(FORGE_CANCELLABLE));
		plainBus = InjectorExecution.construct(loader.loadClass(FORGE_PLAIN));
		neoProbe = loader.loadClass("fixture.NeoProbe");
		forgeProbe = loader.loadClass("fixture.ForgeProbe");
	}

	private static Map<String, byte[]> audited(Map<String, byte[]> original) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String bus : List.of(NEO_BUS, FORGE_CANCELLABLE, FORGE_PLAIN)) {
			String internal = bus.replace('.', '/');
			byte[] out = InjectorExecution.transform(new EventChainAuditInjector(), bus, original.get(internal), EnvType.SERVER);
			assertNotSame(original.get(internal), out, bus + " is the reviewed shape");
			classes.put(internal, out);
		}
		return classes;
	}

	private void forgeListener(boolean cancel) throws Throwable {
		Predicate<Object> listener = event -> cancel;
		InjectorExecution.invoke(forgeBus, "addListener", listener);
	}

	/** A NeoForge listener shaped like the kernel's bridges: post MinecraftForge's event, carry its cancel back or not. */
	private void bridge(boolean carry) throws Throwable {
		Consumer<Object> forward = event -> {
			try {
				boolean cancelled = (boolean) InjectorExecution.invoke(forgeBus, "post", InjectorExecution.construct(forgeProbe));
				if (cancelled && carry) InjectorExecution.invoke(event, "setCanceled", true);
			} catch (RuntimeException | Error e) {
				throw e;
			} catch (Throwable checked) {
				throw new IllegalStateException(checked);
			}
		};
		InjectorExecution.invoke(neoBus, "addListener", forward);
	}

	private Object postNeo() throws Throwable {
		Object event = InjectorExecution.construct(neoProbe);
		Method post = neoBus.getClass().getMethod("post", neoProbe.getSuperclass());
		post.invoke(neoBus, event);
		return event;
	}

	private static long pairCounter(String counter) throws Throwable {
		Object pair = InjectorExecution.invokeStatic(EventChainAudit.class, "pair", "NEO:fixture.NeoProbe", "FORGE:fixture.ForgeProbe");
		assertNotNull(pair, EventChainAudit.json());
		Field field = pair.getClass().getDeclaredField(counter);
		field.setAccessible(true);
		return ((LongAdder) field.get(pair)).sum();
	}

	@Test void aBridgesForwardIsSeenOnceWithItsCancel(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = audited(original);
		buses(classes);
		for (String bus : List.of(NEO_BUS, FORGE_CANCELLABLE, FORGE_PLAIN)) {
			assertEquals("", InjectorExecution.verify(classes.get(bus.replace('.', '/')), neoBus.getClass().getClassLoader()), bus);
		}

		forgeListener(true);
		bridge(true);
		assertEquals(true, InjectorExecution.invoke(postNeo(), "isCanceled"), "the bus dispatches as before");
		assertEquals(1, pairCounter("forwardedOnce"), "the forward is seen, once");
		assertEquals(1, pairCounter("cancelCarried"), "and its cancel reached the NeoForge event");
		assertTrue(EventChainAudit.violations().clean(), EventChainAudit.json());
		assertSame(classes.get(NEO_BUS.replace('.', '/')),
				InjectorExecution.transform(new EventChainAuditInjector(), NEO_BUS, classes.get(NEO_BUS.replace('.', '/')), EnvType.SERVER),
				"a wrapped bus is not wrapped twice");
	}

	@Test void aLostCancelAndADoubleDeliveryAreViolations(@TempDir Path work) throws Throwable {
		buses(audited(InjectorExecution.compile(work, STAND_INS)));
		forgeListener(true);
		bridge(false);
		bridge(false);
		assertEquals(false, InjectorExecution.invoke(postNeo(), "isCanceled"));
		assertEquals(1, EventChainAudit.violations().cancelLost(), EventChainAudit.json());
		assertEquals(1, EventChainAudit.violations().duplicated(), "two listeners forwarded one event: told apart by the loop index");
	}

	@Test void fireAndAThrowingForwardAreReportedAndTheThrowStillPropagates(@TempDir Path work) throws Throwable {
		buses(audited(InjectorExecution.compile(work, STAND_INS)));
		Object forge = InjectorExecution.construct(forgeProbe);
		assertSame(forge, InjectorExecution.invoke(plainBus, "fire", forge), "fire returns its event, as its own body did");
		InjectorExecution.invoke(forgeBus, "addListener", (Predicate<Object>) event -> { throw new IllegalStateException("listener failed"); });
		bridge(true);
		assertThrows(java.lang.reflect.InvocationTargetException.class, this::postNeo);
		assertEquals(1, pairCounter("innerFailures"));
		assertEquals(0, EventChainAudit.violations().unbalanced(), "every frame entered was left");
	}

	@Test void asShippedTheBusesReportNothing(@TempDir Path work) throws Throwable {
		buses(InjectorExecution.compile(work, STAND_INS));
		forgeListener(true);
		bridge(false);
		bridge(false);
		postNeo();
		assertEquals(0, InjectorExecution.invokeStatic(EventChainAudit.class, "pairCount"),
				"premise: unwrapped, a lost cancel and a double delivery go unseen");
		assertTrue(EventChainAudit.violations().clean());
	}

	@Test void withTheAuditOffTheBusesAreLeftAsShipped(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		System.clearProperty(EventChainAudit.PROPERTY);
		for (String bus : List.of(NEO_BUS, FORGE_CANCELLABLE, FORGE_PLAIN)) {
			byte[] bytes = original.get(bus.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new EventChainAuditInjector(), bus, bytes, EnvType.SERVER), bus);
		}
	}
}

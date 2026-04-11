package primegap.util;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.LongStream;

import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorOperators.Comparison;

public class Java {
	static Comparator<Class<?>> comparator = Comparator.comparing(Class::getSimpleName);

	public static <E> Class<? extends E>[] findSubclasses(Class<E> parent) {
		String cp = System.getProperty("java.class.path");
		List<Class<? extends E>> result = new ArrayList<>();
		try {
			for (String entry : cp.split(File.pathSeparator)) {
				Path path = Path.of(entry);
				if (Files.isDirectory(path)) {
					try (var stream = Files.walk(path)) {
						stream.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
							String className = path.relativize(p).toString().replace(File.separatorChar, '.')
									.replace(".class", "");
							tryLoad(className, parent, result);
						});
					}
				} else if (entry.endsWith(".jar")) {
					try (JarFile jar = new JarFile(path.toFile())) {
						jar.entries().asIterator().forEachRemaining(entry2 -> {
							String name = entry2.getName();
							if (name.endsWith(".class")) {
								tryLoad(name.replace('/', '.').replace(".class", ""), parent, result);
							}
						});
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		result.sort(comparator);

		@SuppressWarnings({ "unchecked", "rawtypes" })
		Class<E>[] classes = result.stream().map(c -> ((Class) c).asSubclass(parent)).toArray(Class[]::new);

		return classes;
	}

	static <E> void tryLoad(String className, Class<E> parent, List<Class<? extends E>> result) {
		if (!className.startsWith("attic.")) {
			try {
				Class<?> cls = Class.forName(className);
				if (parent.isAssignableFrom(cls) && !Modifier.isAbstract(cls.getModifiers())) {
					result.add(cls.asSubclass(parent));
				}
			} catch (Throwable ignored) {
				// ignore any error loading the class (NoClassDefFoundError, etc.)
				// as we only want to find classes that can be loaded successfully
				// ignored.printStackTrace();
			}
		}
	}

	public static class SIMD {
		/**
		 * Relaunches the current JVM process with --add-modules=jdk.incubator.vector
		 * appended, forwarding all existing JVM arguments and the main class args.
		 * Inherits stdin/stdout/stderr so output appears normally.
		 */
		public static boolean enable() {
			boolean enabled = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
			// Check if the Vector API incubator module is already loaded
			if (!enabled) {
				// Resolve the current java executable path
				String javaExe = ProcessHandle.current().info().command().orElse("java");

				RuntimeMXBean jvmMeta = ManagementFactory.getRuntimeMXBean();

				Collection<String> cmd = new LinkedHashSet<>();
				cmd.add(javaExe);

				// Inject the missing module flag first
				cmd.add("--add-modules=jdk.incubator.vector");

				// Forward all existing JVM flags (-Xmx, -Xms, -D... etc.)
				cmd.addAll(jvmMeta.getInputArguments());

				// Forward classpath
				cmd.add("-cp");
				cmd.add(jvmMeta.getClassPath());

				// Main class and its arguments
				String[] parts = Optional.ofNullable(System.getProperty("sun.java.command"))
						.orElseGet(() -> Arrays.asList(new Exception().getStackTrace()).getLast().getClassName())
						.split(" ");
				cmd.addAll(Arrays.asList(parts));

				// Launch child process, sharing all I/O with current process
				int exitCode = 1;
				try {
					exitCode = new ProcessBuilder(new ArrayList<>(cmd)).inheritIO().start().waitFor();
					// Thread.sleep(100); // small delay to ensure child process has time to print
					// output before parent exits
				} catch (InterruptedException | IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				// Mirror the child exit code
				System.exit(exitCode);
			}
			return enabled;
		}

		public static final Comparison UNSIGNED_GE;

		static {
			Comparison op = null;
			enable();
			try {
				// JDK 25+
				op = (VectorOperators.Comparison) VectorOperators.class.getField("UGE").get(null);
			} catch (NoSuchFieldException e) {
				try {
					// JDK 17-24 : nom long
					op = (VectorOperators.Comparison) VectorOperators.class.getField("UNSIGNED_GE").get(null);
				} catch (Exception ex) {
					throw new Error(ex);
				}
			} catch (Exception e) {
				throw new Error(e);
			}
			UNSIGNED_GE = op;
		}
	}

	public static void atexit(Runnable action) {
		Thread hook = new Thread(action);
		Runtime.getRuntime().addShutdownHook(hook);
	}

	public static LongStream shuffledRange(long from, long to) {
		long range = Math.subtractExact(to, from); // overflow safe
		if (range <= 0 || range >= 1L << 30)
			throw new IllegalArgumentException("range: 1..2^30-1");

		long mask = (range << 2) | 3;
		mask |= mask >> 4;
		mask |= mask >> 8;
		mask |= mask >> 16;

		long[] perm = new long[(int) range];
		long n = 1;

		for (int idx = 0; idx < range;) {
			n = (n * 5L) & mask;
			long x = (n - 1L) >>> 2;
			if (x < range) {
				perm[idx++] = from + x;
			}
		}
		return LongStream.of(perm);
	}

	public static LongStream rangeWithStep(long start, long endExclusive, long step) {
		long count = (endExclusive - start + step - 1) / step;
		return LongStream.range(0, count).map(i -> start + i * step);
		// return shuffledRange(0, count).map(i -> start + i * step);
	}

	@SuppressWarnings("unchecked")
	public static <T> SequencedMap<Class<? extends T>, String> gettHierarchy(Class<T> root) {
		Class<? extends T>[] classes = findSubclasses(root);

		Map<Class<? extends T>, Set<Class<? extends T>>> children = new TreeMap<>(comparator);

		for (Class<? extends T> clazz : classes) {
			if (clazz == root)
				continue;
			for (Class<? extends T> parent = (Class<? extends T>) clazz.getSuperclass(); //
					parent != null; clazz = parent, //
					parent = (Class<? extends T>) parent.getSuperclass()) {
				Set<Class<? extends T>> l = children.computeIfAbsent(parent, k -> new TreeSet<>(comparator));
				l.add(clazz);
				if (parent == root) {
					break;
				}
			}
		}
		return new Object() {
			SequencedMap<Class<? extends T>, String> res = new LinkedHashMap<>();

			SequencedMap<Class<? extends T>, String> print(Class<? extends T> node,
					Map<Class<? extends T>, Set<Class<? extends T>>> children, String prefix1, String prefix2) {
				res.put(node, String.format(Modifier.isAbstract(node.getModifiers()) ? "%s<%s>" : "%s%s", prefix1,
						node.getSimpleName()));
				Set<Class<? extends T>> kids = children.getOrDefault(node, Collections.emptySet());
				int idx = kids.size();
				for (Class<? extends T> kid : kids) {
					boolean last = --idx == 0;
					print(kid, children, prefix2 + (last ? "+-- " : "|-- "), prefix2 + (last ? "    " : "|   "));
				}
				return res;
			}
		}.print(root, children, "", "");
	}

}

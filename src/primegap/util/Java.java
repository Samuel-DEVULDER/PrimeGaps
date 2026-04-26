package primegap.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorOperators.Comparison;

/**
 * Various java utilities, such as finding subclasses of a class, enabling
 * Vector API, providing atexit() hooks as in C, and generating In/Long range
 * stream with steps.
 */
public class Java {
	/**
	 * Finds all non-abstract subclasses of the given parent class that are
	 * available in the current classpath. This method scans both directories and
	 * JAR files in the classpath, loading classes and checking their type
	 * hierarchy.
	 *
	 * @param <E>    the type of the parent class
	 * @param parent the parent class to find subclasses of
	 * @return an array of classes that are non-abstract subclasses of the given
	 *         parent class
	 */
	public static <E> Class<? extends E>[] findSubclasses(Class<E> parent) {
		class Internal {
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
		}

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
							Internal.tryLoad(className, parent, result);
						});
					}
				} else if (entry.endsWith(".jar")) {
					try (JarFile jar = new JarFile(path.toFile())) {
						jar.entries().asIterator().forEachRemaining(entry2 -> {
							String name = entry2.getName();
							if (name.endsWith(".class")) {
								Internal.tryLoad(name.replace('/', '.').replace(".class", ""), parent, result);
							}
						});
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		Class<E>[] classes = result.stream().map(c -> ((Class) c).asSubclass(parent)).toArray(Class[]::new);

		return classes;
	}

	/**
	 * Builds a map representing the class hierarchy of subclasses of the given root
	 * class. The map keys are the classes, and the values are formatted strings
	 * that visually represent the hierarchy using indentation and symbols. The
	 * method first finds all subclasses of the root class, then constructs a tree
	 * structure based on their superclass relationships, and finally generates a
	 * formatted map that can be used to display the hierarchy in a readable format.
	 *
	 * @param <T>  the type of the root class
	 * @param root the root class to build the hierarchy from
	 * @return a SequencedMap where keys are classes and values are formatted
	 *         strings representing the hierarchy
	 */
	@SuppressWarnings("unchecked")
	public static <T> SequencedMap<Class<? extends T>, String> gettHierarchy(Class<T> root) {
		Class<? extends T>[] classes = findSubclasses(root);

		Map<Class<? extends T>, Set<Class<? extends T>>> children = new LinkedHashMap<>();

		for (Class<? extends T> clazz : classes) {
			if (clazz == root)
				continue;
			for (Class<? extends T> parent = (Class<? extends T>) clazz.getSuperclass(); //
					parent != null; clazz = parent, //
					parent = (Class<? extends T>) parent.getSuperclass()) {
				Set<Class<? extends T>> l = children.computeIfAbsent(parent, k -> new LinkedHashSet<>());
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
						getSimpleName(node)));
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

	public static String getSimpleName(Class<?> cls) {
		return cls.getName().replaceFirst(".*[\\.]", "").replace('$', '.');
	}

	/**
	 * The SIMD class provides a method to enable the Vector API incubator module
	 * and defines a constant for the unsigned greater-than-or-equal comparison
	 * operator. The enable() method checks if the module is already loaded, and if
	 * not, it relaunches the current JVM process with the necessary module added to
	 * the command line arguments. This allows the program to use SIMD operations
	 * without requiring the user to manually specify the module when launching the
	 * application.
	 */
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
				cmd.add("--enable-native-access=ALL-UNNAMED");

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

	/**
	 * Registers a shutdown hook to run the given action when the JVM is exiting.
	 * This can be used to perform cleanup tasks, such as releasing resources or
	 * saving state, before the application terminates. The action will be executed
	 * when the JVM is shutting down, either normally or due to an external signal
	 * (e.g., Ctrl+C).
	 *
	 * @param action the Runnable action to execute on JVM shutdown
	 */
	public static void atexit(Runnable action) {
		if (hook == null) {
			hook = new Thread() {
				@Override
				public void run() {
					for (Runnable r : new ArrayList<>(hooks).reversed()) {
						try {
							r.run();
						} catch (Throwable e) {
							boolean ok = false;
							assert ok = true;
							if (ok) {
								e.printStackTrace();
							}
						}
					}
				}
			};
			Runtime.getRuntime().addShutdownHook(hook);
		}
		if (hooks.remove(action)) {
			if (hooks.isEmpty()) {
				Runtime.getRuntime().removeShutdownHook(hook);
				hook = null;
			}
		} else {
			hooks.add(action);
		}
	}

	static Thread hook;
	static List<Runnable> hooks = new ArrayList<>();

	/**
	 * Generates a LongStream of long values in the range [from, to) in a shuffled
	 * order. The method uses a permutation algorithm based on modular arithmetic to
	 * create a pseudo-random sequence of numbers within the specified range. The
	 * generated stream will contain all numbers from the range exactly once, but in
	 * a random order.
	 *
	 * @param from the starting value of the range (inclusive)
	 * @param to   the ending value of the range (exclusive)
	 * @return a LongStream containing the shuffled values in the specified range
	 * @throws IllegalArgumentException if the range is invalid (non-positive or too
	 *                                  large)
	 */
	public static LongStream shuffledRange(long from, long to) {
		long range = Math.subtractExact(to, from); // overflow safe
		if (range == 0)
			return LongStream.empty();

		if (range < 0 || range >= 1L << 30)
			throw new IllegalArgumentException("range (" + range + "): 1..2^30-1");

		long mask = range << 2;
		mask |= mask >>> 1;
		mask |= mask >>> 2;
		mask |= mask >>> 4;
		mask |= mask >>> 8;
		mask |= mask >>> 16;

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

	/** same as {@link suffledRange(long,long)} but for int range */
	public static IntStream shuffledRange(int from, int to) {
		int range = Math.subtractExact(to, from); // overflow safe
		if (range == 0)
			return IntStream.empty();
		if (range < 0 || range >= 1L << 30)
			throw new IllegalArgumentException("range (" + range + "): 1..2^30-1");

		int mask = range << 2;
		mask |= mask >>> 1;
		mask |= mask >>> 2;
		mask |= mask >>> 4;
		mask |= mask >>> 8;
		mask |= mask >>> 16;

		int[] perm = new int[range];
		int n = 1;

		for (int idx = 0; idx < range;) {
			n = (n * 5) & mask;
			int x = (n - 1) >>> 2;
			if (x < range) {
				perm[idx++] = from + x;
			}
		}
		return IntStream.of(perm);
	}

	/**
	 * Generates a LongStream of long values in the range [start, endExclusive) with
	 * a specified step, in a shuffled order. The method calculates the number of
	 * elements in the range based on the step and then generates a shuffled
	 * sequence of indices, which are then mapped to the actual values in the range
	 * using the formula start + i * step. This allows for generating a stream of
	 * numbers that are evenly spaced by the given step, but in a random order.
	 *
	 * @param start        the starting value of the range (inclusive)
	 * @param endExclusive the ending value of the range (exclusive)
	 * @param step         the step size between consecutive values in the range
	 * @return a LongStream containing the shuffled values in the specified range
	 *         with the given step
	 * @throws IllegalArgumentException if the range is invalid (non-positive or too
	 *                                  large)
	 */
	public static LongStream rangeWithStep(long start, long endExclusive, long step) {
		long count = (endExclusive - start + step - 1) / step;
		return LongStream.range(0, count).map(i -> start + i * step);
		// return shuffledRange(0, count).map(i -> start + i * step);
	}

	public static final boolean dbg = System.getProperty("dbg") != null;

	public static final Object CR = new Object();

	public static boolean dbgTic() {
		timeStack.add(Machine.getCpuTimeNano());
		return timeStack.size() > Math.random();
	}

	public static String dbgToc() {
		return String.format("%.3f", (Machine.getCpuTimeNano() - timeStack.remove(timeStack.size() - 1)) / 1e6);
	}

	static List<Long> timeStack = new ArrayList<>();
	public static boolean isTTY = System.console() != null;

	public static boolean dbg(Object... args) {
		try {
			int len = 0;
			boolean cr = true;
			for (Object o : args) {
				cr = false;
				if (o == CR) {
					String s = isTTY ? "\b".repeat(len) : "\n";
					len = -s.length();
					cr = true;
					o = s;
				}
				if (o instanceof Throwable thr) {
					var bos = new ByteArrayOutputStream();
					thr.printStackTrace(new PrintStream(bos));
					o = bos.toString();
				}
				String s = String.valueOf(o);
				System.err.print(s);
				len += s.length();
			}
			if (!cr) {
				System.err.println();
			}
		} catch (Throwable ignored) {
		}
		return true; // useful for assert
	}

	public static String toString(Number N) {
		return toString(N, " "); // Locale.getDefault() == Locale.FRANCE ? " " : "_");
	}

	public static String toString(Number N, String thousandsSep) {
		String str = N.toString();
		int i = str.indexOf('.');
		if (i < 0)
			i = str.length();
		return str.substring(0, i).replaceAll("\\B(?=(\\d{3})+(?!\\d))", thousandsSep) + str.substring(i);
	}

	/** prints time (in second) as weeks, days, hours, minutes and seconds. */
	public static String toWDHMS(long secs) {
		long s = secs;
		long t = s;
		s = t % 60;
		t /= 60;
		String r = s + "s";
		if (t > 0) {
			s = t % 60;
			t /= 60;
			r = s + "m " + r;
			if (t > 0) {
				s = t % 24;
				t /= 24;
				r = s + "h " + r;
				if (t > 0) {
					s = t % 7;
					t /= 7;
					r = s + "d " + r;
					if (t > 0) {
						r = t + "w " + r;
					}
				}
			}
		}
		return r;
	}
}

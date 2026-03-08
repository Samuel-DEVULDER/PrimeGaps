package primegap;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.jar.JarFile;

import primegap.naive.NaiveGap;

public class Benchmark {
	final Duration RUNTIME = Duration.ofSeconds(300);
	final Duration PAUSE = Duration.ofSeconds(10);

	record Algo(String name, double speed) implements Comparable<Algo> {
		@Override
		public int compareTo(Algo o) {
			return Double.compare(speed, o.speed);
		}
	}

	<T> T mute(NaiveGap impl, Supplier<T> sup) {
		PrintStream out = System.out, err = System.err;
		try {
			PrintStream ps = new PrintStream(new OutputStream() {
				@Override
				public void write(int b) throws IOException {
					if (impl != null && impl.isStopping())
						throw new RuntimeException();
				}
			});
			System.setOut(ps);
			System.setErr(ps);
			return sup.get();
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
	}

	@SafeVarargs
	final void run(Class<? extends NaiveGap>... classes) throws Exception {
		Collection<Algo> col = new TreeSet<>();
		int i = 0;
		for (var cls : classes) {
			final var cst = cls.getConstructor();
			NaiveGap impl = mute(null, () -> {
				try {
					return cst.newInstance();
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			});
			System.out.printf("%d/%d Testing %s...", ++i, classes.length, impl.name());
			AtomicLong time = new AtomicLong();
			CountDownLatch started = new CountDownLatch(1);
			Thread bench = new Thread() {
				public void run() {
					started.countDown();
					long start = System.nanoTime();
					time.set(start);
					impl.run();
					time.set(start - System.nanoTime());
				};
			};

			mute(impl, () -> {
				try {
					bench.start();
					started.await();
					Thread.sleep(RUNTIME);
					impl.stop();
					bench.join(Duration.ofSeconds(60)); // wait 60 sec max
				} catch (InterruptedException e) {
				}
				return null;
			});
			double duration = time.get(); // nSec
			if (duration < 0) {
				duration = -duration;
			} else {
				duration = System.nanoTime() - duration;
			}
			duration /= 1e9; // sec
			long numPrimes = 0;
			for (long c : impl.gapCounts)
				numPrimes += c;
			System.out.printf(Locale.ENGLISH, "%,d primes in %.1f secs%n", numPrimes, duration);
			col.add(new Algo(impl.name(), numPrimes / duration));
			System.gc();
			Thread.sleep(PAUSE);
		}
		
		printMachineInfo();
		for (Algo alg : col) {
			System.out.printf(Locale.ENGLISH, "%-60s %,.1f p/s%n", alg.name, alg.speed);
		}
		// avoid shutdown printing stuff
		PrintStream ps = new PrintStream(new OutputStream() {
			@Override
			public void write(int b) throws IOException {
			}
		});
		System.setErr(ps);
		System.setOut(ps);
	}

	public static <E> List<Class<? extends E>> findSubclasses(Class<E> parent) throws Exception {
		String cp = System.getProperty("java.class.path");
		List<Class<? extends E>> result = new ArrayList<>();

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
		return result;
	}

	static <E> void tryLoad(String className, Class<E> parent, List<Class<? extends E>> result) {
		if (!className.startsWith("attic.")) {
			try {
				Class<?> cls = Class.forName(className);
				if (parent.isAssignableFrom(cls) && !Modifier.isAbstract(cls.getModifiers())) {
					result.add(cls.asSubclass(parent));
				}
			} catch (Throwable ignored) {
			}
		}
	}

	public static void main(String[] args) {
		try {
			var impls = findSubclasses(NaiveGap.class);
			// System.err.println("Found " + impls.size() + " implementations of " +
			// NaiveGap.class);
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Class<? extends NaiveGap>[] classes = impls.stream().map(c -> ((Class) c).asSubclass(NaiveGap.class))
					.toArray(Class[]::new);
			new Benchmark().run(classes);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void printMachineInfo() {
	    Runtime rt = Runtime.getRuntime();
	    System.out.println("=== Machine Info ===");
	    System.out.println("OS      : " + System.getProperty("os.name") 
	                     + " " + System.getProperty("os.version")
	                     + " (" + System.getProperty("os.arch") + ")");
	    System.out.println("JVM     : " + System.getProperty("java.vm.name")
	                     + " " + System.getProperty("java.version"));
	    System.out.println("CPUs    : " + rt.availableProcessors());
	    System.out.printf ("RAM     : %.1f GB total%n", 
	                     rt.maxMemory() / 1e9);
	    printCpuModel();
	    System.out.println("====================");
	}

	public static void printCpuModel() {
		String os = System.getProperty("os.name").toLowerCase();
		String[] cmd;
		if (os.contains("linux"))
			cmd = new String[] { "sh", "-c", "lscpu | grep -E 'Model name|L1|L2|L3'" };
		else if (os.contains("mac"))
			cmd = new String[] { "sh", "-c", "sysctl -n machdep.cpu.brand_string" };
		else // Windows
			cmd = new String[] { "cmd", "/c", "wmic cpu get name,L2CacheSize,L3CacheSize" };

		try {
			Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			new String(proc.getInputStream().readAllBytes()).lines().filter(l -> !l.isBlank())
					.forEach(System.out::println);
		} catch (IOException ignore) {
		}
	}
}

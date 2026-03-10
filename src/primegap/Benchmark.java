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
	final Duration RUNTIME = Duration.ofSeconds(90);
	final Duration PAUSE = Duration.ofSeconds(10);

	record Algo(String name, double speed) implements Comparable<Algo> {
		@Override
		public int compareTo(Algo o) {
			return Double.compare(speed, o.speed);
		}
	}

	static <T> T mute(NaiveGap impl, Supplier<T> sup) {
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

	public static <E> List<Class<? extends E>> findSubclasses(Class<E> parent) {
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

	public static void printMachineInfo() {
		Runtime rt = Runtime.getRuntime();
		System.out.println("=== Machine Info ===");
		System.out.println("OS      : " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
				+ System.getProperty("os.arch") + ")");
		System.out
				.println("JVM     : " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
		System.out.println("CPUs    : " + rt.availableProcessors());
		System.out.printf("RAM     : %.1f GB total%n", rt.maxMemory() / 1e9);
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

	static int probeCache() {
	    System.out.println("=== Cache probing (pointer chasing) ===");

	    // Warm-up JIT
	    int[] w = buildChain(256 * 1024 / 4);
	    long wEnd = System.nanoTime() + 3_000_000_000L;
	    int idx = 0;
	    while (System.nanoTime() < wEnd) idx = w[idx];
	    if (idx < 0) System.out.print("");

	    // Tailles en quarts d'octave (x 2^(1/4))
	    double STEP = Math.pow(2, 0.25);
	    List<Integer> sizeList = new ArrayList<>();
	    for (double sz = 8 * 1024; sz <= 64 * 1024 * 1024 + 1; sz *= STEP) {
	        int s = (int)(sz / 16) * 16;
	        if (sizeList.isEmpty() || s != sizeList.get(sizeList.size() - 1))
	            sizeList.add(s);
	    }
	    int steps          = sizeList.size();
	    int[] sizes        = sizeList.stream().mapToInt(Integer::intValue).toArray();
	    double[] latencies = new double[steps];

	    int REPEATS = 7;
	    int HOPS    = 1 << 23;

	    for (int s = 0; s < steps; s++) {
	        int len     = sizes[s] / 4;
	        int[] chain = buildChain(len);

	        double[] samples = new double[REPEATS];
	        for (int r = 0; r < REPEATS; r++) {
	            int cur = 0;
	            long t0 = System.nanoTime();
	            for (int i = 0; i < HOPS; i++) cur = chain[cur];
	            long dt = System.nanoTime() - t0;
	            if (cur < 0) System.out.print("");
	            samples[r] = (double) dt / HOPS;
	        }
	        java.util.Arrays.sort(samples);
	        latencies[s] = samples[REPEATS / 2];
	        System.out.printf("size=%7d KB  latency=%6.1f ns%n", sizes[s] / 1024, latencies[s]);
	    }

	    // Stabilisation JIT : 3 points consecutifs dans +/-30% de leur moyenne
	    int startDetect = steps / 4;
	    for (int s = 2; s < steps - 4; s++) {
	        double a = latencies[s], b = latencies[s+1], c = latencies[s+2];
	        double avg = (a + b + c) / 3.0;
	        if (avg > 0
	                && Math.abs(a - avg) / avg < 0.30
	                && Math.abs(b - avg) / avg < 0.30
	                && Math.abs(c - avg) / avg < 0.30) {
	            startDetect = s;
	            break;
	        }
	    }
	    System.out.printf("  (stabilisation JIT a partir de %d KB)%n", sizes[startDetect] / 1024);

	    // Detection des seuils par fenetre glissante 3+3
	    List<int[]> thresholds = new ArrayList<>();
	    for (int s = startDetect + 3; s < steps - 3; s++) {
	        double before = (latencies[s-3] + latencies[s-2] + latencies[s-1]) / 3.0;
	        double after  = (latencies[s]   + latencies[s+1] + latencies[s+2]) / 3.0;
	        boolean bigJump   = after / before > 1.6;
	        boolean farEnough = thresholds.isEmpty() ||
	            sizes[s] > thresholds.get(thresholds.size()-1)[0] * 4;
	        if (bigJump && farEnough) {
	            thresholds.add(new int[]{sizes[s-1], s-1});
	            System.out.printf("  >>> seuil #%d detecte a ~%d KB  (%.1f -> %.1f ns)%n",
	                thresholds.size(), sizes[s-1] / 1024, before, after);
	        }
	    }

	    // Interpretation
	    String[] cacheNames = {"L2", "L3", "RAM"};
	    System.out.println("\n=== Interpretation ===");
	    for (int i = 0; i < thresholds.size() && i < 3; i++)
	        System.out.printf("  Fin %s : ~%d KB%n", cacheNames[i], thresholds.get(i)[0] / 1024);

	    // Taille optimale : puissance de 2 juste sous le 1er seuil,
	    // prise superieure si le seuil depasse 1.5x cette puissance
	    int optimal;
	    if (!thresholds.isEmpty()) {
	        int l2end = thresholds.get(0)[0];
	        optimal = Integer.highestOneBit(l2end);
	        if (l2end > optimal + optimal / 2) optimal <<= 1;
	    } else {
	        optimal = 256 * 1024;
	        System.out.println("  (aucun seuil detecte, fallback 256 KB)");
	    }

	    System.out.printf("%n=> Taille optimale pour le sieve : %d KB%n%n", optimal / 1024);
	    return optimal;
	}

	static int[] buildChain(int len) {
	    int[] arr = new int[len];
	    for (int i = 0; i < len; i++) arr[i] = i;
	    java.util.Random rng = new java.util.Random(42);
	    for (int i = len - 1; i > 0; i--) {
	        int j = rng.nextInt(i + 1);
	        int tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
	    }
	    int[] chain = new int[len];
	    for (int i = 0; i < len - 1; i++) chain[arr[i]] = arr[i+1];
	    chain[arr[len-1]] = arr[0];
	    return chain;
	}

	public static void main(String[] args) {
		try {
			var impls = mute(null, () -> findSubclasses(NaiveGap.class));
			// System.err.println("Found " + impls.size() + " implementations of " +
			// NaiveGap.class);
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Class<? extends NaiveGap>[] classes = impls.stream().map(c -> ((Class) c).asSubclass(NaiveGap.class))
					.toArray(Class[]::new);
			probeCache();
			new Benchmark().run(classes);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

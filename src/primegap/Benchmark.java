package primegap;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.SequencedMap;
import java.util.TreeSet;
import java.util.function.Supplier;

import primegap.sieve.SieveGap;
import primegap.sieve.wheel.AbstractWheelSieveGap;
import primegap.util.Java;
import primegap.util.Machine;
import primegap.util.NullStream;

/**
 * A benchmark class to compare the performance of different implementations of
 * PrimeGap. It runs each implementation for a specified duration and measures
 * the number of primes found per second.
 */
public class Benchmark {
	final Duration RUNTIME;
	final Duration PAUSE;

	Benchmark() {
		this("90");
	}

	Benchmark(Duration duration) {
		RUNTIME = duration.abs();
		PAUSE = RUNTIME.dividedBy(20);
	}

	Benchmark(String duration) {
		this(parseDuration(duration));
	}

	static Duration parseDuration(String duration) {
		duration = duration.trim();
		if (duration.matches("^[0-9]+$"))
			duration = duration + "S";
		if (!duration.startsWith("PT"))
			duration = "PT" + duration;
		return Duration.parse(duration);
	}

	record Algo(String name, double speed) implements Comparable<Algo> {
		@Override
		public int compareTo(Algo o) {
			return Double.compare(speed, o.speed);
		}
	}

	static class TimeoutException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	static <T> T silentRun(IterativePrimeGap impl, Supplier<T> sup) {
		PrintStream out = System.out, err = System.err;
		try {
			PrintStream ps = NullStream.of(() -> {
				if (impl != null && impl.isStopping())
					throw new TimeoutException();
			});
			System.setOut(ps);
			System.setErr(ps);
			return sup.get();
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
	}

	static void silentRun(IterativePrimeGap impl) {
		silentRun(impl, () -> {
			impl.run();
			return Void.TYPE;
		});
	}

	@SafeVarargs
	final Collection<Algo> run(Class<? extends IterativePrimeGap>... classes) throws Exception {
		var filtered = Arrays.asList(classes).stream().filter(this::accepts).toList();
		Collection<Algo> col = new TreeSet<>();
		int i = 0;
		for (var cls : filtered) {
			final var cst = cls.getConstructor();
			IterativePrimeGap impl = silentRun(null, () -> {
				try {
					return cst.newInstance();
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			});
			impl.doStat = false;
			System.out.printf("%d/%d Testing %s (%s)...", ++i, filtered.size(), Java.getSimpleName(cls), impl.name());

			Thread stopWatch = new Thread() {
				public void run() {
					try {
						Thread.sleep(RUNTIME);
						impl.stop();
					} catch (InterruptedException e) {
					}
				};
			};
			stopWatch.setDaemon(true);
			stopWatch.start();
			long start = System.nanoTime();
			try {
				silentRun(impl);
			} catch (TimeoutException ignored) {
			} catch (Exception ex) {
				ex.printStackTrace();
				break;
			}
			double duration = System.nanoTime() - start;

			duration /= 1e9; // sec
			long numPrimes = impl.getPrimesCount();
			col.add(new Algo(impl.getClass().getName(), numPrimes / duration));

			System.out.printf(Locale.ENGLISH, "%,d primes in %.1f secs%n", numPrimes, duration);
			System.gc();
			Thread.sleep(PAUSE);
		}

		return col;
	}

	static void printHierarchyResult(SequencedMap<Class<? extends AbstractPrimeGap>, String> hierarchy,
			Collection<Algo> col) {
		int longestName = hierarchy.values().stream().mapToInt(String::length).max().orElse(0);
		int longestSpeed = String.format(Locale.ENGLISH, "%,.1f", //
				col.stream().mapToDouble(a -> a.speed).max().orElse(0)).length();
		hierarchy.forEach((k, v) -> {
			var a = col.stream().filter(x -> x.name.equals(k.getName())).findFirst();
			if (a.isEmpty()) {
				System.out.printf(Locale.ENGLISH, "%s%n", v);
			} else {
				var s = String.format(Locale.ENGLISH, "%,.1f", a.get().speed).replace(',', ' ');

				System.out.printf(Locale.ENGLISH, "%s %s%s %s p/s%n", //
						v, //
						".".repeat(3 + longestName - v.length()), //
						" ".repeat(longestSpeed - s.length()), //
						s);
			}
		});
	}

	static void printResult(Collection<Algo> col) {
		Machine.printMachineInfo(System.out);
		for (Algo alg : col) {
			System.out.printf(Locale.ENGLISH, "%-80s %,.1f p/s%n", alg.name, alg.speed);
		}
	}

	protected boolean accepts(Class<?> cls) {
		return SieveGap.class.isAssignableFrom(cls) && !AbstractWheelSieveGap.class.isAssignableFrom(cls);
	}

	public static void main(String[] args) {
		try {
			Machine.preventSleep();
			Class<? extends IterativePrimeGap> root = IterativePrimeGap.class;
			var classes = silentRun(null, () -> Java.findSubclasses(root));
			SequencedMap<Class<? extends AbstractPrimeGap>, String> hierarchy = Java
					.gettHierarchy(AbstractPrimeGap.class);
			hierarchy.forEach((k, v) -> System.err.println(v));
//			new Benchmark().run(SIMDSieveGap.class, SieveGap.class);
			var col = new Benchmark(args.length == 0 ? "90" : args[0]).run(classes);
			System.out.println();
			printHierarchyResult(hierarchy, col);
			System.out.println();
			printResult(col);
			System.out.println("SieveGap.defaultWindowSize=" + SieveGap.defaultWindowSize);
			System.out.println("Date=" + java.time.LocalDate.now());
		} catch (Exception e) {
			e.printStackTrace();
		} catch (AssertionError e) {
			e.printStackTrace();
		} finally {
			System.setErr(NullStream.instance);
			System.setOut(NullStream.instance);
			Machine.allowSleep();
		}
	}
}

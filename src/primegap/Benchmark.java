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
import primegap.sieve.parallel.ParallelSeqSieveGap;
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

	record Result(IterativePrimeGap impl, double seconds) {
		double speed() {
			return impl.getPrimesCount() / seconds;
		}
	}

	Result benchmark(Class<? extends IterativePrimeGap> cls, Duration RUNTIME) {
		IterativePrimeGap impl = silentRun(null, () -> {
			try {
				final var cst = cls.getConstructor();
				return cst.newInstance();
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
		});
		impl.doStat = false;
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
			return null;
		}
		return new Result(impl, (System.nanoTime() - start) / 1e9);
	}

	final Collection<Algo> run(PrintStream out, SequencedMap<Class<? extends AbstractPrimeGap>, String> hierarchy)
			throws Exception {
		int longestName = hierarchy.values().stream().mapToInt(String::length).max().orElse(0);
		Collection<Algo> col = new TreeSet<>();
		int i = 0;
		out.printf("Total = %d WS=%d%n", hierarchy.size(),SieveGap.defaultWindowSize);
		for (var me : hierarchy.entrySet()) {
			Class<? extends AbstractPrimeGap> cls = me.getKey();
			out.printf("%2d %s", ++i, me.getValue());
			if (IterativePrimeGap.class.isAssignableFrom(cls) && accepts(cls)) {
				out.printf(" %s ", ".".repeat(3 + longestName - me.getValue().length()));
				out.flush();
				@SuppressWarnings("unchecked")
				Result res = benchmark((Class<IterativePrimeGap>) cls, RUNTIME);

				System.out.printf("%s : %s primes in %.1f secs%n", res.impl.name(),
						Java.toString(res.impl.getPrimesCount()), res.seconds);

				col.add(new Algo(cls.getName(), res.speed()));

				System.gc();
				Thread.sleep(PAUSE);
			} else {
				out.println();
			}
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
		return SieveGap.class.isAssignableFrom(cls) && !AbstractWheelSieveGap.class.isAssignableFrom(cls)
				&& !ParallelSeqSieveGap.class.isAssignableFrom(cls);
	}

	public static void main(String[] args) {
		try {
			Class<? extends IterativePrimeGap> root = IterativePrimeGap.class;
			silentRun(null, () -> Java.findSubclasses(root));

			Machine.preventSleep();
			SequencedMap<Class<? extends AbstractPrimeGap>, String> hierarchy = Java
					.gettHierarchy(AbstractPrimeGap.class);
			hierarchy.forEach((k, v) -> System.err.println(v));
			System.out.println();

//			new Benchmark().run(SIMDSieveGap.class, SieveGap.class);
			var bench = new Benchmark(args.length == 0 ? "90" : args[0]);
			var col = bench.run(System.out, hierarchy);
			System.out.println();

			printHierarchyResult(hierarchy, col);
			printResult(col);
			System.out.println();
			System.out.println("Duration=" + bench.RUNTIME.toSeconds() + "s");
			System.out.println("Date=" + java.time.LocalDate.now() + " " + java.time.LocalTime.now());
			System.out.println("SieveGap.defaultWindowSize=" + SieveGap.defaultWindowSize);
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

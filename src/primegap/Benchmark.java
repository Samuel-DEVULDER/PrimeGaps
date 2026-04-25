package primegap;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Supplier;

import primegap.util.Java;
import primegap.util.Machine;
import primegap.util.NullStream;

/**
 * A benchmark class to compare the performance of different implementations of
 * PrimeGap. It runs each implementation for a specified duration and measures
 * the number of primes found per second.
 */
public class Benchmark {
	final Duration RUNTIME = Duration.ofSeconds(180);
	final Duration PAUSE = Duration.ofSeconds(10);

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
	final void run(Class<? extends IterativePrimeGap>... classes) throws Exception {
		Collection<Algo> col = new TreeSet<>();
		int i = 0;
		for (var cls : classes) {
			final var cst = cls.getConstructor();
			IterativePrimeGap impl = silentRun(null, () -> {
				try {
					return cst.newInstance();
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}
			});
			System.out.printf("%d/%d Testing %s (%s)...", ++i, classes.length, Java.getSimpleName(cls), impl.name());

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

			Thread.sleep(PAUSE);
			System.out.printf(Locale.ENGLISH, "%,d primes in %.1f secs%n", numPrimes, duration);
			col.add(new Algo(impl.getClass().getName(), numPrimes / duration));
			System.gc();
		}

		printResult(col);
	}

	static void printResult(Collection<Algo> col) {
		Machine.printMachineInfo(System.out);
		for (Algo alg : col) {
			System.out.printf(Locale.ENGLISH, "%-80s %,.1f p/s%n", alg.name, alg.speed);
		}
	}

	public static void main(String[] args) {
		try {
			Machine.preventSleep();
			Class<? extends IterativePrimeGap> root = IterativePrimeGap.class;
			var classes = silentRun(null, () -> Java.findSubclasses(root));
			Java.gettHierarchy(AbstractPrimeGap.class).forEach((k, v) -> System.err.println(v));
//			new Benchmark().run(SIMDSieveGap.class, SieveGap.class);
			new Benchmark().run(classes);
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

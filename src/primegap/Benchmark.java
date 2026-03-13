package primegap;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import primegap.naive.NaiveGap;
import primegap.sieve.simd.SIMDSieveGap;
import primegap.util.Java;
import primegap.util.Machine;
import primegap.util.NullStream;

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
			PrintStream ps = NullStream.of(() -> {
				if (impl != null && impl.isStopping())
					throw new RuntimeException();
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
					bench.join(RUNTIME);
				} catch (InterruptedException e) {
				}
				return Void.TYPE;
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
			
			Thread.sleep(PAUSE);
			System.out.printf(Locale.ENGLISH, "%,d primes in %.1f secs%n", numPrimes, duration);
			col.add(new Algo(impl.name(), numPrimes / duration));
			System.gc();
		}

		printResult(col);

		System.setErr(NullStream.instance);
		System.setOut(NullStream.instance);
	}

	static void printResult(Collection<Algo> col) {
		Machine.printMachineInfo(System.out);
		for (Algo alg : col) {
			System.out.printf(Locale.ENGLISH, "%-60s %,.1f p/s%n", alg.name, alg.speed);
		}
	}

	public static void main(String[] args) {
		try {
			var classes = mute(null, () -> Java.findSubclasses(NaiveGap.class));
			new Benchmark().run(SIMDSieveGap.class);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}

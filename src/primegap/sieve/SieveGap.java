package primegap.sieve;

import java.io.PrintStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import primegap.naive.NaiveGap;
import primegap.util.NullStream;

public class SieveGap extends NaiveGap {
	@Override
	protected void stopping(Info info) {
		super.stopping(info);
		supplier.primes.close();
		supplier = null;
	}

	// 128 -> 5,232,179.3
	// 64 -> 5,861,262.1
	// 48 -> 5,913,321.2
	// 40 -> 5,904,032.0
	// 36 -> 5,386,508.0
	// 32 -> 5,973,348.8
	// 24 -> 5,519,193.2
	// 16 -> 4,522,208.0

	// 1<<19 -> 5,087,780.2
	// 1<<18 -> 6,028,986.7
	// 1<<17 -> 5,560,017.5

	public static int defaultWindowSize = 262144;
	// Machine.probeCache(System.out);
	AbstractSlidingWindowSieve supplier = newSlidingWindowSieve(defaultWindowSize);

	@Override
	protected String name() {
		return name;
	}

	private String name = SieveGap.class.getSimpleName() + "/" + supplier.name();

	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size) {
		return newSlidingWindowSieve(size, false);
	}
	
	protected AbstractSlidingWindowSieve newSlidingWindowSieve(int size, boolean doublBuffer) {
		return new SlidingWindowSieve(this, size, doublBuffer);
	}

	@Override
	protected BigInteger nextPrimeImpl(BigInteger N) {
		BigInteger Q = supplier.getLastPrime();
		while (Q == null || Q.compareTo(N) <= 0) {
			Q = supplier.get();
		}
		return Q;
	}

	public static void main(String[] args) {
		// probeOptimalTabLen(System.out);
		new SieveGap().run();
	}

	/**
	 * Benchmarks the sieve itself across candidate tabLen values and returns the
	 * one that maximises throughput (numbers sieved per second).
	 *
	 * Strategy: for each candidate size, run N full slide cycles starting from a
	 * known position (e.g. near 10^15 to have realistic prime density), measure
	 * total elapsed time, compute numbers_covered / elapsed.
	 *
	 * This naturally captures both T_cache and T_overhead in one measurement.
	 */
	// Remplace : public static int defaultWindowSize = probeCache();
	@SuppressWarnings("resource")
	static int probeOptimalTabLen(PrintStream out) {
		out = (out != null) ? out : System.err;
		out.println("=== Probing optimal tabLen ===");

		// Candidats : 16K .. 4M longs, par octave + intermédiaire x1.5
		List<Integer> candidates = new ArrayList<>();
		for (int s = 16 * 1024; s <= 2 * 1024 * 1024; s *= 2) {
			candidates.add(s);
			candidates.add(s + s / 2);
		}
		candidates.sort(Integer::compareTo);

		final int SLIDES = 10;
		final int REPEATS = 3;

		int bestSize = candidates.get(candidates.size() / 2);
		double bestThroughput = -1;
		String name = "";

		for (int tabLen : candidates) {
			double[] samples = new double[REPEATS];

			PrintStream err = System.err, out2 = System.out;
			System.setErr(NullStream.instance);
			System.setOut(NullStream.instance);
			for (int r = 0; r < REPEATS; r++) {

				// Crée un sieve frais — bootstrap via get() jusqu'à doMarking==false
				SieveGap sieve = new SieveGap();
				name = sieve.name();
				AbstractSlidingWindowSieve sw = sieve.newSlidingWindowSieve(tabLen);
				// Consomme la 1ère fenêtre entièrement : remplit sw.primes
				// jusqu'à sqrt(windowRange), et désactive doMarking
				while (sw.doMarking) {
					sw.get();
				}
				// Vide le reste de la fenêtre courante pour partir proprement
				while (sw.next() >= 0) {
					/* drain */ }

				// === Mesure : SLIDES cycles markAllMultiples + slideWindow ===
				long t0 = System.nanoTime();
				for (int i = 0; i < SLIDES; i++) {
					sw.markAllMultiples();
					sw.fillTab(sw.tab, 0);
					sw.slideWindow();
				}
				long dt = System.nanoTime() - t0;
				sw.primes.close();

				long covered = (long) SLIDES * tabLen * 128L; // nombres couverts
				samples[r] = (double) covered / dt * 1e9; // nombres/s
			}

			System.setErr(err);
			System.setOut(out2);

			Arrays.sort(samples);
			double throughput = samples[REPEATS / 2];
			out.printf("%s  tabLen=%7d longs (%5d KB)  ->  %,.0f numbers/s%n", name, tabLen, tabLen * 8 / 1024,
					throughput);

			if (throughput > bestThroughput) {
				bestThroughput = throughput;
				bestSize = tabLen;
			}
		}

		out.printf("%n=> Optimal tabLen : %d longs (%d KB)%n%n", bestSize, bestSize * 8 / 1024);
		return bestSize;
	}
}

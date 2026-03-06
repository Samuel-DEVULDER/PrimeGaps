package primegap;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

public abstract class AbstractPrimeGap {
	protected boolean running(int gap) {
		return gap <= 464; // 464;
	}	
	
	static protected BigInteger v(long l) {
		return BigInteger.valueOf(l);
	}

	static BigInteger TWO = v(2), ONE = v(1), ZERO = v(0);

	void printf(String fmt, Object... args) {
		System.out.printf(Locale.ENGLISH, fmt, args);
	}

	void dbg(Object... objs) {
//		for (Object o : objs)
//			System.err.print(o);
//		System.err.println();
	}

	/**
	 * Returns true iff this BigInteger passes the specified number of Miller-Rabin
	 * tests. This test is taken from the DSA spec (NIST FIPS 186-2).
	 *
	 * The following assumptions are made: This BigInteger is a positive, odd number
	 * greater than 2. iterations<=50.
	 */
	boolean passesMillerRabin(BigInteger N, int iterations) {
		// Find a and m such that m is odd and this == 1 + 2**a * m
		BigInteger thisMinusOne = N.subtract(ONE);
		BigInteger m_ = thisMinusOne;
		int a = m_.getLowestSetBit();
		BigInteger m = m_.shiftRight(a);

		return IntStream.range(0, iterations).parallel().allMatch(ignored -> {
			Random rnd = ThreadLocalRandom.current();
			// Generate a uniform random on (1, this)
			BigInteger b;
			do {
				b = new BigInteger(N.bitLength(), rnd);
			} while (b.compareTo(ONE) <= 0 || b.compareTo(N) >= 0);

			int j = 0;
			BigInteger z = b.modPow(m, N);
			while (!((j == 0 && z.equals(ONE)) || z.equals(thisMinusOne))) {
				if (j > 0 && z.equals(ONE) || ++j == a)
					return false;
				z = z.modPow(TWO, N);
			}

			return true;
		});
	}

	protected boolean isPrime(BigInteger N) {
		if (N.testBit(0) == false)
			return N.equals(TWO);

		return passesMillerRabin(N, 5) && N.isProbablePrime(1);
	}

	/**
	 * Threa-safe
	 * 
	 * @param N
	 * @return
	 */
	protected final BigInteger nextPrime(BigInteger N) {
		++primeCallCount;
		return nextPrimeImpl(N);
	}

	protected BigInteger nextPrimeImpl(BigInteger N) {
		return N.nextProbablePrime();
	}

	protected BigInteger prevPrime(BigInteger N) {
		if (isPrime(N))
			return N;

		BigInteger P = N;
		// find *a* prime before N
		for (BigInteger K = v(2); P.compareTo(N) >= 0; K = K.shiftLeft(1)) {
			P = nextPrimeImpl(N.subtract(K));
		}
		// find the *last* one before N
		for (BigInteger Q = nextPrimeImpl(P); Q.compareTo(N) < 0; Q = nextPrimeImpl(Q)) {
			P = Q;
		}
		return P;
	}

	abstract BigInteger find(int gap, BigInteger after);

	// --- Prime discovery rate tracking ---
	long primeCallCount = 0;
	protected LongSupplier timer = initTimer();

	private static LongSupplier initTimer() {
		ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
		if (tmx.isThreadCpuTimeSupported()) {
			tmx.setThreadCpuTimeEnabled(true);
			System.err.println("[timer] using thread CPU time");
			return tmx::getCurrentThreadCpuTime;
		}
		System.err.println("[timer] fallback to nanoTime");
		return System::nanoTime;
	}

	record Info(long time, BigInteger lastP, double best_merit, BigInteger best_P) {
	};

	protected void stopping(Info info) {
	}
	
	protected String name() {
		return this.getClass().getSimpleName();
	}

	protected void searchGaps() {
		BigInteger P = v(2), best_P = P;
		long total = 0;
		double prev = 1;
		double best_merit = 0;

		try {
			for (int gap = 2; running(gap); gap += 2) {
				printf("%s: Searching gap >= %s...", name(), gap);

				long time = timer.getAsLong();
				BigInteger P_ = find(gap, P);
				time = timer.getAsLong() - time;
				total += time;
				if (P_ == null)
					break;
				String blank="                                       ";
				printf("found.%s%s\n", blank, "\b".repeat(blank.length()));
				P = P_;

				BigInteger Q = nextPrime(P);
				gap = Q.subtract(P).intValueExact();
				double p = P.doubleValue();
				double merit = gap / Math.log(p);
				String merit_pfx = "";
				if (merit > best_merit) {
					best_merit = merit;
					best_P = P;
					merit_pfx = "+";
				}

				// Compute average prime discovery rate
				String rateStr = String.format(Locale.ENGLISH, "%,.0f", (primeCallCount * 1e9) / total).replace(',',
						' ');

				printf(">> %d\n + %s\n = %s\n", gap, P, Q);
				printf("%.3fs (tot=%.3fs), %d bits, %d digits, " + "x%.2g prev, %s%.2f merit, ~%g, %s p/s.\n",
						time / 1e9, total / 1e9, P.bitLength(), P.toString().length(), p / prev, merit_pfx, merit, p,
						rateStr);
				prev = p;
				P = Q;
			}
		} finally {
			stopping(new Info(total, P, best_merit, best_P));
		}
	}

	protected void run() {
		searchGaps();
	}

	void run_() {
		Thread t = new Thread(this::searchGaps);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
		try {
			t.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}

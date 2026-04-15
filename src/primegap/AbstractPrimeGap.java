package primegap;

import java.math.BigInteger;
import java.util.Locale;

import primegap.util.Machine;

/**
 * Base class for prime gap search implementations. It provides common utilities
 * such as primality testing and next prime generation, as well as a framework
 * for tracking the performance of the search.
 */
public abstract class AbstractPrimeGap {
	protected boolean running(int gap) {
		return gap <= 464 * 2; // 464;
	}

	static protected BigInteger v(long l) {
		return BigInteger.valueOf(l);
	}

	public static String wdhm(long secs) {
		long t = secs / 60;
		long s = t % 60;
		t = t / 60;
		String r = s + "m";
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
		return r;
	}

	static protected BigInteger TWO = v(2), ONE = v(1), ZERO = v(0);

	static protected void printf(String fmt, Object... args) {
		System.out.printf(Locale.ENGLISH, fmt, args);
	}

	public static void dbg(Object... objs) {
//		for (Object o : objs)
//			System.err.print(o);
//		System.err.println();
	}

	public final int MILLER_RABIN_PASSES = 5;

	protected boolean isPrime(BigInteger N) {
		return N.isProbablePrime(MILLER_RABIN_PASSES);
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

	/**
	 * default implementation using BigInteger's nextProbablePrime, which is
	 * thread-safe.
	 * 
	 * @param N a number
	 * @return the next prime greater than N
	 */
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

	protected abstract BigInteger find(int gap, BigInteger after);

	// --- Prime discovery rate tracking ---
	private long primeCallCount = 0;

	public long getPrimeCallCount() {
		return primeCallCount;
	}

	protected record Info(long time, BigInteger lastP, double best_merit, BigInteger best_P) {
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

				long time = Machine.getCpuTimeNano();
				BigInteger P_ = find(gap, P);
				time = Machine.getCpuTimeNano() - time;
				total += time;
				if (P_ == null)
					break;
				String blank = "                                       ";
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
				String rateStr = String.format(Locale.ENGLISH, "%,.0f", (getPrimeCallCount() * 1e9) / total)
						.replace(',', ' ');

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

package primegap;

import java.math.BigInteger;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import primegap.util.Java;
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

	static protected BigInteger TWO = v(2), ONE = v(1), ZERO = v(0);

	static protected void printf(String fmt, Object... args) {
		System.out.printf(Locale.ENGLISH, fmt, args);
	}

	public final int MILLER_RABIN_PASSES = 5;

	public boolean isPrime(BigInteger N) {
		return N.isProbablePrime(MILLER_RABIN_PASSES);
	}

	/**
	 * Threa-safe
	 * 
	 * @return
	 * @param N
	 */
	protected final BigInteger nextPrime(BigInteger N) {
		BigInteger P = nextPrimeImpl(N);
		addPrimeCount(1);
		return P;
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
	private long primesCount = 0;

	synchronized protected void addPrimeCount(int num) {
		primesCount += num;
	}

	synchronized public long getPrimesCount() {
		return primesCount;
	}

	protected record Info(long time, BigInteger lastP, double best_merit, BigInteger best_P) {
	};

	protected void stopping(Info info) {
	}

	protected String name() {
		if (name == null) {
			Class<?> cls = this.getClass();
			while (cls.getEnclosingClass() != null)
				cls = cls.getEnclosingClass();

			name = cls.getSimpleName();
		}
		return name;
	}

	private String name;

	/** callback */
	protected void periodicHook() {
		periodicInfo();
	}

	protected void periodicInfo() {
		long secs = runtimeMillis() / 1000;
		Java.dbg("time=", Java.toString(secs), "s, ", Java.toString(getPrimesCount() / secs), "p/s          ", Java.CR);
	}

	long startTime;

	public long runtimeMillis() {
		return System.currentTimeMillis() - startTime;
	}

	protected void searchGaps() {
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

		BigInteger P = v(2), best_P = P;
		long total = 0;
		double prev = 1;
		double best_merit = 0;
		startTime = runtimeMillis();

		try {
			Machine.preventSleep();

			long period = Java.isTTY ? 3L : 30L;
			scheduler.scheduleAtFixedRate(this::periodicHook, period, period, TimeUnit.SECONDS);
			for (int gap = 2; running(gap); gap += 2) {
				printf("%s: Searching gap >= %s...", name(), gap);

				long time = System.nanoTime();
				BigInteger P_ = find(gap, P);
				time = System.nanoTime() - time;

				total += time;
				if (P_ == null)
					break;

				BigInteger Q = nextPrimeImpl(P_);
				int gap2 = Q.subtract(P_).intValueExact();
				foundGap(gap2, P_, Q);

				assert isValid(P_, gap2)
						: "P=" + P_ + " found-gap=" + gap2 + " searched-gap=" + gap + " gapPrimes=" + prime2Gap;

				double p = (P = P_).doubleValue();
				double merit = gap2 / Math.log(p);
				String merit_pfx = "";
				if (merit > best_merit) {
					best_merit = merit;
					best_P = P;
					merit_pfx = "+";
				}

				// Compute average prime discovery rate
				String rateStr = Java.toString((long) ((getPrimesCount() * 1e9) / total));

				printf("%.3fs (tot=%.3fs), %d bits, %d digits, " + "x%.2g prev, %s%.2f merit, ~%g, %s p/s.\n",
						time / 1e9, total / 1e9, P.bitLength(), P.toString().length(), p / prev, merit_pfx, merit, p,
						rateStr);

				gap = gap2;
				prev = p;
				P = Q;
			}
		} finally {
			scheduler.shutdown();
			Machine.allowSleep();
			stopping(new Info(total, P, best_merit, best_P));
		}
	}

	protected void foundGap(int gap, BigInteger p, BigInteger q) {
		String blank = Java.isTTY ? "                                             " : "";
		printf("found.%s%s%n", blank, "\b".repeat(blank.length()));
		printf(">> %d%n + %s%n = %s%n", gap, Java.toString(p), Java.toString(q));
	}

	protected boolean isValid(BigInteger p, int gap) {
		Integer old = prime2Gap.put(p, gap);
		boolean ok = old == null ? p == prime2Gap.lastKey() : old.equals(gap);
		return ok;
	}

	static TreeMap<BigInteger, Integer> prime2Gap = new TreeMap<>();
	static {
		prime2Gap.put(TWO, 1);
	}

	protected void run() {
		searchGaps();
	}
}

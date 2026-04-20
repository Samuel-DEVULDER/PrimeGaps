package primegap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import primegap.util.Java;
import primegap.util.Machine;

/**
 * The IterativePrimeGap class is an implementation of a prime gap search
 * algorithm that iteratively finds the next prime number after a given prime P
 * and checks the gap between them.
 * <p>
 * The class also includes a mechanism to track and print statistics about the
 * gaps found during the search when the program is stopped.
 * <p>
 * It uses {@link #nextPrimeImpl(BigInteger)} to find the next prime.
 */
public abstract class IterativePrimeGap extends AbstractPrimeGap {
	public IterativePrimeGap() {
		gapCounts = new long[1024];
	}

	@Override
	protected BigInteger find(int gap, BigInteger P) {
		P = fastForward(P, gap);
		BigInteger Q = nextPrime(P);

		int delta = 0;
		while ((delta = Q.subtract(P).intValueExact()) < gap) {
			if (countGap(delta))
				return null;
			P = fastForward(Q, gap);
			Q = nextPrime(P);
		}
		countGap(delta);
		return P;
	}

	protected BigInteger fastForward(BigInteger P, int gap) {
		return P;
	}

	private AtomicInteger stopping = new AtomicInteger(0);
	{
		Java.atexit(() -> {
			if (stopping.get() != 0) {
				stop();
				while (stopping.get() != 3)
					Thread.onSpinWait();

				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				Locale bak = Locale.getDefault();
				try {
					Locale.setDefault(Locale.ENGLISH);
					printGapStats(new PrintStream(baos));
				} finally {
					Locale.setDefault(bak);
				}
				System.out.println(baos.toString());
			}
		});
	}

	public void stop() {
		stopping.compareAndSet(1, 2);
	}

	public boolean isStopping() {
		stopping.compareAndSet(0, 1);
		return stopping.get() == 2;
	}

	@Override
	protected void stopping(Info info) {
		super.stopping(info);
		this.info = info;
		this.stopping.compareAndSet(1, 3);
		this.stopping.compareAndSet(2, 3);
	}

	// -------

	public long[] gapCounts;
	Info info = null;

	void printGapStats(PrintStream out) {
		if (gapCounts == null)
			gapCounts = new long[0];
		long maxCount = 0;
		double total = 0;
		for (long c : gapCounts) {
			maxCount = Math.max(maxCount, c);
			total += c;
		}

		// out.printf("Total gaps counted: %,.0f %d%n", total, primeCallCount);

		int countWidth = Math.max(5, Long.toString(maxCount).length());
		int BAR_WIDTH = 60;

		out.printf("%n%n");

		Machine.printMachineInfo(out);

		String align = "%-20s : ";
		out.printf("%n=== Statistics (%s) ===%n", name());
		out.printf(align + "%,.0f%n", "#Primes", total);
		if (info != null) {
			double secs = info.time() / 1e9;
			out.printf(align + "%,.1f secs (%s)%n", "Time", secs, wdhm((long) secs));
			out.printf(align + "%,.1f primes/sec%n", "Speed", (total * 1e9) / info.time());
			out.printf(align + "%s (~%,.4g)%n", "Biggest prime", info.lastP(), info.lastP().doubleValue());
			out.printf(align + "%s (%.1f)%n", "Best prime (merit)", info.best_P(), info.best_merit());
		}
		out.printf("%n");
		out.printf("%-8s  %" + countWidth + "s  %s%n", "gap", "count", "histogram");
		String line = "-".repeat(8 + 2 + countWidth + 2 + BAR_WIDTH);
		out.printf("%s%n", line);
		String colors = " .:-=+*#%@";
		for (int i = 1; i < gapCounts.length; i++) {
			if (gapCounts[i] == 0)
				continue;
			int gap = i << 1;
			long count = gapCounts[i];
			int bar = (int) (count * BAR_WIDTH / maxCount);
			int col = (int) ((colors.length() * count) / (1 + maxCount));
			String c = colors.substring(col, col + 1);
			out.printf("%-8d  %" + countWidth + "d  %s%n", gap, count, c.repeat(bar));
		}
		out.printf("%s%n", line);
	}

	private final int timeout = 1_000_000;
	private int cnt = timeout;

	private boolean countGap(int gap) {
		var tab = gapCounts;
		if (tab != null) {
			int idx = gap >> 1;
			if (idx >= tab.length)
				gapCounts = tab = Arrays.copyOf(tab, idx * 2);
			++tab[idx];
		}
		if (--cnt == 0) {
			cnt = timeout;
			return isStopping();
		} else {
			return false;
		}
	}

	abstract protected BigInteger nextPrimeImpl(BigInteger after);
}

package primegap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
		gapPrimes = new BigInteger[gapCounts.length];
	}

	protected boolean usesFastForward() {
		return gapCounts == null;
	}

	protected String name() {
		if (name == null) {
			name = super.name() + (usesFastForward() ? "/FastFoward" : "");
		}
		return name;
	}

	private String name;

	@Override
	protected BigInteger find(int gap, BigInteger P) {
		try {
			P = fastForward(P, gap);
			BigInteger Q = nextPrime(P);

			int delta = 0;
			while ((delta = Q.subtract(P).intValueExact()) < gap) {
				if (countGap(P, delta)) {
					return null;
				}
				P = fastForward(Q, gap);
				Q = nextPrime(P);
			}
			countGap(P, delta);
			return P;
		} catch (RuntimeException ex) {
			if (ex.getCause() instanceof IOException)
				return null;
			throw ex;
		}
	}

	protected BigInteger fastForward(BigInteger P, int gap) {
		return P;
	}

	public boolean doStat = true;

	private AtomicInteger stopping = new AtomicInteger(0);
	{
		Java.atexit(() -> {
			if (stopping.get() != 0) {
				stop();
				while (stopping.get() != 3)
					Thread.onSpinWait();

				if (doStat) {
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
	public BigInteger[] gapPrimes;
	Info info = null;

	void printGapStats(PrintStream out) {
		double total = getPrimesCount();

		out.printf("%n%n");
		Machine.printMachineInfo(out);

		String align = "%-20s : ";
		out.printf("%n=== Statistics (%s) ===%n", name());
		out.printf(align + "%,.0f%n", "#Primes", total);
		if (info != null) {
			double secs = info.time() / 1e9;
			out.printf(align + "%,.1f secs (%s)%n", "Time", secs, Java.toWDHMS((long) secs));
			out.printf(align + "%,.1f primes/sec%n", "Speed", (total * 1e9) / info.time());
			out.printf(align + "%s (~%,.4g)%n", "Biggest prime", info.lastP(), info.lastP().doubleValue());
			out.printf(align + "%s (%.1f)%n", "Best prime (merit)", info.best_P(), info.best_merit());
		}
		printHistogram(out);
		printTable(out);
	}

	private void printHistogram(PrintStream out) {
		if (gapCounts == null)
			return;

		long maxCount = 0;
		for (long c : gapCounts) {
			maxCount = Math.max(maxCount, c);
		}

		int countWidth = Math.max(5, Long.toString(maxCount).length());
		int BAR_WIDTH = 60;

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

	private void printTable(PrintStream out) {
		if (gapPrimes == null)
			return;
		// https://pzktupel.de/RecordGaps/GAP01FO.php

		BigInteger last = BigInteger.ONE;
		for (int i = gapPrimes.length; --i >= 0 && (last = gapPrimes[i]) == null; --i) {
		}

		int countWidth = Math.max(5, Java.toString(last).length());

		out.printf("%n");
		out.printf("%-4s  %" + countWidth + "s  %6s %6s%n", "gap", "prime", "digits", "merit");
		String line = "-".repeat(6 + countWidth + 2 + 6 + 1 + 6);
		out.printf("%s%n", line);
		BigInteger prev = TWO;
		int max = gapPrimes.length;
		while (--max >= 0 && gapPrimes[max] == null) {
		}
		for (int i = 1; i <= max; i++) {
			BigInteger p = gapPrimes[i];
			boolean rec = false;
			if (p != null) {
				rec = p.compareTo(prev) < 0;
				prev = p;
			}

			out.printf("%-4d  %" + countWidth + "s%c %6d %6.2f%n", i * 2, //
					p == null ? "" : Java.toString(p), //
					rec ? '*' : ' ', //
					p == null ? 0 : p.toString().length(), //
					p == null ? Double.NaN : i * 2.0 / Math.log(p.doubleValue()));
		}
		out.printf("%s%n", line);
	}

	private boolean chkTimeout = true;
	private BigInteger lastPrime = TWO;
	private int lastGap = 1;

	synchronized private boolean countGap(BigInteger prime, int gap) {
		lastPrime = prime;
		lastGap = gap;

		int idx = gap >> 1;

		var tab = gapCounts;
		if (tab != null) {
			if (idx >= tab.length) {
				gapCounts = tab = Arrays.copyOf(tab, idx * 2);
			}
			++tab[idx];
		}

		var tab2 = gapPrimes;
		if (tab2 != null) {
			if (idx >= tab2.length) {
				gapPrimes = tab2 = Arrays.copyOf(tab2, idx * 2);
			}
			if (tab2[idx] == null)
				tab2[idx] = prime;
		}
		if (chkTimeout) {
			chkTimeout = false;
			System.err.flush();
			return isStopping();
		} else {
			return false;
		}
	}

	@Override
	protected void periodicHook() {
		super.periodicHook();
		chkTimeout = true;
	}

	@Override
	synchronized protected void periodicInfo() {
		long secs = (runtimeMillis() + 500) / 1000;
		Java.dbg("time=", Java.toWDHMS(secs), //
				", prime=", Java.toString(lastPrime), //
				", gap=", lastGap, //
				", ", Java.toString(getPrimesCount() / secs), " p/s.             ", Java.CR);
	}

	abstract protected BigInteger nextPrimeImpl(BigInteger after);
}

package primegap.naive;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;

import primegap.AbstractPrimeGap;
import primegap.util.Java;
import primegap.util.Machine;

public class NaiveGap extends AbstractPrimeGap {
	private volatile Boolean stopping;
	{
		Java.atexit(() -> {
			stop();
			while (stopping != Boolean.FALSE)
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
		});
	}

	public void stop() {
		if (stopping == null)
			stopping = Boolean.TRUE;
	}

	public boolean isStopping() {
		return stopping == Boolean.TRUE;
	}

	@Override
	protected void stopping(Info info) {
		super.stopping(info);
		this.info = info;
		this.stopping = Boolean.FALSE;
	}

	// -------

	public long[] gapCounts = new long[1024];
	Info info = null;

	void printGapStats(PrintStream out) {
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
		int idx = gap >> 1;
		if (idx >= gapCounts.length)
			gapCounts = Arrays.copyOf(gapCounts, idx * 2);
		++gapCounts[idx];
		if (--cnt == 0) {
			cnt = timeout;
			return stopping != null;
		} else {
			return false;
		}
	}

	@Override
	protected BigInteger find(int gap, BigInteger P) {
		BigInteger Q = nextPrime(P);
		int delta = 0;
		while ((delta = Q.subtract(P).intValueExact()) < gap) {
			if (countGap(delta))
				return null;

			P = Q;
			Q = nextPrime(P);
		}
		countGap(delta);
		return P;
	}

	public static void main(String[] args) throws Exception {
		new NaiveGap().run();
	}

}

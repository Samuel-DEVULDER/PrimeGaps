package primegap;

import java.math.BigInteger;
import java.util.Arrays;

public class NaiveGap extends AbstractPrimeGap {
	long[] gapCounts = new long[1024]; // couvre gaps jusqu'à 1024 au lieu de 512
	Info info = null;
	volatile Boolean stopping;
	{
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (stopping == null)
				stopping = Boolean.TRUE;
			while (stopping != Boolean.FALSE)
				Thread.onSpinWait(); // attend l'ack ✅
			printGapStats();
		}));
	}

	@Override
	protected void stopping(Info info) {
		super.stopping(info);
		this.info = info;
		this.stopping = Boolean.FALSE;
	}

	void printGapStats() {
		long maxCount = 0;
		double total = 0;
		for (long c : gapCounts) {
			maxCount = Math.max(maxCount, c);
			total += c;
		}

		int countWidth = Math.max(5, Long.toString(maxCount).length()); // largeur du plus grand
		int BAR_WIDTH = 60;

		String align = "%-20s : ";
		printf("\n=== Statistics ===%n");
		printf(align + "%,.0f%n", "#Primes", total);
		if (info != null) {
			printf(align + "%,.1f secs%n", "Time", info.time() / 1e9);
			printf(align + "%,.1f primes/sec%n", "Speed", (total * 1e9) / info.time());
			printf(align + "%s (~%,.4g)%n", "Biggest prime", info.lastP(), info.lastP().doubleValue());
			printf(align + "%s (%.1f)%n", "Best prime (merit)", info.best_P(), info.best_merit());
		}
		printf("%n");
		printf("%-8s  %" + countWidth + "s  %s%n", "gap", "count", "histogram");
		String line = "-".repeat(8 + 2 + countWidth + 2 + BAR_WIDTH);
		printf("%s%n", line);
		String colors = " .:-=+*#%@";
		// colors = "
		// .'`^\",:;Il!i><~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$";
		for (int i = 1; i < gapCounts.length; i++) {
			if (gapCounts[i] == 0)
				continue;
			int gap = i << 1;
			long count = gapCounts[i];
			int bar = (int) (count * BAR_WIDTH / maxCount);
			int col = (int) ((colors.length() * count) / (1 + maxCount));
			String c = colors.substring(col, col + 1);
			System.out.printf("%-8d  %" + countWidth + "d  %s%n", gap, count, c.repeat(bar));
		}
		printf("%s%n", line);
	}

	final int timeout = 100_000;
	private int cnt = timeout;

	boolean countGap(int gap) {
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
	BigInteger find(int gap, BigInteger P) {
		BigInteger Q = nextPrimeImpl(P);
		int delta = 0;
		while ((delta = Q.subtract(P).intValueExact()) < gap) {
			if (countGap(delta))
				return null;

			P = Q;
			Q = nextPrimeImpl(P);
		}
		countGap(delta);
		return P;
	}

	public static void main(String[] args) throws Exception {
		new NaiveGap().run();
	}

}

package primegap.naive;

import java.math.BigInteger;

import primegap.IterativePrimeGap;
import primegap.util.MillerRabin;

/**
 * This implementation uses a parallelized version of the Miller-Rabin primality
 * test to check for primality. The Miller-Rabin test is a probabilistic test
 * that can quickly determine if a number is composite or probably prime. By
 * running multiple iterations of the test in parallel, we can increase the
 * confidence level of our primality checks while still maintaining good
 * performance.
 */
public class ParallelMillerRabinGap extends IterativePrimeGap {
	@Override
	protected BigInteger nextPrimeImpl(BigInteger N) {
		BigInteger P = N.add(N.testBit(0) ? TWO : ONE);
		if (P.equals(cache[0]))
			return cache[1];
		cache[0] = P;
		while (!isPrime(P))
			P = P.add(TWO);
		cache[1] = P;
		return P;
	}

	private BigInteger cache[] = new BigInteger[2];

	public boolean isPrime(BigInteger N) {
		var ok = MillerRabin.instance.isPrime(N, MILLER_RABIN_PASSES, true);

		// assert ok == N.isProbablePrime(10) : "ok=" + ok + " " +
		// N.isProbablePrime(10);

		return ok;
	}

	public static void main(String[] args) throws Exception {
		new ParallelMillerRabinGap().run();
	}
}

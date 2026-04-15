package primegap;

import java.math.BigInteger;

/**
 * This implementation uses Java's built-in nextProbablePrime() method to find
 * the next prime. It serves as a baseline for performance comparison against
 * more sophisticated algorithms. The nextProbablePrime() method is a
 * probabilistic algorithm that returns a prime number that is greater than the
 * given BigInteger, with a certain level of certainty. This implementation is
 * straightforward and relies on Java's internal optimizations for prime number
 * generation.
 */
public class JavaNextProbablePrimeGap extends IterativePrimeGap {
	public BigInteger nextPrimeImpl(BigInteger N) {
		return N.nextProbablePrime();
	}

	public static void main(String[] args) throws Exception {
		new JavaNextProbablePrimeGap().run();
	}

}

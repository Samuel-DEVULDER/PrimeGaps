package primegap;

import java.math.BigInteger;
import java.util.stream.IntStream;

/**
 * This implementation finds the next prime after n! + 2, where n is the gap
 * size. This is based on the fact that for any integer n > 1, n! + 2 is not
 * divisible by any integer from 2 to n, and thus has a good chance of being
 * prime or having a large prime factor.
 */
public class FactorialGap extends AbstractPrimeGap {

	BigInteger factorial(int n) {
		return IntStream.range(2, n + 1).mapToObj(AbstractPrimeGap::v).reduce(BigInteger.ONE, BigInteger::multiply);
	}

	@Override
	protected BigInteger find(int gap, BigInteger P) {
		return prevPrime(factorial(gap).add(v(2)));
	}

	public static void main(String[] args) {
		new FactorialGap().run();
	}

}

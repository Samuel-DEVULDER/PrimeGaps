package primegap;
import java.math.BigInteger;
import java.util.stream.IntStream;

public class FactorialGap extends AbstractPrimeGap {

	BigInteger factorial(int n) {
		return IntStream.range(2, n+1).mapToObj(AbstractPrimeGap::v).reduce(BigInteger.ONE, BigInteger::multiply);
	}

	@Override
	protected BigInteger find(int gap, BigInteger P) {
		return prevPrime(factorial(gap).add(v(2)));
	}

	public static void main(String[] args) {
		new FactorialGap().run();
	}

}

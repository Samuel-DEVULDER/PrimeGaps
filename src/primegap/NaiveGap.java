import java.math.BigInteger;

public class NaiveGap extends AbstractPrimeGap {

	@Override
	BigInteger find(int gap, BigInteger P) {
		BigInteger Q = nextPrimeImpl(P);
		while (Q.subtract(P).intValueExact() < gap) {
			P = Q;
			Q = nextPrimeImpl(P);
		}
		return P;
	}

	public static void main(String[] args) {
		new NaiveGap().run();
	}

}

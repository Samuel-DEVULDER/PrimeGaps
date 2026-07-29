package primegap.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class to gather and print machine information, including CPU model
 * and cache sizes. It also includes a method to probe the cache hierarchy by
 * measuring memory access latencies for different buffer sizes.
 */
public class Machine {
	public static void printMachineInfo(PrintStream out) {
		Runtime rt = Runtime.getRuntime();
		out.printf("\r%-80s%n", "=== Machine Info ===");
		out.printf("OS      : %s %s (%s)%n", System.getProperty("os.name"), System.getProperty("os.version"),
				System.getProperty("os.arch"));
		out.printf("JVM     : %s %s (%s)%n", System.getProperty("java.vm.name"), System.getProperty("java.version"),
				System.getProperty("java.vm.vendor"));
		out.printf("CPUs    : %d%n", rt.availableProcessors());
		out.printf("RAM     : %.1f GB total%n", rt.maxMemory() / 1e9);
		printCpuModel(out);
		out.printf("====================%n");
	}

	public static void printCpuModel(PrintStream out) {
		String os = System.getProperty("os.name").toLowerCase();
		String[] cmd;
		if (os.contains("linux"))
			cmd = new String[] { "sh", "-c", "lscpu | grep -E 'Model name|L1|L2|L3'" };
		else if (os.contains("mac"))
			cmd = new String[] { "sh", "-c", "sysctl -n machdep.cpu.brand_string" };
		else // Windows
			cmd = new String[] { "cmd", "/c", "wmic cpu get name,L2CacheSize,L3CacheSize" };

		try {
			Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			new String(proc.getInputStream().readAllBytes()).lines().filter(l -> !l.isBlank()).forEach(out::println);
		} catch (IOException ignore) {
		}
	}

	@SuppressWarnings("resource")
	public static int probeCache(PrintStream out) {
		out = out == null ? NullStream.instance : out;
		out.println("=== Cache probing (pointer chasing) ===");

		class ChainBuilder {
			int[] buildChain(int len) {
				int[] arr = new int[len];
				for (int i = 0; i < len; i++)
					arr[i] = i;
				java.util.Random rng = new java.util.Random(42);
				for (int i = len - 1; i > 0; i--) {
					int j = rng.nextInt(i + 1);
					int tmp = arr[i];
					arr[i] = arr[j];
					arr[j] = tmp;
				}
				int[] chain = new int[len];
				for (int i = 0; i < len - 1; i++)
					chain[arr[i]] = arr[i + 1];
				chain[arr[len - 1]] = arr[0];
				return chain;
			}
		}
		var chainBuilder = new ChainBuilder();

		// JIT warm-up
		int[] w = chainBuilder.buildChain(256 * 1024 / 4);
		long wEnd = System.nanoTime() + 3_000_000_000L;
		int idx = 0;
		while (System.nanoTime() < wEnd)
			idx = w[idx];
		if (idx < 0)
			out.print("");

		// Sizes: one per octave (x2), from 8 KB to 64 MB
		List<Integer> sizeList = new ArrayList<>();
		for (int sz = 8 * 1024; sz <= 64 * 1024 * 1024; sz *= 2)
			sizeList.add(sz);
		int steps = sizeList.size();
		int[] sizes = sizeList.stream().mapToInt(Integer::intValue).toArray();
		double[] latencies = new double[steps];

		int REPEATS = 7;
		int HOPS = 1 << 23;

		for (int s = 0; s < steps; s++) {
			int len = sizes[s] / 4;
			int[] chain = chainBuilder.buildChain(len);

			double[] samples = new double[REPEATS];
			for (int r = 0; r < REPEATS; r++) {
				int cur = 0;
				long t0 = System.nanoTime();
				for (int i = 0; i < HOPS; i++)
					cur = chain[cur];
				long dt = System.nanoTime() - t0;
				if (cur < 0)
					out.print("");
				samples[r] = (double) dt / HOPS;
			}
			java.util.Arrays.sort(samples);
			latencies[s] = samples[REPEATS / 2];
			out.printf("size=%7d KB  latency=%6.1f ns%n", sizes[s] / 1024, latencies[s]);
		}

		// JIT stabilization: 3 consecutive points within +/-30% of their average
		int startDetect = 0;
		for (int s = 0; s < steps - 3; s++) {
			double a = latencies[s], b = latencies[s + 1], c = latencies[s + 2];
			double avg = (a + b + c) / 3.0;
			if (avg > 0 && Math.abs(a - avg) / avg < 0.30 && Math.abs(b - avg) / avg < 0.30
					&& Math.abs(c - avg) / avg < 0.30) {
				startDetect = s;
				break;
			}
		}
		out.printf("  (JIT stable from %d KB)%n", sizes[startDetect] / 1024);

		// Threshold detection via sliding window 2+2
		List<Integer> thresholds = new ArrayList<>();
		for (int s = startDetect + 2; s < steps - 2; s++) {
			double before = (latencies[s - 2] + latencies[s - 1]) / 2.0;
			double after = (latencies[s] + latencies[s + 1]) / 2.0;
			boolean bigJump = after / before > 1.6;
			boolean farEnough = thresholds.isEmpty() || sizes[s] > thresholds.get(thresholds.size() - 1) * 4;
			if (bigJump && farEnough)
				thresholds.add(sizes[s - 1]);
		}

		// Print raw thresholds
		out.println("\n=== Cache boundaries ===");
		for (int i = 0; i < thresholds.size() && i < 3; i++)
			out.printf("  boundary #%d : ~%d KB%n", i + 1, thresholds.get(i) / 1024);

		// Heuristic: if boundary#1 < 128 KB -> it is L1 end, use boundary#2 for L2
		int targetIdx = 0;
		if (thresholds.size() >= 2 && thresholds.get(0) < 128 * 1024)
			targetIdx = 1;

		int optimal;
		if (targetIdx < thresholds.size()) {
			int l2end = thresholds.get(targetIdx);
			optimal = Integer.highestOneBit(l2end);
			if (l2end > optimal + optimal / 2)
				optimal <<= 1;
			out.printf("  => estimated L2 end : ~%d KB%n", l2end / 1024);
		} else {
			optimal = 256 * 1024;
			out.println("  (L2 boundary not detected, fallback 256 KB)");
		}

		out.printf("%n=> Optimal sieve size : %d KB%n%n", optimal / 1024);
		return optimal;
	}

	public static long getCpuTimeNano() {
		return timer.getAsLong();
	}

	private static final LongSupplier timer = initTimer();

	private static LongSupplier initTimer() {
		ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
		if (tmx.isThreadCpuTimeSupported()) {
			tmx.setThreadCpuTimeEnabled(true);
			assert Java.dbg("[timer] using thread CPU time");
			return tmx::getCurrentThreadCpuTime;
		}
		assert Java.dbg("[timer] fallback to nanoTime");
		return System::nanoTime;
	}

	private static class StayAwake {
		boolean installed = false;
		int noSleep = 0;

		private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

		private static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");

		// --- Windows (FFM) ---
		private MethodHandle winHandle;

		private static final int ES_CONTINUOUS = 0x80000000;
		private static final int ES_SYSTEM_REQUIRED = 0x00000001;

		@SuppressWarnings("preview")
		private void initWindows() throws Exception {
			if (winHandle != null)
				return;

			Linker linker = Linker.nativeLinker();
			SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

			winHandle = linker.downcallHandle(kernel32.find("SetThreadExecutionState").get(),
					FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
		}

		// --- Linux ---
		private Process inhibitor;

		// --- API publique ---
		public void preventSleep() {
			if (noSleep++ > 0)
				return;
			try {
				if (IS_WINDOWS) {
					initWindows();
					winHandle.invokeExact(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
				} else if (IS_LINUX) {
					if (inhibitor == null) {
						inhibitor = new ProcessBuilder("systemd-inhibit", "--why=Java running", "sleep", "infinity")
								.start();
					}
				}
			} catch (Throwable e) {
				assert Java.dbg(e);
			}
			if (!installed) {
				installed = true;
				Java.atexit(atExitCallback); // add
			}
		}

		Runnable atExitCallback = () -> allowSleep();

		public void allowSleep() {
			if (0 == --noSleep) {
				try {
					if (IS_WINDOWS && winHandle != null) {
						winHandle.invokeExact(ES_CONTINUOUS);
					} else if (IS_LINUX && inhibitor != null) {
						inhibitor.destroy();
						inhibitor = null;
					}
					Java.atexit(atExitCallback); // remove
				} catch (Throwable e) {
					assert Java.dbg(e);
				}
			}
		}
	}

	static StayAwake awake = new StayAwake();

	static public void preventSleep() {
		awake.preventSleep();
	}

	static public void allowSleep() {
		awake.allowSleep();
	}

	/**
	 * Detects the L2 cache size PER PHYSICAL CORE, in a cross-platform manner.
	 *
	 * Problem: on Windows, WMIC/PowerShell returns the *total* L2 (sum of all
	 * cores), whereas on Linux/macOS, APIs usually return the per-core value. => We
	 * always normalize to "per physical core".
	 *
	 * IMPORTANT: never use Runtime.getRuntime().availableProcessors() as a
	 * substitute for physical core count — it returns logical threads (e.g. 28 on a
	 * 20-core i7-13850HX with Hyper-Threading), which would produce a wrong
	 * per-core value.
	 */
	public static class Cache {

		// Per-core floor/ceiling (realistic values 2005-2026)
		private static final long L2_PER_CORE_MIN_BYTES = 128L * 1024; // 128 KB
		private static final long L2_PER_CORE_MAX_BYTES = 4L * 1024 * 1024; // 4 MB

		// -------------------------------------------------------------------------
		// Main API
		// -------------------------------------------------------------------------

		/**
		 * Returns the L2 cache size per physical core, in bytes. Uses the first
		 * strategy that succeeds, in order: 1. Linux :
		 * /sys/devices/system/cpu/cpu0/cache/ 2. macOS : sysctl hw.l2cachesize 3.
		 * Windows: PowerShell Get-CimInstance Win32_Processor (preferred) 4. Windows:
		 * wmic cpu get L2CacheSize,NumberOfCores (fallback) 5. Default: 512 KB
		 *
		 * The fallback NEVER uses availableProcessors() as a core count substitute,
		 * because that returns logical threads, not physical cores.
		 */
		public static int detectL2CachePerCoreBytes() {
			String os = System.getProperty("os.name", "").toLowerCase();
			try {
				if (os.contains("linux"))
					return clamp(linuxL2PerCore());
				if (os.contains("mac"))
					return clamp(macL2PerCore());
				if (os.contains("win"))
					return clamp(windowsL2PerCore());
			} catch (Exception ignored) {
			}
			return 512 * 1024; // ultimate default
		}

		/**
		 * Recommended sieve window size (number of longs). ws = L2PerCore / Long.BYTES
		 */
		public static long recommendedWindowSize() {
			return detectL2CachePerCoreBytes() / Long.BYTES;
		}

		// -------------------------------------------------------------------------
		// Linux: /sys/devices/system/cpu/cpu0/cache/index*/
		// -------------------------------------------------------------------------
		private static long linuxL2PerCore() throws Exception {
			// Look for level 2 among the cpu0 cache entries
			for (int idx = 0; idx <= 4; idx++) {
				String levelFile = "/sys/devices/system/cpu/cpu0/cache/index" + idx + "/level";
				String sizeFile = "/sys/devices/system/cpu/cpu0/cache/index" + idx + "/size";
				String level = readFile(levelFile).trim();
				if ("2".equals(level)) {
					String sizeStr = readFile(sizeFile).trim(); // e.g. "512K" or "2048K"
					return parseSizeKB(sizeStr) * 1024L;
				}
			}
			throw new Exception("L2 cache not found in /sys");
		}

		// -------------------------------------------------------------------------
		// macOS: sysctl -n hw.l2cachesize (returns directly per core, in bytes)
		// -------------------------------------------------------------------------
		private static long macL2PerCore() throws Exception {
			String out = runCommand("sysctl", "-n", "hw.l2cachesize");
			return Long.parseLong(out.trim());
		}

		// -------------------------------------------------------------------------
		// Windows: PowerShell (preferred) then WMIC (fallback)
		//
		// Both commands return NumberOfCores = physical cores only (e.g. 20 on an
		// i7-13850HX: 14 P-cores + 6 E-cores), NOT logical threads (28 with HT).
		// We must use that value — never availableProcessors() — to divide L2 total.
		// -------------------------------------------------------------------------
		private static long windowsL2PerCore() throws Exception {
			// WMIC is absent on recent Windows 11 builds => try PowerShell first
			try {
				return windowsViaPowerShell();
			} catch (Exception e) {
				return windowsViaWmic();
			}
		}

		private static long windowsViaPowerShell() throws Exception {
			// PowerShell: Get-CimInstance Win32_Processor
			// L2CacheSize = total KB across all physical cores
			// NumberOfCores = physical core count (P-cores + E-cores, no HT)
			String script = "$c=Get-CimInstance Win32_Processor;"
					+ "Write-Output ($c.L2CacheSize.ToString() + ' ' + $c.NumberOfCores.ToString())";
			String out = runCommand("powershell", "-NoProfile", "-Command", script).trim();
			String[] parts = out.split("\\s+");
			if (parts.length < 2)
				throw new Exception("Unexpected PowerShell output: " + out);
			long l2KB = Long.parseLong(parts[0].trim());
			long cores = Long.parseLong(parts[1].trim());
			if (cores <= 0)
				throw new Exception("Invalid physical core count: " + cores);
			return (l2KB * 1024L) / cores; // per physical core
		}

		private static long windowsViaWmic() throws Exception {
			// wmic cpu get L2CacheSize,NumberOfCores /value
			// L2CacheSize = total KB, NumberOfCores = physical cores (same as PowerShell)
			String out = runCommand("wmic", "cpu", "get", "L2CacheSize,NumberOfCores", "/value");
			long l2KB = parseWmicLong(out, "L2CacheSize");
			long cores = parseWmicLong(out, "NumberOfCores");
			if (cores <= 0)
				throw new Exception("Invalid physical core count from WMIC: " + cores);
			// Do NOT fall back to availableProcessors() here: it returns logical threads,
			// not physical cores, and would silently produce a wrong per-core value.
			return (l2KB * 1024L) / cores; // per physical core
		}

		// -------------------------------------------------------------------------
		// Fallback JVM: sun.cpu.l2.cache.size system property
		// (set by some embedded/mobile JVMs; absent on desktop HotSpot)
		// -------------------------------------------------------------------------
		@SuppressWarnings("unused")
		private static long jvmL2PerCore() throws Exception {
			// Some JVMs (J9, Zing, embedded) expose the L2 cache size via this property.
			// It is NOT present on standard HotSpot desktop/server builds.
			String prop = System.getProperty("sun.cpu.l2.cache.size");
			if (prop != null && !prop.isBlank()) {
				return Long.parseLong(prop.trim()); // value is already per-core, in bytes
			}
			// com.sun.management.OperatingSystemMXBean only exposes RAM sizes, not cache.
			// There is no standard JVM API for L2 cache size — give up and use the default.
			throw new Exception("No JVM L2 cache size property available (sun.cpu.l2.cache.size)");
		}

		// -------------------------------------------------------------------------
		// Utilities
		// -------------------------------------------------------------------------

		private static int clamp(long value) {
			return (int) Math.max(L2_PER_CORE_MIN_BYTES, Math.min(L2_PER_CORE_MAX_BYTES, value));
		}

		private static String readFile(String path) throws Exception {
			return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
		}

		private static String runCommand(String... cmd) throws Exception {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			StringBuilder sb = new StringBuilder();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null)
					sb.append(line).append('\n');
			}
			p.waitFor();
			return sb.toString();
		}

		/** Parse "512K" or "2048K" into KB */
		private static long parseSizeKB(String s) {
			s = s.toUpperCase().trim();
			if (s.endsWith("K"))
				return Long.parseLong(s.replace("K", "").trim());
			if (s.endsWith("M"))
				return Long.parseLong(s.replace("M", "").trim()) * 1024L;
			return Long.parseLong(s); // assumed to already be in KB
		}

		/** Extract a long value from a WMIC output of the form "Key=Value\r\n" */
		private static long parseWmicLong(String output, String key) throws Exception {
			Pattern p = Pattern.compile(key + "=(\\d+)", Pattern.CASE_INSENSITIVE);
			Matcher m = p.matcher(output);
			if (m.find())
				return Long.parseLong(m.group(1));
			throw new Exception("Key not found in WMIC output: " + key);
		}

		// -------------------------------------------------------------------------
		// Quick test entry point
		// -------------------------------------------------------------------------
		public static void main(String[] args) {
			long l2 = detectL2CachePerCoreBytes();
			long ws = recommendedWindowSize();
			System.out.printf("OS               : %s%n", System.getProperty("os.name"));
			System.out.printf("Logical CPUs     : %d%n", Runtime.getRuntime().availableProcessors());
			System.out.printf("L2 per phys.core : %,d bytes  (%,d KB)%n", l2, l2 / 1024);
			System.out.printf("Window size      : %,d longs%n", ws);
		}
	}

	/**
	 * Detects CPU cache topology (L2, L3, CPU count) and computes optimal window
	 * size (WS) for a segmented prime sieve based on empirical benchmarking rules.
	 *
	 * <p>
	 * Empirical rules derived from benchmark data across multiple machines:
	 * <ul>
	 * <li>Sequential: WS = (L2 / 8) / cpuCount</li>
	 * <li>Parallel: WS = LLC / 8 where LLC = max(L2, L3)</li>
	 * <li>Mode: Parallel if cpuCount > 4, else Sequential</li>
	 * </ul>
	 *
	 * <p>
	 * The detection works on Windows, Linux, and macOS. If all OS-level detection
	 * methods fail, a pure-Java empirical fallback is used.
	 *
	 * <p>
	 * Usage:
	 * 
	 * <pre>{@code
	 * CacheDetector detector = new CacheDetector();
	 * CacheInfo info = detector.detect();
	 * WindowSize ws = detector.computeWs(info);
	 * System.out.println("Best WS: " + ws.bestWs());
	 * }</pre>
	 */
	public static final class CacheDetector {
		// --------------------------------------------------------------
		// Records (immutable data carriers)
		// --------------------------------------------------------------

		/**
		 * Immutable cache topology information.
		 *
		 * @param l2Bytes  total L2 cache size in bytes
		 * @param l3Bytes  total L3 cache size in bytes (0 if absent)
		 * @param cpuCount number of logical processors
		 */
		public record CacheInfo(long l2Bytes, long l3Bytes, int cpuCount) {

			/**
			 * Returns the Last Level Cache (LLC) size in bytes. If no L3 is present, L2 is
			 * the LLC.
			 */
			public long llcBytes() {
				return Math.max(l2Bytes, l3Bytes);
			}

			/**
			 * Returns the name of the cache level acting as LLC.
			 */
			public String llcSource() {
				return (l3Bytes >= l2Bytes && l3Bytes > 0) ? "L3" : "L2";
			}
		}

		private record CpuModel(String regex, long l2Bytes, long l3Bytes, int physicalCores) {
		}

		// --------------------------------------------------------------
		// Historical CPU lookup table (L2 = total, L3 = total, 0 = none)
		// Sorted by specificity: most specific patterns first.
		// --------------------------------------------------------------

		private static final CpuModel[] CPU_TABLE = {
				// Intel Core 2 (Conroe, Wolfdale, Kentsfield, Yorkfield) — no L3
				new CpuModel("(?i)Core\\s*2\\s*Quad.*Q9[456]00", 2L * 4096 * 1024, 0, 4), // 2×4MB L2
				new CpuModel("(?i)Core\\s*2\\s*Quad.*Q8[34]00", 2L * 2048 * 1024, 0, 4), // 2×2MB L2
				new CpuModel("(?i)Core\\s*2\\s*Quad.*Q6[567]00", 2L * 4096 * 1024, 0, 4), // 2×4MB L2
				new CpuModel("(?i)Core\\s*2\\s*Quad.*Q6600", 2L * 4096 * 1024, 0, 4),
				new CpuModel("(?i)Core\\s*2\\s*Duo.*E8[456]00", 1L * 6144 * 1024, 0, 2), // 6MB shared L2
				new CpuModel("(?i)Core\\s*2\\s*Duo.*E[67]0[012]0", 1L * 4096 * 1024, 0, 2), // 4MB shared L2
				new CpuModel("(?i)Core\\s*2\\s*Duo.*E4[234]00", 1L * 2048 * 1024, 0, 2), // 2MB shared L2
				new CpuModel("(?i)Core\\s*2\\s*Duo.*T[789]000", 1L * 4096 * 1024, 0, 2), // mobile 4MB
				new CpuModel("(?i)Core\\s*2\\s*Duo.*T5[67]00", 1L * 2048 * 1024, 0, 2), // mobile 2MB
				new CpuModel("(?i)Core\\s*2\\s*Duo.*L7[45]00", 1L * 4096 * 1024, 0, 2), // low-volt 4MB
				new CpuModel("(?i)Core\\s*2\\s*Duo.*L[67]200", 1L * 2048 * 1024, 0, 2), // low-volt 2MB
				new CpuModel("(?i)Core\\s*2\\s*Extreme", 1L * 4096 * 1024, 0, 2), // X6800 etc
				new CpuModel("(?i)Core\\s*2\\s*Solo", 1L * 2048 * 1024, 0, 1), // mobile single
				new CpuModel("(?i)Core\\s*2", 1L * 2048 * 1024, 0, 2), // generic fallback

				// Intel Pentium 4 / Pentium D / Celeron D — no L3
				new CpuModel("(?i)Pentium\\s*D.*9[01][234]0", 2L * 2048 * 1024, 0, 2), // 2×2MB
				new CpuModel("(?i)Pentium\\s*D.*8[234]0", 2L * 1024 * 1024, 0, 2), // 2×1MB
				new CpuModel("(?i)Pentium\\s*D.*8[23]0", 2L * 1024 * 1024, 0, 2),
				new CpuModel("(?i)Pentium\\s*D", 2L * 1024 * 1024, 0, 2),
				new CpuModel("(?i)Pentium\\s*4.*6[234]0", 1L * 2048 * 1024, 0, 1), // 2MB L2 Prescott
				new CpuModel("(?i)Pentium\\s*4.*5[456]0", 1L * 1024 * 1024, 0, 1), // 1MB L2 Prescott
				new CpuModel("(?i)Pentium\\s*4.*5[123]0", 1L * 512 * 1024, 0, 1), // 512KB L2
				new CpuModel("(?i)Pentium\\s*4.*3[123]0", 1L * 512 * 1024, 0, 1), // 512KB L2 Northwood
				new CpuModel("(?i)Pentium\\s*4", 1L * 512 * 1024, 0, 1),
				new CpuModel("(?i)Celeron\\s*D", 1L * 256 * 1024, 0, 1),
				new CpuModel("(?i)Celeron", 1L * 128 * 1024, 0, 1),

				// AMD Athlon 64 / X2 / FX — no L3
				new CpuModel("(?i)Athlon\\s*64\\s*FX.*7[12]", 1L * 1024 * 1024, 0, 2), // FX-70 dual
				new CpuModel("(?i)Athlon\\s*64\\s*FX.*6[012]", 1L * 1024 * 1024, 0, 1), // FX-60 single
				new CpuModel("(?i)Athlon\\s*64\\s*FX.*5[57]", 1L * 1024 * 1024, 0, 1), // FX-55/57
				new CpuModel("(?i)Athlon\\s*64\\s*FX.*53", 1L * 1024 * 1024, 0, 1),
				new CpuModel("(?i)Athlon\\s*64\\s*FX", 1L * 1024 * 1024, 0, 1),
				new CpuModel("(?i)Athlon\\s*64\\s*X2\\s*4[456]00", 2L * 512 * 1024, 0, 2), // 2×512KB
				new CpuModel("(?i)Athlon\\s*64\\s*X2\\s*3[456]00", 2L * 512 * 1024, 0, 2),
				new CpuModel("(?i)Athlon\\s*64\\s*X2\\s*2[456]00", 2L * 256 * 1024, 0, 2),
				new CpuModel("(?i)Athlon\\s*64\\s*X2\\s*1[456]00", 2L * 256 * 1024, 0, 2),
				new CpuModel("(?i)Athlon\\s*64\\s*X2\\s*3[89]00", 2L * 512 * 1024, 0, 2), // AM2
				new CpuModel("(?i)Athlon\\s*64\\s*X2", 2L * 512 * 1024, 0, 2),
				new CpuModel("(?i)Athlon\\s*64\\s*3[456]00\\+", 1L * 512 * 1024, 0, 1), // 512KB
				new CpuModel("(?i)Athlon\\s*64\\s*3[012]00\\+", 1L * 512 * 1024, 0, 1),
				new CpuModel("(?i)Athlon\\s*64\\s*2[89]00\\+", 1L * 512 * 1024, 0, 1),
				new CpuModel("(?i)Athlon\\s*64\\s*3[456]00", 1L * 512 * 1024, 0, 1),
				new CpuModel("(?i)Athlon\\s*64\\s*2[89]00", 1L * 512 * 1024, 0, 1),
				new CpuModel("(?i)Athlon\\s*64\\s*2[456]00", 1L * 256 * 1024, 0, 1),
				new CpuModel("(?i)Athlon\\s*64\\s*1[456]00", 1L * 256 * 1024, 0, 1),
				new CpuModel("(?i)Athlon\\s*64", 1L * 512 * 1024, 0, 1),
				new CpuModel("(?i)Sempron", 1L * 256 * 1024, 0, 1),

				// AMD Phenom / Phenom II — WITH L3 (first AMD with L3)
				new CpuModel("(?i)Phenom\\s*II\\s*X4\\s*9[567]0", 4L * 512 * 1024, 1L * 6144 * 1024, 4),
				new CpuModel("(?i)Phenom\\s*II\\s*X4\\s*8[456]0", 4L * 512 * 1024, 1L * 4096 * 1024, 4),
				new CpuModel("(?i)Phenom\\s*II\\s*X4\\s*9[01]0", 4L * 512 * 1024, 1L * 6144 * 1024, 4),
				new CpuModel("(?i)Phenom\\s*II\\s*X4", 4L * 512 * 1024, 1L * 4096 * 1024, 4),
				new CpuModel("(?i)Phenom\\s*II\\s*X6\\s*10[456]0", 6L * 512 * 1024, 1L * 6144 * 1024, 6),
				new CpuModel("(?i)Phenom\\s*II\\s*X6", 6L * 512 * 1024, 1L * 6144 * 1024, 6),
				new CpuModel("(?i)Phenom\\s*II\\s*X2\\s*5[456]0", 2L * 512 * 1024, 1L * 6144 * 1024, 2),
				new CpuModel("(?i)Phenom\\s*II\\s*X2", 2L * 512 * 1024, 1L * 4096 * 1024, 2),
				new CpuModel("(?i)Phenom\\s*X4\\s*9[456]0", 4L * 512 * 1024, 1L * 2048 * 1024, 4),
				new CpuModel("(?i)Phenom\\s*X4\\s*9[01]0", 4L * 512 * 1024, 1L * 2048 * 1024, 4),
				new CpuModel("(?i)Phenom\\s*X4\\s*8[456]0", 4L * 512 * 1024, 1L * 2048 * 1024, 4),
				new CpuModel("(?i)Phenom\\s*X3\\s*8[456]0", 3L * 512 * 1024, 1L * 2048 * 1024, 3),
				new CpuModel("(?i)Phenom\\s*X3", 3L * 512 * 1024, 1L * 2048 * 1024, 3),
				new CpuModel("(?i)Phenom\\s*X4", 4L * 512 * 1024, 1L * 2048 * 1024, 4),
				new CpuModel("(?i)Phenom", 3L * 512 * 1024, 1L * 2048 * 1024, 3),

				// Intel Core i3/i5/i7 (Nehalem to Sandy Bridge) — WITH L3
				new CpuModel("(?i)Core\\s*i7.*9[2345]0", 4L * 256 * 1024, 1L * 8192 * 1024, 4), // 920-960
				new CpuModel("(?i)Core\\s*i7.*8[456]0", 4L * 256 * 1024, 1L * 8192 * 1024, 4), // 860-870
				new CpuModel("(?i)Core\\s*i7.*7[678]0", 4L * 256 * 1024, 1L * 8192 * 1024, 4), // 760-780
				new CpuModel("(?i)Core\\s*i5.*7[456]0", 4L * 256 * 1024, 1L * 8192 * 1024, 4), // 750-760
				new CpuModel("(?i)Core\\s*i5.*6[456]0", 2L * 256 * 1024, 1L * 4096 * 1024, 2), // 650-660
				new CpuModel("(?i)Core\\s*i5.*[456]7[567]0", 4L * 256 * 1024, 1L * 8192 * 1024, 4), // 4570 etc
				new CpuModel("(?i)Core\\s*i5.*3[345]70", 4L * 256 * 1024, 1L * 6144 * 1024, 4), // 3570 etc
				new CpuModel("(?i)Core\\s*i5.*2[345]70", 4L * 256 * 1024, 1L * 6144 * 1024, 4), // 2500 etc
				new CpuModel("(?i)Core\\s*i3.*[456]1[345]0", 2L * 256 * 1024, 1L * 3072 * 1024, 2), // 4130 etc
				new CpuModel("(?i)Core\\s*i3.*3[12]20", 2L * 256 * 1024, 1L * 3072 * 1024, 2),
				new CpuModel("(?i)Core\\s*i3.*2[12]20", 2L * 256 * 1024, 1L * 3072 * 1024, 2),
				new CpuModel("(?i)Core\\s*i3.*5[345]0", 2L * 256 * 1024, 1L * 3072 * 1024, 2),
				new CpuModel("(?i)Core\\s*i7.*[234]7[0-9]{2}0", 4L * 256 * 1024, 1L * 8192 * 1024, 4), // 4770 etc
				new CpuModel("(?i)Core\\s*i7.*[234]9[0-9]{2}0", 4L * 256 * 1024, 1L * 8192 * 1024, 4), // 4930K etc
				new CpuModel("(?i)Core\\s*i7.*[234]6[0-9]{2}0", 4L * 256 * 1024, 1L * 8192 * 1024, 4), // 4670K etc
		};

		// --------------------------------------------------------------
		// Instance state
		// --------------------------------------------------------------

		private CacheInfo cached;

		// --------------------------------------------------------------
		// Public API
		// --------------------------------------------------------------

		/**
		 * Detects cache topology using OS-level methods, falling back to pure-Java
		 * empirical detection if needed.
		 *
		 * <p>
		 * Results are cached internally; subsequent calls return the same
		 * {@link CacheInfo} without re-running detection.
		 *
		 * @return detected cache topology
		 */
		public CacheInfo info() {
			if (cached != null) {
				return cached;
			}

			int cpuCount = Runtime.getRuntime().availableProcessors();
			long l2 = 0;
			long l3 = 0;

			// OS-specific detection
			String os = System.getProperty("os.name", "").toLowerCase();
			if (os.contains("win")) {
				long[] caches = detectWindows();
				l2 = caches[0];
				l3 = caches[1];
			} else if (os.contains("mac") || os.contains("darwin")) {
				long[] caches = detectMacOS();
				l2 = caches[0];
				l3 = caches[1];
			} else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
				long[] caches = detectLinux();
				l2 = caches[0];
				l3 = caches[1];
			}

			// Fallback! CPU model lookup table for historical architectures
			if (l2 == 0 && l3 == 0) {
				long[] lookup = lookupCpuModel();
				if (lookup[0] > 0) {
					l2 = lookup[0];
					l3 = lookup[1];
				}
			}

			// Pure-Java empirical fallback if all OS methods failed
			if (l2 == 0 && l3 == 0) {
				System.out.println("[WARN] OS detection failed — switching to empirical fallback");
				long[] fallback = heuristicFallback();
				l2 = fallback[0];
				l3 = fallback[1];
			}

			cached = new CacheInfo(l2, l3, cpuCount);
			return cached;
		}

		/**
		 * Retrieves the CPU model name from the OS and looks it up in the historical
		 * CPU table. Returns (0,0) if no match is found.
		 */
		private long[] lookupCpuModel() {
			String cpuName = getCpuName();
			if (cpuName == null || cpuName.isEmpty()) {
				return array(0, 0);
			}

			System.out.println("[INFO] CPU model detected: " + cpuName);

			for (CpuModel model : CPU_TABLE) {
				if (Pattern.compile(model.regex).matcher(cpuName).find()) {
					System.out.println("[INFO] Matched table entry: L2=" + human(model.l2Bytes) + " L3="
							+ (model.l3Bytes > 0 ? human(model.l3Bytes) : "none"));
					return array(model.l2Bytes, model.l3Bytes);
				}
			}

			System.out.println("[INFO] CPU not found in historical table");
			return array(0, 0);
		}

		/**
		 * Retrieves the CPU brand string from the OS.
		 */
		private String getCpuName() {
			String os = System.getProperty("os.name", "").toLowerCase();

			if (os.contains("win")) {
				String wmic = exec("wmic", "cpu", "get", "Name", "/format:value");
				if (wmic != null) {
					Matcher m = Pattern.compile("Name=(.+)").matcher(wmic);
					if (m.find())
						return m.group(1).trim();
				}
				String ps = exec("powershell", "-NoProfile", "-Command",
						"Get-CimInstance Win32_Processor | Select-Object Name | Format-List");
				if (ps != null) {
					Matcher m = Pattern.compile("Name\\s*:\\s*(.+)").matcher(ps);
					if (m.find())
						return m.group(1).trim();
				}
			} else if (os.contains("mac") || os.contains("darwin")) {
				String sysctl = exec("sysctl", "machdep.cpu.brand_string");
				if (sysctl != null) {
					Matcher m = Pattern.compile("machdep\\.cpu\\.brand_string\\s*[:=]\\s*(.+)").matcher(sysctl);
					if (m.find())
						return m.group(1).trim();
				}
			} else if (os.contains("nix") || os.contains("nux")) {
				try {
					String cpuinfo = Files.readString(Path.of("/proc/cpuinfo"));
					Matcher m = Pattern.compile("model name\\s*:\\s*(.+)").matcher(cpuinfo);
					if (m.find())
						return m.group(1).trim();
				} catch (Exception e) {
					// ignore
				}
			}

			return null;
		}

		// --------------------------------------------------------------
		// Empirical fallback (pure Java, no external commands)
		// --------------------------------------------------------------

		private long[] heuristicFallback() {
			int cpuCount = Runtime.getRuntime().availableProcessors();
			long totalRam = getPhysicalRam();
			String arch = System.getProperty("os.arch", "").toLowerCase();

			long l2PerCore, l3PerCore;

			if (arch.contains("aarch64") || arch.contains("arm")) {
				// Apple Silicon or ARM: large L2 per cluster, small/no L3
				l2PerCore = 1024 * 1024; // 1 MB
				l3PerCore = 0;
			} else if (cpuCount > 16) {
				// High-core-count Xeon/EPYC: 512KB L2, 1MB L3 per core
				l2PerCore = 512 * 1024;
				l3PerCore = 1024 * 1024;
			} else if (cpuCount > 4) {
				// Desktop/workstation: 256KB L2, 2MB L3 per core
				l2PerCore = 256 * 1024;
				l3PerCore = 2 * 1024 * 1024;
			} else {
				// Mobile/low-end: 256KB L2, 1MB L3 per core
				l2PerCore = 256 * 1024;
				l3PerCore = 1024 * 1024;
			}

			// Adjust upward if plenty of RAM (correlates with newer platforms)
			if (totalRam > 32L * 1024 * 1024 * 1024) {
				l2PerCore = Math.max(l2PerCore, 512 * 1024);
				l3PerCore = Math.max(l3PerCore, 2 * 1024 * 1024);
			}

			long l2 = l2PerCore * cpuCount;
			long l3 = l3PerCore * cpuCount;

			// Sanity-check minimums
			if (l2 < 256L * 1024 * Math.max(1, cpuCount))
				l2 = 256L * 1024 * cpuCount;
			if (l3 > 0 && l3 < 512L * 1024 * Math.max(1, cpuCount))
				l3 = 512L * 1024 * cpuCount;

			return array(l2, l3);
		}

		@SuppressWarnings("deprecation")
		private long getPhysicalRam() {
			// Strategy 1: com.sun.management extension (HotSpot, OpenJDK, GraalVM)
			try {
				java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
				if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
					try {
						return sunBean.getTotalMemorySize(); // Java 14+
					} catch (NoSuchMethodError e) {
						return sunBean.getTotalPhysicalMemorySize(); // Java 8-13
					}
				}
			} catch (Exception e) {
				// com.sun.management not available (e.g., IBM J9, native-image)
			}

			// Strategy 2: /proc/meminfo on Linux
			try {
				String meminfo = Files.readString(Path.of("/proc/meminfo"));
				Matcher m = Pattern.compile("MemTotal:\\s*(\\d+)\\s*kB").matcher(meminfo);
				if (m.find()) {
					return Long.parseLong(m.group(1)) * 1024;
				}
			} catch (Exception e) {
				// /proc/meminfo not available
			}

			// Strategy 3: Runtime.maxMemory() as last-resort proxy
			return Runtime.getRuntime().maxMemory();
		}

		// --------------------------------------------------------------
		// OS-specific detection methods
		// --------------------------------------------------------------

		/**
		 * Detects L2 and L3 on Windows via WMIC or PowerShell fallback.
		 */
		private long[] detectWindows() {
			long l2 = 0;
			long l3 = 0;

			// Primary: WMIC (present on Windows 10/11)
			String wmic = exec("wmic", "cpu", "get", "L2CacheSize,L3CacheSize", "/format:value");
			if (wmic != null) {
				Matcher m = Pattern.compile("L2CacheSize=(\\d+)").matcher(wmic);
				if (m.find())
					l2 = Long.parseLong(m.group(1)) * 1024;
				m = Pattern.compile("L3CacheSize=(\\d+)").matcher(wmic);
				if (m.find())
					l3 = Long.parseLong(m.group(1)) * 1024;
			}

			// Fallback: PowerShell (for newer Windows where WMIC is removed)
			if (l2 == 0 && l3 == 0) {
				String ps = exec("powershell", "-NoProfile", "-Command",
						"Get-CimInstance Win32_Processor | Select-Object L2CacheSize,L3CacheSize | Format-List");
				if (ps != null) {
					Matcher m = Pattern.compile("L2CacheSize\\s*:\\s*(\\d+)").matcher(ps);
					if (m.find())
						l2 = Long.parseLong(m.group(1)) * 1024;
					m = Pattern.compile("L3CacheSize\\s*:\\s*(\\d+)").matcher(ps);
					if (m.find())
						l3 = Long.parseLong(m.group(1)) * 1024;
				}
			}

			return array(l2, l3);
		}

		/**
		 * Detects L2 and L3 on Linux via sysfs or lscpu fallback.
		 */
		private long[] detectLinux() {
			long l2 = 0;
			long l3 = 0;

			// Primary: sysfs cache topology
			Path cacheDir = Paths.get("/sys/devices/system/cpu/cpu0/cache");
			if (Files.isDirectory(cacheDir)) {
				File[] indices = cacheDir.toFile().listFiles((d, name) -> name.startsWith("index"));
				if (indices != null) {
					for (File idx : indices) {
						try {
							String levelStr = readFirstLine(Paths.get(idx.getPath(), "level"));
							String sizeStr = readFirstLine(Paths.get(idx.getPath(), "size"));
							if (levelStr == null || sizeStr == null) {
								continue;
							}
							int level = Integer.parseInt(levelStr.trim());
							long bytes = parseSizeStr(sizeStr.trim());
							if (level == 2 && bytes > l2) {
								l2 = bytes;
							}
							if (level == 3 && bytes > l3) {
								l3 = bytes;
							}
						} catch (Exception ignored) {
						}
					}
				}
			}

			// Fallback: lscpu
			if (l2 == 0 || l3 == 0) {
				String lscpu = exec("lscpu");
				if (lscpu != null) {
					Matcher m = Pattern.compile("L2 cache:\\s*(\\d+)\\s*(\\S?)", Pattern.CASE_INSENSITIVE)
							.matcher(lscpu);
					if (m.find()) {
						l2 = (l2 == 0) ? toBytes(Long.parseLong(m.group(1)), m.group(2)) : l2;
					}
					m = Pattern.compile("L3 cache:\\s*(\\d+)\\s*(\\S?)", Pattern.CASE_INSENSITIVE).matcher(lscpu);
					if (m.find()) {
						l3 = (l3 == 0) ? toBytes(Long.parseLong(m.group(1)), m.group(2)) : l3;
					}
				}
			}

			return array(l2, l3);
		}

		/**
		 * Detects L2 and L3 on macOS via sysctl.
		 */
		private long[] detectMacOS() {
			long l2 = 0;
			long l3 = 0;

			// Primary: sysctl cache size keys
			String sysctl = exec("sysctl", "hw.l2cachesize", "hw.l3cachesize");
			if (sysctl == null) {
				sysctl = exec("sysctl", "-a");
			}
			if (sysctl != null) {
				Matcher m = Pattern.compile("hw\\.l2cachesize\\s*[:=]\\s*(\\d+)").matcher(sysctl);
				if (m.find())
					l2 = Long.parseLong(m.group(1));
				m = Pattern.compile("hw\\.l3cachesize\\s*[:=]\\s*(\\d+)").matcher(sysctl);
				if (m.find())
					l3 = Long.parseLong(m.group(1));
			}

			// Apple Silicon fallback: no traditional L3, use per-cluster L2
			if (l2 == 0 && l3 == 0) {
				String alt = exec("sysctl", "hw.perflevel0.l2cachesize", "hw.perflevel1.l2cachesize");
				if (alt != null) {
					Matcher m = Pattern.compile("hw\\.perflevel0\\.l2cachesize\\s*[:=]\\s*(\\d+)").matcher(alt);
					if (m.find())
						l2 = Long.parseLong(m.group(1));
				}
			}

			return array(l2, l3);
		}

		// --------------------------------------------------------------
		// Utility methods
		// --------------------------------------------------------------

		private long[] array(long... longs) {
			return longs;
		}

		/**
		 * Executes an external command and returns its stdout.
		 *
		 * @param cmd command and arguments
		 * @return command output or null if execution failed
		 */
		private String exec(String... cmd) {
			try {
				ProcessBuilder pb = new ProcessBuilder(cmd);
				pb.redirectErrorStream(true);
				Process p = pb.start();
				String out = new String(p.getInputStream().readAllBytes());
				p.waitFor();
				return out;
			} catch (Exception e) {
				return null;
			}
		}

		/**
		 * Reads the first line of a file.
		 */
		private String readFirstLine(Path path) {
			try (BufferedReader r = Files.newBufferedReader(path)) {
				return r.readLine();
			} catch (Exception e) {
				return null;
			}
		}

		/**
		 * Parses sysfs cache size strings like "256K", "30720K", "4M". Sizes without a
		 * unit are treated as kilobytes.
		 */
		private long parseSizeStr(String s) {
			s = s.trim().toUpperCase();
			try {
				if (s.endsWith("K")) {
					return Long.parseLong(s.substring(0, s.length() - 1)) * 1024;
				}
				if (s.endsWith("M")) {
					return Long.parseLong(s.substring(0, s.length() - 1)) * 1024 * 1024;
				}
				if (s.endsWith("G")) {
					return Long.parseLong(s.substring(0, s.length() - 1)) * 1024 * 1024 * 1024;
				}
				return Long.parseLong(s) * 1024;
			} catch (NumberFormatException e) {
				return 0;
			}
		}

		/**
		 * Converts a value with optional unit (K, M, G) to bytes.
		 */
		private long toBytes(long val, String unit) {
			if (unit == null || unit.isEmpty()) {
				return val * 1024;
			}
			return switch (unit.toUpperCase()) {
			case "K" -> val * 1024;
			case "M" -> val * 1024 * 1024;
			case "G" -> val * 1024 * 1024 * 1024;
			default -> val;
			};
		}

		/**
		 * Returns a human-readable string for a byte count.
		 */
		public static String human(long bytes) {
			if (bytes == 0) {
				return "0 KB";
			}
			if (bytes % (1024 * 1024) == 0) {
				return (bytes / (1024 * 1024)) + " MB";
			}
			return (bytes / 1024) + " KB";
		}

		// --------------------------------------------------------------
		// Demo main
		// --------------------------------------------------------------

		public static void main(String[] args) {
			System.out.println("=== CacheDetector ===\n");

			CacheDetector detector = new CacheDetector();
			CacheInfo info = detector.info();

			System.out.println("CPUs  : " + info.cpuCount());
			System.out.println("L2    : " + human(info.l2Bytes()) + "  (" + info.l2Bytes() + " bytes)");
			System.out.println("L3    : " + human(info.l3Bytes()) + "  (" + info.l3Bytes() + " bytes)");
			System.out.println("LLC   : " + human(info.llcBytes()) + "  (" + info.llcSource() + ")\n");
		}
	}

	public static final CacheDetector cache = new CacheDetector();
}

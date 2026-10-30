package notification;

import java.util.Arrays;

/** Latency summary in milliseconds from raw nanosecond samples. */
public record Stats(int n, double mean, double p50, double p95, double p99, double max) {
    public static Stats of(long[] nanos) {
        long[] s = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(s);
        double sum = 0;
        for (long v : s) sum += v;
        return new Stats(s.length, sum / s.length / 1e6, pct(s, 50), pct(s, 95), pct(s, 99), s[s.length - 1] / 1e6);
    }

    private static double pct(long[] sorted, double p) { // nearest-rank
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, idx)] / 1e6;
    }
}

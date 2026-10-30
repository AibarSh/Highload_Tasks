package notification;

/**
 * Fault injection for the reliability experiment (all rates are probabilities 0..1).
 *
 * @param unavailableRate  fail with UNAVAILABLE BEFORE the event is stored (request never committed)
 * @param lostResponseRate fail with UNAVAILABLE AFTER the event is stored (committed, but client sees an error)
 * @param slowRate         delay the response by slowMillis AFTER storing (client may hit its deadline)
 */
public record FaultConfig(double unavailableRate, double lostResponseRate, double slowRate, long slowMillis) {
    public static final FaultConfig NONE = new FaultConfig(0, 0, 0, 0);
}

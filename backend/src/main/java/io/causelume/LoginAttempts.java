package io.causelume;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** One-minute fixed windows. The global limit also bounds the number of peer entries. */
@Component
class LoginAttempts {
    private final Map<String,Integer> peers = new HashMap<>();
    private long start = System.nanoTime();
    private int total;
    private boolean reportedGlobal;

    enum Decision { ALLOW, FIRST_LIMIT, LIMIT }

    synchronized Decision check(String peer) {
        long now = System.nanoTime();
        if (now - start >= TimeUnit.MINUTES.toNanos(1)) {
            start = now;
            peers.clear();
            total = 0;
            reportedGlobal = false;
        }
        if (total >= 200) {
            if (reportedGlobal) return Decision.LIMIT;
            reportedGlobal = true;
            return Decision.FIRST_LIMIT;
        }
        int previous = peers.getOrDefault(peer,0);
        if (previous > 10) return Decision.LIMIT;
        peers.put(peer,previous+1);
        if (previous == 10) return Decision.FIRST_LIMIT;
        total++;
        return Decision.ALLOW;
    }
}

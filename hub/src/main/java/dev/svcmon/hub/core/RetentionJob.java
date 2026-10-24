package dev.svcmon.hub.core;

import java.util.concurrent.TimeUnit;

import dev.svcmon.hub.config.HubProperties;
import dev.svcmon.hub.store.ResultRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes raw results older than the retention period. */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final ResultRepo results;
    private final HubProperties props;

    public RetentionJob(ResultRepo results, HubProperties props) {
        this.results = results;
        this.props = props;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 6 * 3_600_000)
    public void purge() {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(props.retentionDays());
        int removed = results.deleteOlderThan(cutoff);
        if (removed > 0) {
            log.info("Retention: removed {} result(s) older than {} days", removed, props.retentionDays());
        }
    }
}

package iq.ievent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automatically flips LIVE events to ENDED once they are actually over, so
 * the host's "Ended" filter fills itself in without anyone touching the
 * event. Runs shortly after startup and then every 15 minutes — cheap single
 * UPDATE, safe to run as often as we like.
 *
 * What "over" means depends on the event's date precision (V23):
 *  - DAY/RANGE with an end time set: the moment ends_at passes.
 *  - DAY/RANGE without one: the end of the event's (last) calendar day in
 *    Asia/Baghdad — a date-only event stays live for its whole day.
 *  - MONTH: the end of that month in Asia/Baghdad.
 *  - TBA: never — an unannounced date can't be in the past.
 *
 * CANCELLED and DRAFT are never touched, and the sweep never un-ends
 * anything — postponing to a future date is what revives an ENDED event
 * (see HostService#postponeEvent).
 */
@Component
public class EventStatusSweeper {

    private static final Logger log = LoggerFactory.getLogger(EventStatusSweeper.class);

    private final JdbcTemplate jdbc;

    public EventStatusSweeper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 15 * 60 * 1000)
    @Transactional
    public void sweep() {
        int flipped = jdbc.update("""
                UPDATE events SET status = 'ENDED'
                WHERE status = 'LIVE'
                  AND date_precision <> 'TBA'
                  AND (CASE
                        WHEN date_precision = 'MONTH' THEN
                          ((date_trunc('month', starts_at AT TIME ZONE 'Asia/Baghdad')
                            + interval '1 month') AT TIME ZONE 'Asia/Baghdad') < now()
                        WHEN ends_at IS NOT NULL AND has_start_time THEN ends_at < now()
                        WHEN ends_at IS NOT NULL THEN
                          ((date_trunc('day', ends_at AT TIME ZONE 'Asia/Baghdad')
                            + interval '1 day') AT TIME ZONE 'Asia/Baghdad') < now()
                        ELSE
                          ((date_trunc('day', starts_at AT TIME ZONE 'Asia/Baghdad')
                            + interval '1 day') AT TIME ZONE 'Asia/Baghdad') < now()
                      END)
                """);
        if (flipped > 0) log.info("Auto-ended {} past event(s)", flipped);
    }
}

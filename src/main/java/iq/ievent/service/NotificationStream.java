package iq.ievent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory Server-Sent-Events fan-out for the notification bell (R31 #9).
 * A signed-in browser tab opens GET /api/notifications/stream once; every
 * time a notification row is committed for that user the tab receives a
 * "notify" event and refreshes the bell immediately, instead of waiting for
 * the 30 s poll. Single-JVM by design (sessions are in-memory too); the poll
 * stays in place as the fallback for browsers or proxies that drop the stream.
 */
@Component
public class NotificationStream {

    private static final Logger log = LoggerFactory.getLogger(NotificationStream.class);
    private static final long TIMEOUT_MS = 30L * 60 * 1000;
    private static final int MAX_PER_USER = 6;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        // a user with many open tabs: drop the oldest so the map never grows unbounded
        while (list.size() >= MAX_PER_USER) {
            SseEmitter old = list.remove(0);
            try { old.complete(); } catch (Exception ignored) {}
        }
        list.add(emitter);
        Runnable remove = () -> {
            list.remove(emitter);
            if (list.isEmpty()) emitters.remove(userId, list);
        };
        emitter.onCompletion(remove);
        // complete on timeout, otherwise Spring logs an AsyncRequestTimeoutException per idle tab
        emitter.onTimeout(() -> { remove.run(); emitter.complete(); });
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("hello").data("ok"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    /** Wakes every open tab of this user; call after the notification row is committed. */
    public void push(Long userId) {
        List<SseEmitter> list = emitters.get(userId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("notify").data("1"));
            } catch (Exception e) {
                list.remove(emitter);
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
    }

    /** Keeps idle streams alive through proxies (Caddy, browsers) that close quiet connections. */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        for (List<SseEmitter> list : emitters.values()) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    list.remove(emitter);
                    try { emitter.complete(); } catch (Exception ignored) {}
                }
            }
        }
    }

    int openStreams() {
        return emitters.values().stream().mapToInt(List::size).sum();
    }
}

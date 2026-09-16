package com.voicemail.tracker;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Maintains the list of active SSE browser connections and provides
 * a broadcast method that fans an event out to every connected client.
 *
 * <p>Thread-safe: emitter registration, removal, and broadcast all use a
 * {@link CopyOnWriteArrayList} so concurrent reads and writes are safe
 * without explicit locking.
 */
@Component
public class SseBroadcaster {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * Creates a new {@link SseEmitter} with no timeout, registers lifecycle
     * callbacks to remove it when done, and returns it to the caller (usually
     * a controller method whose return value Spring serialises as an SSE stream).
     */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()    -> emitters.remove(emitter));
        emitter.onError(e      -> emitters.remove(emitter));
        return emitter;
    }

    /**
     * Sends a named SSE event with the given data string to every currently
     * connected client.  Any emitter that throws on send is collected and
     * pruned from the active list afterwards.
     */
    public void broadcast(String eventName, String data) {
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    /** Returns the number of clients currently connected via SSE. */
    public int activeCount() {
        return emitters.size();
    }
}

package net.matrix.events;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight publish-subscribe event bus.
 * <p>
 * Listeners subscribe with {@link #subscribe(Object)} and are scanned for
 * methods annotated with {@link EventHandler}. Each handler method must
 * accept exactly one parameter — the event type it listens to.
 * <p>
 * Events are dispatched via {@link #post(Object)} in priority order
 * (HIGH → NORMAL → LOW).
 */
public final class EventBus {
    private static final EventBus INSTANCE = new EventBus();

    public static EventBus get() {
        return INSTANCE;
    }

    /** Cached handler records per event type */
    private final Map<Class<?>, CopyOnWriteArrayList<HandlerRecord>> handlers = new HashMap<>();

    /** Cache of scanned methods per listener class (avoids repeated reflection) */
    private final Map<Class<?>, List<MethodRecord>> methodCache = new HashMap<>();

    private record MethodRecord(Method method, Class<?> eventType, EventPriority priority) {}
    private record HandlerRecord(Object listener, Method method, EventPriority priority) {}

    private EventBus() {}

    /**
     * Subscribe a listener object. All methods annotated with {@link EventHandler}
     * will be registered for their respective event types.
     */
    public void subscribe(Object listener) {
        for (MethodRecord mr : getMethodRecords(listener.getClass())) {
            CopyOnWriteArrayList<HandlerRecord> list =
                    handlers.computeIfAbsent(mr.eventType, k -> new CopyOnWriteArrayList<>());

            // Avoid duplicate registration
            boolean alreadyRegistered = false;
            for (HandlerRecord hr : list) {
                if (hr.listener == listener && hr.method.equals(mr.method)) {
                    alreadyRegistered = true;
                    break;
                }
            }
            if (!alreadyRegistered) {
                list.add(new HandlerRecord(listener, mr.method, mr.priority));
                // Re-sort by priority ordinal (HIGH=0 first, LOW=2 last)
                list.sort(Comparator.comparingInt(h -> h.priority.ordinal()));
            }
        }
    }

    /**
     * Unsubscribe a listener object. All of its handler registrations are removed.
     */
    public void unsubscribe(Object listener) {
        for (CopyOnWriteArrayList<HandlerRecord> list : handlers.values()) {
            list.removeIf(hr -> hr.listener == listener);
        }
    }

    /**
     * Post an event to all registered handlers for its type.
     * Handlers are called in priority order. If the event implements
     * {@link Cancellable} and is cancelled, remaining handlers still fire
     * but downstream code should check {@code isCancelled()}.
     */
    public void post(Object event) {
        CopyOnWriteArrayList<HandlerRecord> list = handlers.get(event.getClass());
        if (list == null || list.isEmpty()) return;

        for (HandlerRecord hr : list) {
            try {
                hr.method.invoke(hr.listener, event);
            } catch (Exception e) {
                System.err.println("[Matrix EventBus] Error dispatching " +
                        event.getClass().getSimpleName() + " to " +
                        hr.listener.getClass().getSimpleName() + "." + hr.method.getName());
                e.printStackTrace();
            }
        }
    }

    /** Scans and caches annotated methods for a given class. */
    private List<MethodRecord> getMethodRecords(Class<?> clazz) {
        return methodCache.computeIfAbsent(clazz, c -> {
            List<MethodRecord> records = new ArrayList<>();
            for (Method method : c.getDeclaredMethods()) {
                EventHandler annotation = method.getAnnotation(EventHandler.class);
                if (annotation == null) continue;

                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) {
                    System.err.println("[Matrix EventBus] @EventHandler method " +
                            c.getSimpleName() + "." + method.getName() +
                            " must have exactly 1 parameter");
                    continue;
                }

                method.setAccessible(true);
                records.add(new MethodRecord(method, params[0], annotation.priority()));
            }
            return records;
        });
    }
}

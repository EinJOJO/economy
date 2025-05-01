package it.einjojo.economy.redis;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class RedisTransactionObserver implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(RedisTransactionObserver.class);
    private final List<Listener> listener = new LinkedList<>();
    private final Thread listenerThread;
    private final PubSub pubSub;

    public RedisTransactionObserver(Jedis providedJedis, Gson gson) {
        Objects.requireNonNull(providedJedis, "Jedis cannot be null");
        this.pubSub = new PubSub(gson, this);
        this.listenerThread = new Thread(() -> {
            try {
                providedJedis.subscribe(pubSub);
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.error("Listener thread error", e);
                }
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.start();
        log.info("RedisTransactionObserver initialized.");
    }

    private static class PubSub extends JedisPubSub {
        private final Gson gson;
        private final RedisTransactionObserver observer;

        private PubSub(Gson gson, RedisTransactionObserver observer) {
            this.gson = gson;
            this.observer = observer;
        }

        @Override
        public void onMessage(String channel, String message) {
            observer.notifyListeners(TransactionPayload.fromJson(gson.fromJson(message, TypeToken.get(JsonObject.class))));
        }
    }

    public void registerListener(Listener listener) {
        this.listener.add(listener);
    }

    public void unregisterListener(Listener listener) {
        this.listener.remove(listener);
    }


    protected void notifyListeners(TransactionPayload payload) {
        for (Listener l : listener) {
            try {
                l.onTransaction(payload);
            } catch (Exception e) {
                log.warn("Listener threw exception: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            pubSub.unsubscribe();
        } catch (Exception ignore) {
        }
        if (listenerThread != null && listenerThread.isAlive()) {
            listenerThread.interrupt();
            try {
                listenerThread.join(1000); // Wait for the thread to die
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public interface Listener {
        void onTransaction(TransactionPayload payload);
    }

}

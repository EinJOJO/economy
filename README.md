![Economy API](title.png)


Often I find myself setting up a custom economy on a mineraft server. Thus, I needed a solution that always fits. This is a java-library that manages an economic balance assigned to UUID. 
It should support float transactions and be multi-instance compatible.

This library needs to be shaded and is not a plugin.

Javadocs: [https://javadocs.einjojo.it/economy](https://javadocs.einjojo.it/economy/)

---



# Usage
```kotlin
repositories {
    maven {
        name = "einjojoReleases"
        url = uri("https://repo.einjojo.it/releases")
    }
}

dependencies {
    implementation("it.einjojo:economy:2.0.1")
}
```
**[Javadocs](https://repo.einjojo.it/javadoc/releases/it/einjojo/economy/2.0.0)**
### Create Service instance
```java
DataSource dataSource = //...
JedisPool jedisPool = //...
PostgresEconomyRepository repository = new PostgresEconomyRepository(dataSource::getConnection, "coins");
JedisNotifier redisNotifier = new JedisNotifier(jedisPool, "coins_eco");
EconomyService coinsService = new AsyncEconomyService(repository, redisNotifier, economyExecutorService);
coinsService.initialize(); // this will call init() on repository which creates a database schema 
// Use multiple currencies:
PostgresEconomyRepository twoRepo = new PostgresEconomyRepository(dataSource::getConnection, "second_currency");
twoRepo.init();
EconomyService tokenService = new AsyncEconomyService(repository, null, economyExecutorService); // rarely used, no need to notify other instances
      
```
### Cache

This is an example on how to use the cache.
```java
public class SyncEconomyCache implements JedisTransactionObserver.Listener, EconomyCache {
    private final Cache<UUID, Double> cache;
    private final JedisTransactionObserver observer;
    private final Map<UUID, CompletableFuture<?>> completableFutureMap = new HashMap<>();

    public EconomyCacheImpl(EconomyService economyService, JedisNotifier jedisNotifier) {
        observer = jedisNotifier.createTransactionObserver();
        observer.registerListener(this);
        this.cache = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(10)).build();
    }

    public double getBalance(UUID uuid) { /* ... */    }

    @Override
    public boolean isCached(UUID uuid) { /* ... */   }

    @Override
    public void cacheBalance(UUID uuid, double v) { /* ... */    }

    @Override
    public void onTransaction(TransactionPayload transactionPayload) {
        if (cache.getIfPresent(transactionPayload.uuid()) == null) { // ignore uncached updates
            return;
        }
        cache.put(transactionPayload.uuid(), transactionPayload.newBalance());
    }

}

// somewhere else
EconomyService service =  // get it by your provider
var cache = new EconomyCacheImpl(economyService, redisNotifier);
economyService.setSyncCache(cache);

```


## For Minecraft-Servers
I made a plugin for paper servers that provides this API using the Service-API.
https://github.com/EinJOJO/EconomyProviderPlugin

**Full Changelog**: https://github.com/EinJOJO/economy/compare/1.6.1...2.0.0

# Core Principles:
- **Database as the Single Source of Truth:** PostgreSQL will hold the definitive balance for each UUID. All other systems (caches, notifications) derive their state from it.

- **Asynchronous Operations:** Absolutely no blocking database or network I/O operations will occur on the calling thread (e.g., the Minecraft main server thread). All API methods involving I/O will return CompletableFuture or a similar async construct.

- **Optimistic Concurrency Control:** To handle simultaneous updates from different instances without complex distributed locking, we will use optimistic locking at the database level.
 
- **Decoupled Notifications:** Redis Pub/Sub will be used to broadcast balance changes after they are successfully committed to the database, allowing other instances or services to react (e.g., update local caches, refresh scoreboards).
 
- **Atomicity via Database:** Database transactions and atomic operations (`UPDATE ... SET balance = balance + ?`) will be used to ensure individual operations are applied correctly.
 
_Engineered using Gemini 2.5 Pro_



##### Multi-Instance Synchronization Flow

    Instance A wants to withdraw 10 from Player P (Current Balance: 50, Version: 5).

    Instance B wants to withdraw 20 from Player P concurrently.

    Instance A reads: Balance=50, Version=5. Checks funds (50 >= 10). Proceeds.

    Instance B reads: Balance=50, Version=5. Checks funds (50 >= 20). Proceeds.

    Instance A executes UPDATE ... SET balance = 40, version = 6 WHERE uuid = P AND version = 5. It affects 1 row (Success). Commits.

    Instance A publishes {"uuid": P, "newBalance": 40, "change": -10} to Redis.

    Instance B executes UPDATE ... SET balance = 30, version = 6 WHERE uuid = P AND version = 5. It affects 0 rows because the version is now 6 in the DB. Rolls back.

    Instance B detects the version conflict and retries.

    Instance B reads: Balance=40, Version=6. Checks funds (40 >= 20). Proceeds.

    Instance B executes UPDATE ... SET balance = 20, version = 7 WHERE uuid = P AND version = 6. It affects 1 row (Success). Commits.

    Instance B publishes {"uuid": P, "newBalance": 20, "change": -20} to Redis.


    Other Instances (C, D, etc.): Subscribe to the Redis channel. When they receive the messages from A and B, they know the confirmed balance changes and can update their local state/UI accordingly without hitting the database themselves just for notification.



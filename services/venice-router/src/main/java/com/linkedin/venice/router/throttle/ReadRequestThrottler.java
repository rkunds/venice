package com.linkedin.venice.router.throttle;

import static com.linkedin.venice.meta.Store.NON_EXISTING_VERSION;

import com.linkedin.venice.exceptions.QuotaExceededException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.ZkRoutersClusterManager;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.RoutersClusterConfig;
import com.linkedin.venice.meta.RoutersClusterManager;
import com.linkedin.venice.meta.RoutingDataRepository;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreDataChangedListener;
import com.linkedin.venice.router.VeniceRouterConfig;
import com.linkedin.venice.router.stats.AggRouterHttpRequestStats;
import com.linkedin.venice.throttle.EventThrottler;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * This class define the throttler on reads request. Basically it will calculate the store quota per router based on
 * the total store quota and the number of living routers. Then a StoreReadThrottler will be created to maintain the
 * throttler for this store and all storage nodes which get the ONLINE replica for the current version of this store.
 * For each read request throttler will ask the related StoreReadThrottler to check both store level quota and storage
 * level quota then accept or reject it.
 */
public class ReadRequestThrottler implements RouterThrottler, StoreDataChangedListener,
    RoutersClusterManager.RouterCountChangedListener, RoutersClusterManager.RouterClusterConfigChangedListener {
  // We want to give more tight restriction for store-level quota to protect router but more lenient restriction for
  // storage node level quota. Because in some case per-storage node quota is too small to user.
  public static final long DEFAULT_STORE_QUOTA_TIME_WINDOW = TimeUnit.SECONDS.toMillis(10); // 10sec

  private static final Logger LOGGER = LogManager.getLogger(ReadRequestThrottler.class);
  private final ZkRoutersClusterManager zkRoutersManager;
  private final ReadOnlyStoreRepository storeRepository;
  private final RoutingDataRepository routingDataRepository;
  private final long maxRouterReadCapacity;
  private int lastRouterCount;

  /**
   * Sum of all store's quota for the current router.
   */
  private long idealTotalQuotaPerRouter;

  /**
   * The atomic reference of all store throttlers. While updating any throttler, lock this reference to prevent race
   * condition. We could not use volatile variable here because we will replace the whole inside map once router count
   * is changed(ReadRequestThrottler#handleRouterCountChanged), in that case lock will fail because the object that
   * this
   * reference points to has been changed.
   */
  private final AtomicReference<ConcurrentMap<String, EventThrottler>> storesThrottlers;

  private final AggRouterHttpRequestStats stats;

  private final double perStoreRouterQuotaBuffer;

  private final long storeQuotaCheckTimeWindow;

  private volatile boolean isNoopThrottlerEnabled;

  public ReadRequestThrottler(
      ZkRoutersClusterManager zkRoutersManager,
      ReadOnlyStoreRepository storeRepository,
      RoutingDataRepository routingDataRepository,
      AggRouterHttpRequestStats stats,
      VeniceRouterConfig routerConfig) {
    this(
        zkRoutersManager,
        storeRepository,
        routingDataRepository,
        routerConfig.getMaxReadCapacityCu(),
        stats,
        routerConfig.getPerStoreRouterQuotaBuffer(),
        DEFAULT_STORE_QUOTA_TIME_WINDOW);
  }

  public ReadRequestThrottler(
      ZkRoutersClusterManager zkRoutersManager,
      ReadOnlyStoreRepository storeRepository,
      RoutingDataRepository routingDataRepository,
      long maxRouterReadCapacity,
      AggRouterHttpRequestStats stats,
      double perStoreRouterQuotaBuffer,
      long storeQuotaCheckTimeWindow) {
    this.zkRoutersManager = zkRoutersManager;
    this.storeRepository = storeRepository;
    this.routingDataRepository = routingDataRepository;
    this.storeQuotaCheckTimeWindow = storeQuotaCheckTimeWindow;
    this.storeRepository.registerStoreDataChangedListener(this);
    this.stats = stats;
    this.maxRouterReadCapacity = maxRouterReadCapacity;
    this.lastRouterCount = zkRoutersManager.getExpectedRoutersCount();
    this.perStoreRouterQuotaBuffer = perStoreRouterQuotaBuffer;
    this.idealTotalQuotaPerRouter = calculateIdealTotalQuotaPerRouter();
    this.storesThrottlers = new AtomicReference<>(buildAllStoreReadThrottlers());
    this.isNoopThrottlerEnabled = false;
  }

  /**
   * Check the quota and reject the request if needed.
   *
   * @param storeName        name of the store that request is trying to visit.
   * @param readCapacityUnit usage of this read request.
   * @throws QuotaExceededException if the usage exceeded the quota throw this exception to reject the request.
   */
  @Override
  public void mayThrottleRead(String storeName, double readCapacityUnit) throws QuotaExceededException {
    if (!zkRoutersManager.isThrottlingEnabled() || isNoopThrottlerEnabled) {
      return;
    }
    EventThrottler throttler = storesThrottlers.get().get(storeName);
    if (throttler == null) {
      throw new VeniceException("Could not find the throttler for store: " + storeName);
    } else {
      throttler.maybeThrottle(readCapacityUnit);
    }
  }

  // TODO will update once we complete some experiments to finalize the correlation between size and read capacity unit.
  // TODO right now read capacity unit is just QPS;
  @Override
  public int getReadCapacity() {
    return 1;
  }

  @Override
  public void setIsNoopThrottlerEnabled(boolean isNoopThrottlerEnabled) {
    this.isNoopThrottlerEnabled = isNoopThrottlerEnabled;
  }

  protected long calculateStoreQuotaPerRouter(long storeQuota) {
    int routerCount = zkRoutersManager.getLiveRoutersCount();
    // There are some edge cases where a bad temporary value will render the quota calculation nonsensical. So we
    // default
    // to the last good read we got.
    if (routerCount <= 0) {
      routerCount = lastRouterCount;
    } else {
      lastRouterCount = routerCount;
    }

    if (routerCount <= 0) {
      LOGGER.error("Could not find any live router to serve traffic.");
    }

    long idealStoreQuotaPerRouter = routerCount > 0
        ? Math.max(storeQuota / routerCount, 5) // Do not make quota to be 0 when storeQuota < routerCount
        : 0;

    if (!zkRoutersManager.isMaxCapacityProtectionEnabled() || idealTotalQuotaPerRouter <= maxRouterReadCapacity) {
      // Current router's capacity is big enough to be allocated to each store's quota.
      return idealStoreQuotaPerRouter * (1 + (long) perStoreRouterQuotaBuffer);
    } else {
      // If we allocate ideal quota value to each store, the total quota would exceed the router's capacity.
      // The reason is the cluster does not have enough number of routers.(Might be caused by to manny router failures)
      // So each store's quota must be adjusted accordingly to make sure total quota would not exceed router's capacity.
      // Compare to the solution that use a single throttler per router to protect usage exceeding router's capacity,
      // this logic could reduce the quota for each store in proportion which could prevent the usage of a few stores
      // eat all quota.
      LOGGER.warn(
          "The ideal total quota per router: {} has exceeded the router's max capacity: {}, will reduce quotas for all store in proportion.",
          idealTotalQuotaPerRouter,
          maxRouterReadCapacity);
      return idealStoreQuotaPerRouter * maxRouterReadCapacity / idealTotalQuotaPerRouter;
    }
  }

  protected final long calculateIdealTotalQuotaPerRouter() {
    long totalQuota = 0;
    int routerCount = zkRoutersManager.getLiveRoutersCount();

    if (routerCount != 0) {
      totalQuota = storeRepository.getTotalStoreReadQuota() / routerCount;
    }
    if (zkRoutersManager.isMaxCapacityProtectionEnabled()) {
      stats.recordTotalQuota(Math.min(totalQuota, maxRouterReadCapacity));
    } else {
      stats.recordTotalQuota(totalQuota);
    }
    return totalQuota;
  }

  protected EventThrottler getStoreReadThrottler(String storeName) {
    return storesThrottlers.get().get(storeName);
  }

  private EventThrottler buildStoreReadThrottler(String storeName, long storeQuotaPerRouter) {
    stats.recordQuota(storeName, storeQuotaPerRouter);
    return new EventThrottler(
        storeQuotaPerRouter,
        storeQuotaCheckTimeWindow,
        storeName + "-throttler",
        true,
        EventThrottler.REJECT_STRATEGY);
  }

  private ConcurrentMap<String, EventThrottler> buildAllStoreReadThrottlers() {
    // Total quota for this router is changed, we have to update all store throttlers.
    List<Store> allStores = storeRepository.getAllStores();
    ConcurrentMap<String, EventThrottler> newStoreThrottlers = new ConcurrentHashMap<>();
    for (Store store: allStores) {
      if (storeHasNoValidVersion(store)) {
        continue;
      }
      newStoreThrottlers.put(
          store.getName(),
          buildStoreReadThrottler(store.getName(), calculateStoreQuotaPerRouter(store.getReadQuotaInCU())));
    }
    return newStoreThrottlers;
  }

  @Override
  public void handleRouterCountChanged(int newRouterCount) {
    // Clean all existing throttlers. We will create them again with the latest router count once getting new requests.
    LOGGER.info("Number of router has been changed. Delete all of store throttlers.");
    resetAllThrottlers();
    LOGGER.info("All throttlers were reset");
  }

  @Override
  public void handleStoreCreated(Store store) {
    if (storeHasNoValidVersion(store)) {
      return;
    }
    updateStoreThrottler(() -> {
      long storeQuotaPerRouter = calculateStoreQuotaPerRouter(store.getReadQuotaInCU());
      LOGGER.info(
          "Store: {} is created. Add a throttler with quota: {} for this store.",
          store.getName(),
          storeQuotaPerRouter);
      storesThrottlers.get().put(store.getName(), buildStoreReadThrottler(store.getName(), storeQuotaPerRouter));
    });
  }

  private void updateStoreThrottler(Runnable updater) {
    synchronized (storesThrottlers) {
      // Total store quota should be changed because of add/update/delete store.
      long oldIdealTotalQuotaPerRouter = idealTotalQuotaPerRouter;
      idealTotalQuotaPerRouter = calculateIdealTotalQuotaPerRouter();
      updater.run();
      if (oldIdealTotalQuotaPerRouter > maxRouterReadCapacity || idealTotalQuotaPerRouter > maxRouterReadCapacity) {
        // Old router's quota and/or new router's quota exceed the router's max capacity, update all store throttlers
        // 1. If the new router's quota exceeded the router's max capacity, we have to reduce the quota for each store
        // to make sure each of them get the proper proportion of the max capacity as quota.
        // 2. If the old router's quota exceed the max capacity, but new router's quota is smaller than the max capacity
        // We also need to update all store's quota, because they will all get more quotas
        // 3. If the old router's quota and new router's quota both exceeded the max capacity, we still need to update
        // all store's quota because the proportion of each store has been changed.
        if (oldIdealTotalQuotaPerRouter != idealTotalQuotaPerRouter) {
          LOGGER.info(
              "Old router's quota and/or new router's quota exceeds the router's max capacity, update throttlers for all stores.");
          storesThrottlers.set(buildAllStoreReadThrottlers());
        }
      }
    }
  }

  @Override
  public void handleStoreDeleted(String storeName) {
    updateStoreThrottler(() -> {
      LOGGER.info("Store: {} has been deleted. Remove the throttler for this store.", storeName);
      EventThrottler throttler = storesThrottlers.get().remove(storeName);
      if (throttler == null) {
        return;
      }
      stats.recordQuota(storeName, 0);
    });
  }

  @Override
  public void handleStoreChanged(Store store) {
    if (storeHasNoValidVersion(store)) {
      return;
    }
    updateStoreThrottler(() -> {
      EventThrottler eventThrottler = storesThrottlers.get().get(store.getName());
      if (eventThrottler == null) {
        LOGGER.warn(
            "Throttler have not been created for store: {}. Router might miss the creation event.",
            store.getName());
        handleStoreCreated(store);
        return;
      }

      long storeQuotaPerRouter = calculateStoreQuotaPerRouter(store.getReadQuotaInCU());
      if (storeQuotaPerRouter != storesThrottlers.get().get(store.getName()).getMaxRatePerSecond()) {
        // Handle store's quota was updated.
        LOGGER.info(
            "Read quota has been changed for store: {} - oldQuota: {}, newQuota: {}. Updating the store read throttler.",
            store.getName(),
            eventThrottler.getMaxRatePerSecond(),
            storeQuotaPerRouter);
        storesThrottlers.get().put(store.getName(), buildStoreReadThrottler(store.getName(), storeQuotaPerRouter));
      }
    });
  }

  private boolean storeHasNoValidVersion(Store store) {
    return store.getCurrentVersion() == NON_EXISTING_VERSION;
  }

  @Override
  public void handleRouterClusterConfigChanged(RoutersClusterConfig newConfig) {
    LOGGER.info("Router cluster config has been changed, reset all throttlers.");
    resetAllThrottlers();
    LOGGER.info("All throttlers were reset");
  }

  private void resetAllThrottlers() {
    synchronized (storesThrottlers) {
      long newIdealTotalQuotaPerRouter = calculateIdealTotalQuotaPerRouter();
      if (idealTotalQuotaPerRouter != newIdealTotalQuotaPerRouter) {
        idealTotalQuotaPerRouter = newIdealTotalQuotaPerRouter;
        // Total quota for this router is changed, we have to update all store throttlers.
        storesThrottlers.set(buildAllStoreReadThrottlers());
      }
    }
  }

  // This function is for testing
  protected void restoreAllThrottlers() {
    synchronized (storesThrottlers) {
      // Restore all throttlers.
      storesThrottlers.set(buildAllStoreReadThrottlers());
    }
  }
}

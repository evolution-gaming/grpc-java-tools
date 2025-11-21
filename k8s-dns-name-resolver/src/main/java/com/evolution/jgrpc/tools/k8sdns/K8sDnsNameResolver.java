package com.evolution.jgrpc.tools.k8sdns;

import static java.lang.Math.max;
import static java.lang.String.format;

import com.google.common.net.InetAddresses;
import io.grpc.*;
import io.grpc.SynchronizationContext.ScheduledHandle;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.jspecify.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import org.xbill.DNS.lookup.LookupResult;
import org.xbill.DNS.lookup.LookupSession;

/* package */ final class K8sDnsNameResolver extends NameResolver {

  private static final Logger logger = LoggerFactory.getLogger(K8sDnsNameResolver.class);

  private final ParsedDnsTargetUri targetUri;
  private final long refreshIntervalSeconds;
  private final SynchronizationContext syncCtx;
  private final ScheduledExecutorService scheduledExecutor;
  private final LookupSession dnsLookupSession;

  @Nullable private Listener listener = null;

  @Nullable private ScheduledHandle scheduledRefreshTask = null;
  @Nullable private SuccessResult lastSuccessfulResult = null;
  private boolean refreshing = false;

  private record SuccessResult(List<InetAddress> addresses, Instant receiveTime) {}

  /* package */ K8sDnsNameResolver(
      ParsedDnsTargetUri targetUri,
      int refreshIntervalSeconds,
      SynchronizationContext syncCtx,
      ScheduledExecutorService scheduledExecutor) {
    this.targetUri = targetUri;
    this.refreshIntervalSeconds = refreshIntervalSeconds;
    this.syncCtx = syncCtx;
    this.scheduledExecutor = scheduledExecutor;
    this.dnsLookupSession =
        LookupSession.defaultBuilder().searchPath(targetUri.host()).clearCaches().build();
  }

  @Override
  public String getServiceAuthority() {
    return this.targetUri.authority();
  }

  @Override
  public void shutdown() {
    if (this.scheduledRefreshTask != null) {
      this.scheduledRefreshTask.cancel();
    }
  }

  @Override
  public void start(Listener listener) {
    this.listener = listener;
    startScheduledRefreshTask(0L);
  }

  @Override
  public void refresh() {
    if (this.scheduledRefreshTask == null) {
      // this means the last attempt failed, and we are getting retried by the client

      var initialDelayMs = 0L;
      if (this.lastSuccessfulResult != null) {
        var now = Instant.now();
        var minExpectedRefreshTs =
            this.lastSuccessfulResult.receiveTime.plusSeconds(this.refreshIntervalSeconds);
        initialDelayMs = max(0L, Duration.between(now, minExpectedRefreshTs).toMillis());
      }

      startScheduledRefreshTask(initialDelayMs);
    }
  }

  private void startScheduledRefreshTask(long initialDelayMs) {
    this.scheduledRefreshTask =
        this.syncCtx.scheduleWithFixedDelay(
            this::refreshInner,
            initialDelayMs,
            this.refreshIntervalSeconds * 1000, // delay
            TimeUnit.MILLISECONDS,
            this.scheduledExecutor);
  }

  private void refreshInner() {
    if (!this.refreshing) {
      this.refreshing = true;
      resolveAllAsync(
          (addresses, err) -> {
            try {
              if (err != null) {
                handleResolutionFailure(err);
              } else if (addresses != null && !addresses.isEmpty()) {
                handleResolutionSuccess(addresses);
              } else {
                // listener.onAddresses with an empty list is equivalent to listener.onError
                // and should be retried by the client, as per NameResolver protocol
                handleResolutionFailure(new UnknownHostException(this.targetUri.hostStr()));
              }
            } finally {
              this.refreshing = false;
            }
          });
    }
  }

  private void handleResolutionFailure(Throwable err) {
    /*
    NameResolver contract specifies that the client handles retries and their frequency.
    So in case of failure, we need to cancel internal reoccurring refresh ticks and rely on refresh method
    invoked externally.
    */
    if (this.scheduledRefreshTask != null) {
      this.scheduledRefreshTask.cancel();
      this.scheduledRefreshTask = null;
    }
    getListener()
        .onError(
            Status.UNAVAILABLE
                .withDescription(format("Unable to resolve host %s", this.targetUri.hostStr()))
                .withCause(err));
  }

  private void handleResolutionSuccess(List<InetAddress> addresses) {
    // do not notify if addresses didn't change
    if (this.lastSuccessfulResult == null
        // the addresses list is always sorted here and contains only unique values
        || !this.lastSuccessfulResult.addresses.equals(addresses)) {
      var addrGroups = addresses.stream().map(this::mkAddressGroup).toList();
      getListener().onAddresses(addrGroups, Attributes.EMPTY);
    }

    this.lastSuccessfulResult = new SuccessResult(addresses, Instant.now());
  }

  private EquivalentAddressGroup mkAddressGroup(InetAddress addr) {
    return new EquivalentAddressGroup(new InetSocketAddress(addr, this.targetUri.port()));
  }

  // callback is executed under syncCtx
  private void resolveAllAsync(
      BiConsumer<@Nullable List<InetAddress>, ? super @Nullable Throwable> cb) {
    final var dnsLookupAsyncResult = this.dnsLookupSession.lookupAsync(Name.empty, Type.A);
    dnsLookupAsyncResult
        .thenApply(
            (result) -> {
              logger.debug("DNS lookup result: {}", result);
              var records =
                  Optional.ofNullable(result).map(LookupResult::getRecords).orElse(List.of());
              return records.stream()
                  .map(Record::rdataToString)
                  .distinct()
                  .sorted() // make sure that result comparison does not depend on order
                  .map(InetAddresses::forString)
                  .toList();
            })
        .whenComplete(
            (addresses, err) ->
                this.syncCtx.execute(
                    () -> {
                      if (err != null) {
                        logger.error("DNS lookup failed", err);
                      }
                      cb.accept(addresses, err);
                    }));
  }

  private Listener getListener() {
    if (this.listener == null) {
      throw new IllegalStateException("listener not set");
    }
    return this.listener;
  }
}

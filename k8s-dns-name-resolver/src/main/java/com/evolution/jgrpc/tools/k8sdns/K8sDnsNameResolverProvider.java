package com.evolution.jgrpc.tools.k8sdns;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// **
// * A provider for [[K8sDnsNameResolver]].
// * <p>
// * It is an alternative to the stock io.grpc.internal.DnsNameResolver geared towards better
// support
// * for resolving addresses of Kubernetes headless service pods by hostname.
// * <p>
// * The main improvement over `DnsNameResolver` is that this resolver implements live watching of
// the
// * set of ready pods and notifies the channel when it changes.
// * <p>
// * This resolver uses the `dnsjava` library for executing queries instead of the JDK built-in
// * capabilities. This allows executing DNS queries with caching disabled without changing JVM-wide
// * settings.
// * <p>
// * DNS queries are repeated every 15 seconds by default. The interval can be adjusted using
// * [[setRefreshIntervalSeconds]].
// * <p>
// * Only A-records are supported.
// * <p>
// * Example target URIs: - `k8s-dns:///my-svc.my-namespace.svc.my-cluster.local` - default port -
// * `k8s-dns:///my-svc.my-namespace.svc.my-cluster.local:8080`
// * <p>
// * This class is thread-safe.
// *
// * @see [[io.grpc.internal.DnsNameResolverProvider]] [[io.grpc.internal.DnsNameResolver]]
// */

/**
 * {@link NameResolverProvider} for DNS-based GRPC service discovery in Kubernetes.
 *
 * <p>It is an alternative to {@code io.grpc.internal.DnsNameResolver} geared towards better support
 * for resolving addresses of Kubernetes headless service pods by hostname.
 *
 * <p>The main improvement over {@code DnsNameResolver} is that this resolver implements live
 * watching of the set of ready pods and notifies the channel when it changes.
 *
 * <p>This resolver uses the <a href="https://github.com/dnsjava/dnsjava">dnsjava</a> library for
 * executing queries instead of the JDK built-in capabilities. This allows executing DNS queries
 * with caching disabled without changing JVM-wide settings.
 *
 * <p>DNS queries are repeated every 10 seconds by default. The interval can be adjusted using
 * {@link K8sDnsNameResolverProvider#setRefreshIntervalSeconds(int)}.
 *
 * <p>Only A-records are supported.
 *
 * <p>Example target URIs:
 *
 * <ul>
 *   <li>{@code k8s-dns://my-svc.my-namespace.svc.my-cluster.local} (default port)
 *   <li>{@code k8s-dns:///my-svc.my-namespace.svc.my-cluster.local} (default port)
 *   <li>{@code k8s-dns://my-svc.my-namespace.svc.my-cluster.local:8080}
 *   <li>{@code k8s-dns:///my-svc.my-namespace.svc.my-cluster.local:8080}
 * </ul>
 *
 * <p>If you wish to use a different URI schema, you can create a custom instance using {@link
 * K8sDnsNameResolverProvider#K8sDnsNameResolverProvider(String, int, int)} and register it
 * manually.
 *
 * <p>This class is thread-safe.
 *
 * @see NameResolverProvider
 */
public final class K8sDnsNameResolverProvider extends NameResolverProvider {

  /** The default URI scheme handled by this provider. */
  public static final String DEFAULT_SCHEME = "k8s-dns";

  /**
   * The default interval in seconds between DNS refresh operations.
   *
   * <p>The default Kubernetes CoreDNS TTL is 5 seconds. Our default refresh interval is 2x that.
   */
  public static final int DEFAULT_REFRESH_INTERVAL_SECONDS = 10;

  /**
   * The default priority for this name resolver provider.
   *
   * <p>5 is the recommended default from NameResolverProvider documentation.
   *
   * @see io.grpc.NameResolverProvider#priority()
   */
  public static final int DEFAULT_PRIORITY = 5;

  private final int priority;
  private final String scheme;

  private volatile int refreshIntervalSeconds = DEFAULT_REFRESH_INTERVAL_SECONDS;

  /** Creates a new K8sDnsNameResolverProvider with default configuration. */
  public K8sDnsNameResolverProvider() {
    this.scheme = DEFAULT_SCHEME;
    this.priority = DEFAULT_PRIORITY;
  }

  /**
   * Creates a new K8sDnsNameResolverProvider with custom configuration.
   *
   * <p>Use this constructor to register K8sDnsNameResolverProvider with a custom URI scheme.
   *
   * @param scheme the URI scheme this provider handles, non-null; {@link
   *     K8sDnsNameResolverProvider#DEFAULT_SCHEME}
   * @param priority the priority of this provider, must be [0, 10]; {@link
   *     K8sDnsNameResolverProvider#DEFAULT_PRIORITY}
   * @param refreshIntervalSeconds the interval in seconds between DNS refreshes, must be positive;
   *     {@link K8sDnsNameResolverProvider#DEFAULT_REFRESH_INTERVAL_SECONDS}
   * @see io.grpc.NameResolverProvider#priority() resolver provider priority
   * @see K8sDnsNameResolverProvider#newNameResolver(URI, Args) URI scheme handling
   */
  public K8sDnsNameResolverProvider(String scheme, int priority, int refreshIntervalSeconds) {
    this.scheme = requireNonNull(scheme, "scheme must not be null");
    this.priority = validatePriority(priority);
    this.refreshIntervalSeconds = validateRefreshInterval(refreshIntervalSeconds);
  }

  /**
   * Changes DNS query refresh interval.
   *
   * <p>Must be called before creating any channels that use this resolver's target URI schema.
   *
   * @param refreshIntervalSeconds the refresh interval in seconds, must be positive
   */
  public void setRefreshIntervalSeconds(int refreshIntervalSeconds) {
    this.refreshIntervalSeconds = validateRefreshInterval(refreshIntervalSeconds);
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return this.priority;
  }

  @Override
  public String getDefaultScheme() {
    return this.scheme;
  }

  @Nullable
  @Override
  public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
    if (Objects.equals(getDefaultScheme(), targetUri.getScheme())) {
      var parsedTargetUri = ParsedDnsTargetUri.parse(targetUri, args.getDefaultPort());

      return new K8sDnsNameResolver(
          parsedTargetUri,
          this.refreshIntervalSeconds,
          args.getSynchronizationContext(),
          args.getScheduledExecutorService());
    } else {
      return null;
    }
  }

  private static int validateRefreshInterval(int refreshIntervalSeconds) {
    checkArgument(
        refreshIntervalSeconds > 0,
        "refreshIntervalSeconds must be > 0, got %s",
        refreshIntervalSeconds);
    return refreshIntervalSeconds;
  }

  private static int validatePriority(int priority) {
    checkArgument(
        priority >= 0 && priority <= 10,
        "resolver provider priority must be [0, 10], got %s",
        priority);
    return priority;
  }
}

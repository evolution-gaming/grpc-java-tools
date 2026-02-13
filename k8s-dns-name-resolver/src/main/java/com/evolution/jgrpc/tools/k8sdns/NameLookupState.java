package com.evolution.jgrpc.tools.k8sdns;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.xbill.DNS.Name;
import org.xbill.DNS.NameTooLongException;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.Type;
import org.xbill.DNS.lookup.LookupResult;
import org.xbill.DNS.lookup.LookupSession;

/**
 * Immutable component for performing DNS A-record lookups for a name.
 *
 * <p>Intended usage pattern:
 *
 * <ul>
 *   <li>create the initial state using {@link #initialize(Name) initialize}
 *   <li>to perform a lookup: {@link #runNextLookup() runNextLookup}
 *   <li>if successful, use {@link #getLastResult()} on the returned state object to get the result
 *   <li>save the returned state object for the next lookup
 *   <li>if a lookup fails, use the same state object for retries
 * </ul>
 *
 * <p>Search domains support quirks:
 *
 * <ul>
 *   <li>dnsjava 3.6.3 LookupSession has a bug that it doesn't take into account search domains by
 *       default - even if you configure it manually using <code>
 *       .searchPath(ResolverConfig.getCurrentConfig().searchPath())</code> on the LookupSession
 *       builder, it still fails in tests with CoreDNS because it aborts the search domains attempts
 *       process upon encountering an unexpected DNS error code, i.e. <code>
 *       "Unknown non-success error
 * code REFUSED"</code>
 *   <li>to mitigate the issue and support short k8s service domain names, this logic was
 *       reimplemented manually bypassing LookupSession search domains implementation
 *   <li>in the implemented logic, we cheated a bit for efficiency - upon finding the first absolute
 *       name for which we get a non-empty set of IP addresses, it is saved, and subsequent DNS
 *       queries are made against this absolute name, without doing multiple queries against search
 *       domains
 *   <li>regardless of the ndots setting, the name itself is tried for the DNS lookup first
 * </ul>
 *
 * @see LookupSession
 */
/* package */ final class NameLookupState {

  private final Name name;
  private final LookupSession session;
  private final List<InetAddress> lastResult;
  @Nullable private final Name discoveredAbsoluteName;

  public static NameLookupState initialize(Name name) {
    return new NameLookupState(name);
  }

  private NameLookupState(
      Name name,
      LookupSession session,
      List<InetAddress> lastResult,
      @Nullable Name discoveredAbsoluteName) {
    this.name = name;
    this.session = session;
    this.lastResult = lastResult;
    this.discoveredAbsoluteName = discoveredAbsoluteName;
  }

  private NameLookupState(Name name) {
    this.name = name;
    this.session =
        LookupSession.defaultBuilder()
            // disable cache, to make sure we always get the actual information
            .clearCaches()
            // disable search domains support just in case, we are reimplementing it here anyway
            // see the class doc for the "why"
            .clearSearchPath()
            .build();
    this.lastResult = Collections.emptyList();
    this.discoveredAbsoluteName = name.isAbsolute() ? name : null;
  }

  private NameLookupState copyWithDiscoveredAbsoluteName(
      Name discoveredAbsoluteName, List<InetAddress> newLastResult) {
    return new NameLookupState(this.name, this.session, newLastResult, discoveredAbsoluteName);
  }

  private NameLookupState copyWithResult(List<InetAddress> newLastResult) {
    return new NameLookupState(this.name, this.session, newLastResult, this.discoveredAbsoluteName);
  }

  public List<InetAddress> getLastResult() {
    return this.lastResult;
  }

  public CompletionStage<NameLookupState> runNextLookup() {
    if (discoveredAbsoluteName != null) {
      return lookupByAbsoluteName(discoveredAbsoluteName).thenApply(this::copyWithResult);
    } else {
      var primaryName = concatFailIfInvalid(name, Name.root);
      return lookupByAbsoluteName(primaryName)
          .handle(
              (addresses, err) -> {
                if (err == null && !addresses.isEmpty()) {
                  return CompletableFuture.completedFuture(
                      copyWithDiscoveredAbsoluteName(primaryName, addresses));
                } else {
                  var alternativeAbsoluteNames =
                      ResolverConfig.getCurrentConfig().searchPath().stream()
                          .flatMap(searchName -> concatEmptyIfInvalid(name, searchName).stream())
                          .toList();
                  return lookupAlternativeAbsoluteNames(addresses, err, alternativeAbsoluteNames);
                }
              })
          .thenCompose(Function.identity());
    }
  }

  private CompletionStage<NameLookupState> lookupAlternativeAbsoluteNames(
      List<InetAddress> primaryNameResultAddresses,
      @Nullable Throwable primaryNameResultError,
      List<Name> remainingAbsoluteNamesToTry) {
    if (remainingAbsoluteNamesToTry.isEmpty()) {
      if (primaryNameResultError != null) {
        return CompletableFuture.failedFuture(primaryNameResultError);
      } else {
        return CompletableFuture.completedFuture(copyWithResult(primaryNameResultAddresses));
      }
    } else {
      var curNameToTry = remainingAbsoluteNamesToTry.get(0);
      return lookupByAbsoluteName(curNameToTry)
          .handle(
              (addresses, err) -> {
                if (err == null && !addresses.isEmpty()) {
                  return CompletableFuture.completedFuture(
                      copyWithDiscoveredAbsoluteName(curNameToTry, addresses));
                } else {
                  var newRemainingAbsoluteNamesToTry =
                      remainingAbsoluteNamesToTry.stream().skip(1).toList();
                  return lookupAlternativeAbsoluteNames(
                      primaryNameResultAddresses,
                      primaryNameResultError,
                      newRemainingAbsoluteNamesToTry);
                }
              })
          .thenCompose(Function.identity());
    }
  }

  private CompletionStage<List<InetAddress>> lookupByAbsoluteName(Name name) {
    return session
        .lookupAsync(name, Type.A)
        .thenApply(
            (res) ->
                Optional.ofNullable(res).map(LookupResult::getRecords).orElse(List.of()).stream()
                    .map(Record::rdataToString)
                    .distinct()
                    .sorted() // make sure that result comparison does not depend on order
                    .map(InetAddresses::forString)
                    .toList());
  }

  // org.xbill.DNS.lookup.LookupSession.safeConcat
  private static Optional<Name> concatEmptyIfInvalid(Name name, Name suffix) {
    try {
      return Optional.of(Name.concatenate(name, suffix));
    } catch (NameTooLongException e) {
      return Optional.empty();
    }
  }

  private static Name concatFailIfInvalid(Name name, Name suffix) {
    try {
      return Name.concatenate(name, suffix);
    } catch (NameTooLongException e) {
      throw new RuntimeException(e);
    }
  }
}

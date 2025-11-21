package com.evolution.jgrpc.tools.k8sdns;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import java.net.URI;
import org.xbill.DNS.Name;
import org.xbill.DNS.TextParseException;

/* package */ record ParsedDnsTargetUri(String authority, Name host, String hostStr, int port) {

  /* package */ static ParsedDnsTargetUri parse(URI targetUri, int defaultPort) {
    try {
      var nameUri = targetUri;

      if (Strings.isNullOrEmpty(targetUri.getAuthority())) {
        // handle scheme:/// case
        var targetPath = requireNonNull(targetUri.getPath(), "missing path component");
        checkArgument(
            targetPath.startsWith("/"), "path component '%s' must start with '/'", targetPath);

        var name = targetPath.substring(1);
        nameUri = URI.create("//" + name);
      }

      var authority = requireNonNull(nameUri.getAuthority(), "missing authority");
      var hostStr = requireNonNull(nameUri.getHost(), "missing host");
      var host = parseHost(hostStr);
      var port = nameUri.getPort() == -1 ? defaultPort : nameUri.getPort();

      return new ParsedDnsTargetUri(authority, host, hostStr, port);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          format("invalid DNS target URI '%s': %s", targetUri, e.getMessage()), e);
    }
  }

  private static Name parseHost(String hostStr) {
    try {
      return Name.fromString(hostStr);
    } catch (TextParseException e) {
      throw new IllegalArgumentException(
          format("invalid host '%s': %s", hostStr, e.getMessage()), e);
    }
  }
}

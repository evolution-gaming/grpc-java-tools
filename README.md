# grpc-java-tools

[![Build Status](https://github.com/evolution-gaming/grpc-java-tools/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/evolution-gaming/grpc-java-tools/actions/workflows/ci.yml?query=branch%3Amain)
[![Maven Central Version](https://img.shields.io/maven-central/v/com.evolution.jgrpc.tools/k8s-dns-name-resolver)](https://central.sonatype.com/artifact/com.evolution.jgrpc.tools/k8s-dns-name-resolver)

A collection of Java libraries that supplement [grpc-java](https://github.com/grpc/grpc-java).

The minimum supported JDK version is 17.

## Table of Contents

- [Modules](#modules)
  - [k8s-dns-name-resolver](#k8s-dns-name-resolver)
- [For Contributors](#for-contributors)

## Modules

### k8s-dns-name-resolver

Provides a custom name resolver for working with headless Kubernetes services using DNS.

Compared to the grpc-java built-in DNS name resolver, this one properly supports on-the-fly updates
of the service pod list:

- The built-in DNS name resolver relies on the JVM's DNS query logic and its cache.
- If a new pod is added, it is not visible to the clients until a server closes one of the
  connections, or the JVM DNS cache entry expires.
- The JVM DNS cache (in JDK 17 and earlier, at least) does not honor DNS record TTL values and
  instead uses a globally configured TTL for expiration.
- So with the vanilla DNS name resolver, to discover a new pod quickly, you either need to set a
  very low global JVM DNS TTL, or use a very low connection TTL on the servers.
- Tampering with global JVM DNS settings might be undesirable because it could affect other
  networking code, such as code that calls external HTTP services.
- A low server connection TTL negates the benefits of long-lived HTTP/2 connections.

`K8sDnsNameResolver` avoids these issues by using [dnsjava](https://github.com/dnsjava/dnsjava)
to query DNS servers periodically (every 10 seconds by default) without using a DNS cache.

It is useful if you cannot use a Kubernetes API-based or an xDS API-based resolver for your
Kubernetes-deployed gRPC services.

#### Getting Started

Declare dependency on the library:
[Maven Central](https://central.sonatype.com/artifact/com.evolution.jgrpc.tools/k8s-dns-name-resolver)

`K8sDnsNameResolverProvider` is automatically discovered by the grpc-java runtime.

You can then use the `k8s-dns` scheme in your gRPC client channel builder:

```java
ManagedChannel channel = NettyChannelBuilder
    .forTarget("k8s-dns://my-svc.my-namespace.svc.my-cluster.local:8080")
    .build();
```

`K8sDnsNameResolver` supports almost the same target URI format as the standard `DnsNameResolver`:

- `k8s-dns://my-svc.my-namespace.svc.my-cluster.local` (default port)
- `k8s-dns:///my-svc.my-namespace.svc.my-cluster.local` (default port)
- `k8s-dns://my-svc.my-namespace.svc.my-cluster.local:8080`
- `k8s-dns:///my-svc.my-namespace.svc.my-cluster.local:8080`

## For Contributors

### Build Requirements

You will need:

- JDK 17
- [sbt](https://www.scala-sbt.org)

The published library modules are pure Java, but some of the test code is written in Scala.

### Useful SBT commands

To reformat Java and Scala code run:

```shell
sbt fmt
```

Code formatting is verified by the build commands, and pull requests with incorrectly formatted code
will not be accepted.

A full build can be run with:

```shell
sbt build
```

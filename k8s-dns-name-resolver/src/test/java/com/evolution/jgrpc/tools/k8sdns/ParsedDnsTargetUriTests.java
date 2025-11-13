package com.evolution.jgrpc.tools.k8sdns;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.Name;
import org.xbill.DNS.TextParseException;

class ParsedDnsTargetUriTests {

  @Test
  void parseCorrect() throws TextParseException {
    var expected =
        new ParsedDnsTargetUri(
            "foo.googleapis.com", Name.fromString("foo.googleapis.com"), "foo.googleapis.com", 42);
    var actual = ParsedDnsTargetUri.parse(URI.create("dns://foo.googleapis.com"), 42);
    assertEquals(expected, actual);
  }

  @Test
  void parseCorrectWithPort() throws TextParseException {
    var expected =
        new ParsedDnsTargetUri(
            "foo.googleapis.com:8080",
            Name.fromString("foo.googleapis.com"),
            "foo.googleapis.com",
            8080);
    var actual = ParsedDnsTargetUri.parse(URI.create("dns://foo.googleapis.com:8080"), 42);
    assertEquals(expected, actual);
  }

  @Test
  void parseCorrectWithExtraSlash() throws TextParseException {
    var expected =
        new ParsedDnsTargetUri(
            "foo.googleapis.com", Name.fromString("foo.googleapis.com"), "foo.googleapis.com", 42);
    var actual = ParsedDnsTargetUri.parse(URI.create("dns:///foo.googleapis.com"), 42);
    assertEquals(expected, actual);
  }

  @Test
  void parseCorrectWithExtraSlashAndPort() throws TextParseException {
    var expected =
        new ParsedDnsTargetUri(
            "foo.googleapis.com:8080",
            Name.fromString("foo.googleapis.com"),
            "foo.googleapis.com",
            8080);
    var actual = ParsedDnsTargetUri.parse(URI.create("dns:///foo.googleapis.com:8080"), 42);
    assertEquals(expected, actual);
  }
}

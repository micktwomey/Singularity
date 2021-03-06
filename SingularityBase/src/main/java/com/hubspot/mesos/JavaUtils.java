package com.hubspot.mesos;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.DurationFormatUtils;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hubspot.jackson.datatype.protobuf.ProtobufModule;

public final class JavaUtils {

  public static final String LOGBACK_LOGGING_PATTERN = "%-5level [%d] [%.15thread] %logger{35} - %msg%n";

  public static String urlEncode(String string) {
    try {
      return URLEncoder.encode(string, UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw Throwables.propagate(e);
    }
  }

  public static String urlDecode(String string) {
    try {
      return URLDecoder.decode(string, UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw Throwables.propagate(e);
    }
  }

  public static String[] reverseSplit(String string, int numItems, String separator) {
    final String[] splits = string.split("\\" + separator);

    Preconditions.checkState(splits.length >= numItems, "There must be at least %s instances of %s (there were %s)", numItems - 1, separator, splits.length - 1);

    final String[] reverseSplit = new String[numItems];

    for (int i = 1; i < numItems; i++) {
      reverseSplit[numItems - i] = splits[splits.length - i];
    }

    final StringBuilder lastItemBldr = new StringBuilder();

    for (int s = 0; s < splits.length - numItems + 1; s++) {
      lastItemBldr.append(splits[s]);
      if (s < splits.length - numItems) {
        lastItemBldr.append(separator);
      }
    }

    reverseSplit[0] = lastItemBldr.toString();

    return reverseSplit;
  }

  public static boolean isHttpSuccess(int statusCode) {
    return statusCode >= 200 && statusCode < 300;
  }

  public static String getHostAddress() throws SocketException {
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
    while (interfaces.hasMoreElements()) {
      NetworkInterface current = interfaces.nextElement();
      if (!current.isUp() || current.isLoopback() || current.isVirtual()) {
        continue;
      }
      Enumeration<InetAddress> addresses = current.getInetAddresses();
      while (addresses.hasMoreElements()) {
        InetAddress currentAddr = addresses.nextElement();
        if (currentAddr.isLoopbackAddress()) {
          continue;
        }
        if (currentAddr instanceof Inet4Address) {
          return currentAddr.getHostAddress();
        }
      }
    }
    throw new RuntimeException("Couldn't deduce host address");
  }

  public static Optional<String> getHostName() {
    try {
      InetAddress addr = InetAddress.getLocalHost();

      String hostname = addr.getHostName();

      return Optional.fromNullable(hostname);
    } catch (Throwable t) {
      return Optional.absent();
    }
  }

  private static final String DURATION_FORMAT = "mm:ss.S";

  public static String duration(final long start) {
    return DurationFormatUtils.formatDuration(System.currentTimeMillis() - start, DURATION_FORMAT);
  }

  public static String durationFromMillis(final long millis) {
    return DurationFormatUtils.formatDuration(millis, DURATION_FORMAT);
  }

  public static Thread awaitTerminationWithLatch(final CountDownLatch latch, final String threadNameSuffix, final ExecutorService service, final long millis) {
    Thread t = new Thread("ExecutorServiceTerminationWaiter-" + threadNameSuffix) {
      @Override
      public void run() {
        try {
          service.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
        } finally {
          latch.countDown();
        }
      }
    };

    t.start();

    return t;
  }

  public static Iterable<Path> iterable(final Path directory) {
    try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(directory);) {
      Iterator<Path> iterator = dirStream.iterator();
      return Lists.newArrayList(iterator);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  public static Path getValidDirectory(String directoryPath, String name) {
    Preconditions.checkState(!directoryPath.isEmpty(), "Path for %s can't be empty", name);

    Path path = Paths.get(directoryPath);

    Preconditions.checkState(Files.isDirectory(path), "Path %s for %s wasn't a directory", path, name);

    return path;
  }

  public static <K, V> Map<K, V> nonNullImmutable(Map<K, V> map) {
    if (map == null) {
      return Collections.emptyMap();
    }
    return ImmutableMap.copyOf(map);
  }

  public static <T> List<T> nonNullImmutable(List<T> list) {
    if (list == null) {
      return Collections.emptyList();
    }
    return ImmutableList.copyOf(list);
  }

  public static <T> Optional<T> getFirst(Iterable<T> iterable) {
    return Optional.fromNullable(Iterables.getFirst(iterable, null));
  }

  public static <T> Optional<T> getLast(Iterable<T> iterable) {
    return Optional.fromNullable(Iterables.getLast(iterable, null));
  }

  public static ObjectMapper newObjectMapper() {
    final ObjectMapper mapper = new ObjectMapper();
    mapper.setSerializationInclusion(Include.NON_NULL);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    mapper.registerModule(new GuavaModule());
    mapper.registerModule(new ProtobufModule());
    return mapper;
  }

  public static ThreadPoolExecutor newFixedTimingOutThreadPool(int maxThreads, long timeoutMillis, String nameFormat) {
    ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(maxThreads, maxThreads, timeoutMillis, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactoryBuilder().setNameFormat(nameFormat).build());
    threadPoolExecutor.allowCoreThreadTimeOut(true);
    return threadPoolExecutor;
  }

}

/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.prometheus;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import io.github.netmikey.logunit.api.LogCapturer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.internal.testing.slf4j.SuppressLogger;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData;
import io.opentelemetry.sdk.resources.Resource;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class PrometheusHttpServerTest {
  private static final AtomicReference<List<MetricData>> metricData = new AtomicReference<>();

  @SuppressWarnings("NonFinalStaticField")
  static PrometheusHttpServer prometheusServer;

  @SuppressWarnings("NonFinalStaticField")
  static WebClient client;

  @RegisterExtension
  LogCapturer logs = LogCapturer.create().captureForType(Otel2PrometheusConverter.class);

  @BeforeAll
  static void beforeAll() {
    // Register the SDK metric producer with the prometheus reader.
    prometheusServer = PrometheusHttpServer.builder().setHost("localhost").setPort(0).build();
    prometheusServer.register(
        new CollectionRegistration() {
          @Override
          public Collection<MetricData> collectAllMetrics() {
            return metricData.get();
          }
        });

    client =
        WebClient.builder("http://localhost:" + prometheusServer.getAddress().getPort())
            .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
            .build();
  }

  @BeforeEach
  void beforeEach() {
    metricData.set(generateTestData());
  }

  @AfterAll
  static void tearDown() {
    prometheusServer.shutdown();
  }

  @Test
  void invalidConfig() {
    assertThatThrownBy(() -> PrometheusHttpServer.builder().setPort(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("port must be positive");
    assertThatThrownBy(() -> PrometheusHttpServer.builder().setHost(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("host");
    assertThatThrownBy(() -> PrometheusHttpServer.builder().setHost(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("host must not be empty");
  }

  @Test
  void fetchPrometheus() {
    AggregatedHttpResponse response = client.get("/metrics").aggregate().join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.contentUtf8())
        .isEqualTo(
            "# HELP grpc_name_unit_total long_description\n"
                + "# TYPE grpc_name_unit_total counter\n"
                + "grpc_name_unit_total{kp=\"vp\",otel_scope_name=\"grpc\",otel_scope_version=\"version\"} 5.0\n"
                + "# HELP http_name_unit_total double_description\n"
                + "# TYPE http_name_unit_total counter\n"
                + "http_name_unit_total{kp=\"vp\",otel_scope_name=\"http\",otel_scope_version=\"version\"} 3.5\n"
                + "# TYPE target_info gauge\n"
                + "target_info{kr=\"vr\"} 1\n");
  }

  @Test
  void fetchOpenMetrics() {
    AggregatedHttpResponse response =
        client
            .execute(
                RequestHeaders.of(
                    HttpMethod.GET,
                    "/metrics",
                    HttpHeaderNames.ACCEPT,
                    "application/openmetrics-text"))
            .aggregate()
            .join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("application/openmetrics-text; version=1.0.0; charset=utf-8");
    assertThat(response.contentUtf8())
        .isEqualTo(
            "# TYPE grpc_name_unit counter\n"
                + "# UNIT grpc_name_unit unit\n"
                + "# HELP grpc_name_unit long_description\n"
                + "grpc_name_unit_total{kp=\"vp\",otel_scope_name=\"grpc\",otel_scope_version=\"version\"} 5.0\n"
                + "# TYPE http_name_unit counter\n"
                + "# UNIT http_name_unit unit\n"
                + "# HELP http_name_unit double_description\n"
                + "http_name_unit_total{kp=\"vp\",otel_scope_name=\"http\",otel_scope_version=\"version\"} 3.5\n"
                + "# TYPE target info\n"
                + "target_info{kr=\"vr\"} 1\n"
                + "# EOF\n");
  }

  @Test
  void fetchFiltered() {
    AggregatedHttpResponse response =
        client
            .get("/?name[]=grpc_name_unit_total&name[]=bears_total&name[]=target_info")
            .aggregate()
            .join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.contentUtf8())
        .isEqualTo(
            ""
                + "# HELP grpc_name_unit_total long_description\n"
                + "# TYPE grpc_name_unit_total counter\n"
                + "grpc_name_unit_total{kp=\"vp\",otel_scope_name=\"grpc\",otel_scope_version=\"version\"} 5.0\n"
                + "# TYPE target_info gauge\n"
                + "target_info{kr=\"vr\"} 1\n");
  }

  @Test
  void fetchPrometheusCompressed() throws IOException {
    WebClient client =
        WebClient.builder("http://localhost:" + prometheusServer.getAddress().getPort())
            .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
            .addHeader(HttpHeaderNames.ACCEPT_ENCODING, "gzip")
            .build();
    AggregatedHttpResponse response = client.get("/metrics").aggregate().join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
    GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(response.content().array()));
    String content = new String(ByteStreams.toByteArray(gis), StandardCharsets.UTF_8);
    assertThat(content)
        .isEqualTo(
            "# HELP grpc_name_unit_total long_description\n"
                + "# TYPE grpc_name_unit_total counter\n"
                + "grpc_name_unit_total{kp=\"vp\",otel_scope_name=\"grpc\",otel_scope_version=\"version\"} 5.0\n"
                + "# HELP http_name_unit_total double_description\n"
                + "# TYPE http_name_unit_total counter\n"
                + "http_name_unit_total{kp=\"vp\",otel_scope_name=\"http\",otel_scope_version=\"version\"} 3.5\n"
                + "# TYPE target_info gauge\n"
                + "target_info{kr=\"vr\"} 1\n");
  }

  @Test
  void fetchHead() {
    AggregatedHttpResponse response = client.head("/").aggregate().join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.content().isEmpty()).isTrue();
  }

  @Test
  void fetchHealth() {
    AggregatedHttpResponse response = client.get("/-/healthy").aggregate().join();

    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.contentUtf8()).isEqualTo("Exporter is healthy.\n");
  }

  @Test
  @SuppressLogger(PrometheusHttpServer.class)
  void fetch_DuplicateMetrics() {
    Resource resource = Resource.create(Attributes.of(stringKey("kr"), "vr"));
    metricData.set(
        ImmutableList.of(
            ImmutableMetricData.createLongSum(
                resource,
                InstrumentationScopeInfo.create("scope1"),
                "foo",
                "description1",
                "unit",
                ImmutableSumData.create(
                    /* isMonotonic= */ true,
                    AggregationTemporality.CUMULATIVE,
                    Collections.singletonList(
                        ImmutableLongPointData.create(123, 456, Attributes.empty(), 1)))),
            // A counter with same name as foo but with different scope, description, unit
            ImmutableMetricData.createLongSum(
                resource,
                InstrumentationScopeInfo.create("scope2"),
                "foo",
                "description2",
                "unit",
                ImmutableSumData.create(
                    /* isMonotonic= */ true,
                    AggregationTemporality.CUMULATIVE,
                    Collections.singletonList(
                        ImmutableLongPointData.create(123, 456, Attributes.empty(), 2)))),
            // A gauge whose name conflicts with foo counter's name after the "_total" suffix is
            // added
            ImmutableMetricData.createLongGauge(
                resource,
                InstrumentationScopeInfo.create("scope3"),
                "foo_unit_total",
                "unused",
                "",
                ImmutableGaugeData.create(
                    Collections.singletonList(
                        ImmutableLongPointData.create(123, 456, Attributes.empty(), 3))))));

    AggregatedHttpResponse response = client.get("/metrics").aggregate().join();
    assertThat(response.status()).isEqualTo(HttpStatus.OK);
    assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
        .isEqualTo("text/plain; version=0.0.4; charset=utf-8");
    assertThat(response.contentUtf8())
        .isEqualTo(
            "# TYPE foo_unit_total counter\n"
                + "foo_unit_total{otel_scope_name=\"scope1\"} 1.0\n"
                + "foo_unit_total{otel_scope_name=\"scope2\"} 2.0\n"
                + "# TYPE target_info gauge\n"
                + "target_info{kr=\"vr\"} 1\n");

    // Validate conflict warning message
    assertThat(logs.getEvents()).hasSize(1);
    logs.assertContains(
        "Conflicting metrics: Multiple metrics with name foo_unit but different units found. Dropping the one with unit null.");
  }

  @Test
  void stringRepresentation() {
    assertThat(prometheusServer.toString())
        .isEqualTo("PrometheusHttpServer{address=" + prometheusServer.getAddress() + "}");
  }

  @Test
  void defaultExecutor() {
    assertThat(prometheusServer)
        .extracting("httpServer", as(InstanceOfAssertFactories.type(HTTPServer.class)))
        .extracting("executorService", as(InstanceOfAssertFactories.type(ThreadPoolExecutor.class)))
        .satisfies(executor -> assertThat(executor.getCorePoolSize()).isEqualTo(1));
  }

  @Test
  void customExecutor() throws IOException {
    ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(10);
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    try (PrometheusHttpServer server =
        PrometheusHttpServer.builder()
            .setHost("localhost")
            .setPort(port)
            .setExecutor(scheduledExecutor)
            .build()) {
      assertThat(server)
          .extracting("httpServer", as(InstanceOfAssertFactories.type(HTTPServer.class)))
          .extracting(
              "executorService",
              as(InstanceOfAssertFactories.type(ScheduledThreadPoolExecutor.class)))
          .satisfies(executor -> assertThat(executor).isSameAs(scheduledExecutor));
    }
  }

  private static List<MetricData> generateTestData() {
    return ImmutableList.of(
        ImmutableMetricData.createLongSum(
            Resource.create(Attributes.of(stringKey("kr"), "vr")),
            InstrumentationScopeInfo.builder("grpc").setVersion("version").build(),
            "grpc.name",
            "long_description",
            "unit",
            ImmutableSumData.create(
                /* isMonotonic= */ true,
                AggregationTemporality.CUMULATIVE,
                Collections.singletonList(
                    ImmutableLongPointData.create(
                        123, 456, Attributes.of(stringKey("kp"), "vp"), 5)))),
        ImmutableMetricData.createDoubleSum(
            Resource.create(Attributes.of(stringKey("kr"), "vr")),
            InstrumentationScopeInfo.builder("http").setVersion("version").build(),
            "http.name",
            "double_description",
            "unit",
            ImmutableSumData.create(
                /* isMonotonic= */ true,
                AggregationTemporality.CUMULATIVE,
                Collections.singletonList(
                    ImmutableDoublePointData.create(
                        123, 456, Attributes.of(stringKey("kp"), "vp"), 3.5)))));
  }
}

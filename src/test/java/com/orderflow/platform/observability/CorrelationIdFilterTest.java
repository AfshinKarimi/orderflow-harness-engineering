package com.orderflow.platform.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> seenByRequest = new AtomicReference<>();

    @Test
    void createsAnIdWhenTheCallerSentNone() throws Exception {
        run(new MockHttpServletRequest());

        assertThat(seenByRequest.get()).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(CorrelationId.HTTP_HEADER)).isEqualTo(seenByRequest.get());
    }

    @Test
    void keepsAWellFormedCallerId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HTTP_HEADER, "client-req_42");

        run(request);

        assertThat(seenByRequest.get()).isEqualTo("client-req_42");
        assertThat(response.getHeader(CorrelationId.HTTP_HEADER)).isEqualTo("client-req_42");
    }

    @Test
    void replacesAnIdThatCouldForgeLogLines() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HTTP_HEADER, "abc\n2026-10-30 ERROR forged line");

        run(request);

        assertThat(seenByRequest.get()).doesNotContain("forged").matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesAnOverlongId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HTTP_HEADER, "a".repeat(65));

        run(request);

        assertThat(seenByRequest.get()).hasSize(36);
    }

    @Test
    void clearsTheLoggingContextAfterTheRequest() throws Exception {
        run(new MockHttpServletRequest());

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    private void run(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, response, (req, res) -> seenByRequest.set(MDC.get(CorrelationId.MDC_KEY)));
    }
}

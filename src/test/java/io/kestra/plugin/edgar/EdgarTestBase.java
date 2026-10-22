package io.kestra.plugin.edgar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.kestra.core.runners.RunContextFactory;

import jakarta.inject.Inject;

/**
 * Starts a WireMock server standing in for SEC, so no test reaches the real API.
 */
public abstract class EdgarTestBase {
    public static final String USER_AGENT = "Example Co admin@example.com";

    @Inject
    protected RunContextFactory runContextFactory;

    protected WireMockServer sec;

    @BeforeEach
    void startSec() {
        sec = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        sec.start();
    }

    @AfterEach
    void stopSec() {
        sec.stop();
    }

    protected static String fixture(String name) throws IOException {
        try (var input = Objects.requireNonNull(EdgarTestBase.class.getClassLoader().getResourceAsStream("fixtures/" + name), name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

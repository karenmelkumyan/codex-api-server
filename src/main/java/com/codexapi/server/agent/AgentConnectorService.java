package com.codexapi.server.agent;

import com.codexapi.server.config.AgentConfig;
import com.codexapi.server.config.Config;
import com.codexapi.server.codex.CodexService;
import com.codexapi.server.history.HistoryService;
import com.codexapi.server.process.ProcessRunner;
import com.codexapi.server.session.SessionService;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class AgentConnectorService implements AgentConnector {
    private static final String AGENT_TYPE = "codex";

    private final Config config;
    private final Supplier<AgentStateStore> stateStoreFactory;
    private final Supplier<BridgeAgentClient> bridgeClientFactory;
    private final Supplier<Map<String, Object>> capabilitiesSupplier;
    private final BiConsumer<AgentStateStore, AgentState> webSocketStarter;
    private final AgentJobHandler jobHandler;
    private final PrintStream output;
    private final Clock clock;
    private AgentWebSocketClient webSocketClient;
    private AgentConnectorStatus status;

    public AgentConnectorService(Config config) {
        this(config, new ProcessRunner(), new SessionService(config));
    }

    public AgentConnectorService(Config config, ProcessRunner processRunner, SessionService sessionService) {
        this(
                config,
                () -> new AgentStateStore(config.agent().stateFile()),
                () -> new BridgeAgentClient(config.agent()),
                () -> new AgentCapabilitiesProvider(config, processRunner).capabilities(),
                System.out,
                Clock.systemUTC(),
                null,
                new AgentJobHandler(
                        config,
                        sessionService,
                        new CodexService(
                                config,
                                processRunner,
                                sessionService,
                                new HistoryService(sessionService.store())
                        )
                )
        );
    }

    AgentConnectorService(
            Config config,
            Supplier<AgentStateStore> stateStoreFactory,
            Supplier<BridgeAgentClient> bridgeClientFactory,
            Supplier<Map<String, Object>> capabilitiesSupplier,
            PrintStream output,
            Clock clock
    ) {
        this(config, stateStoreFactory, bridgeClientFactory, capabilitiesSupplier, output, clock, (store, state) -> {
        }, AgentJobHandler.notConfigured());
    }

    AgentConnectorService(
            Config config,
            Supplier<AgentStateStore> stateStoreFactory,
            Supplier<BridgeAgentClient> bridgeClientFactory,
            Supplier<Map<String, Object>> capabilitiesSupplier,
            PrintStream output,
            Clock clock,
            BiConsumer<AgentStateStore, AgentState> webSocketStarter,
            AgentJobHandler jobHandler
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.stateStoreFactory = Objects.requireNonNull(stateStoreFactory, "stateStoreFactory");
        this.bridgeClientFactory = Objects.requireNonNull(bridgeClientFactory, "bridgeClientFactory");
        this.capabilitiesSupplier = Objects.requireNonNull(capabilitiesSupplier, "capabilitiesSupplier");
        this.output = Objects.requireNonNull(output, "output");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.webSocketStarter = webSocketStarter == null ? this::startWebSocket : webSocketStarter;
        this.jobHandler = Objects.requireNonNull(jobHandler, "jobHandler");
        this.status = AgentConnectorStatus.disabled();
    }

    public synchronized AgentConnectorStatus start() {
        AgentConfig agentConfig = config.agent();
        if (!agentConfig.enabled()) {
            status = AgentConnectorStatus.disabled();
            return status;
        }

        Optional<String> inactiveReason = agentConfig.inactiveReason();
        if (inactiveReason.isPresent()) {
            status = AgentConnectorStatus.inactive(inactiveReason.get());
            print("codex connector inactive: " + inactiveReason.get());
            return status;
        }

        try {
            status = startActiveConnector(agentConfig);
            return status;
        } catch (AgentStateStoreException exception) {
            String message = safeMessage(exception);
            print("codex connector inactive: " + message);
            status = AgentConnectorStatus.inactive(true, message, agentConfig.bridgeBaseUrl().orElse(null), null);
            return status;
        } catch (RuntimeException exception) {
            String message = safeMessage(exception);
            print("codex connector startup failed: " + message);
            status = AgentConnectorStatus.inactive(false, message, agentConfig.bridgeBaseUrl().orElse(null), null);
            return status;
        }
    }

    @Override
    public synchronized AgentConnectorStatus status() {
        return status;
    }

    @Override
    public synchronized AgentPairingCodeResponse requestPairingCode() {
        AgentConfig agentConfig = config.agent();
        if (!agentConfig.enabled()) {
            throw new AgentConnectorException(
                    409,
                    "AGENT_CONNECTOR_DISABLED",
                    "Connector mode is disabled."
            );
        }
        Optional<String> inactiveReason = agentConfig.inactiveReason();
        if (inactiveReason.isPresent()) {
            throw new AgentConnectorException(
                    409,
                    "AGENT_CONNECTOR_INACTIVE",
                    inactiveReason.get()
            );
        }

        try {
            AgentStateStore stateStore = stateStoreFactory.get();
            AgentState state = stateStore.get().orElseThrow(() -> new AgentConnectorException(
                    409,
                    "AGENT_STATE_NOT_FOUND",
                    "No agent state found. Start connector mode with auto-bootstrap enabled or delete/reset state."
            ));
            Optional<String> bridgeMismatch = storedBridgeMismatchMessage(stateStore, state, agentConfig);
            if (bridgeMismatch.isPresent()) {
                throw new AgentConnectorException(409, "AGENT_BRIDGE_MISMATCH", bridgeMismatch.get());
            }
            return requestPairingCodeFromBridge(state, agentConfig);
        } catch (AgentConnectorException exception) {
            throw exception;
        } catch (AgentStateStoreException exception) {
            throw new AgentConnectorException(409, "AGENT_STATE_INVALID", safeMessage(exception), exception);
        } catch (BridgeAgentClientException exception) {
            throw new AgentConnectorException(502, "BRIDGE_REQUEST_FAILED", safeMessage(exception), exception);
        }
    }

    private AgentConnectorStatus startActiveConnector(AgentConfig agentConfig) {
        AgentStateStore stateStore = stateStoreFactory.get();
        Optional<AgentState> existingState = stateStore.get();

        if (existingState.isPresent()) {
            AgentState state = existingState.get();
            Optional<String> bridgeMismatch = storedBridgeMismatchMessage(stateStore, state, agentConfig);
            if (bridgeMismatch.isPresent()) {
                String message = bridgeMismatch.get();
                print("codex connector inactive: " + message);
                return AgentConnectorStatus.inactive(true, message, bridgeBaseUrl(agentConfig), state.agentId());
            }

            AgentConnectorStatus readyStatus = AgentConnectorStatus.ready(
                    state,
                    "Connector identity loaded from state file."
            );
            if (agentConfig.pairOnStart()) {
                readyStatus = readyStatus.withPairingCode(requestAndPrintPairingCode(state, agentConfig));
            }
            status = readyStatus;
            webSocketStarter.accept(stateStore, state);
            return status;
        }

        if (!agentConfig.autoBootstrap()) {
            String message = "No agent state found and CODEX_AGENT_AUTO_BOOTSTRAP=false.";
            print("codex connector inactive: " + message);
            return AgentConnectorStatus.inactive(false, message, bridgeBaseUrl(agentConfig), null);
        }

        AgentState state = bootstrapState(stateStore, agentConfig);
        AgentConnectorStatus readyStatus = AgentConnectorStatus.ready(state, "Connector identity bootstrapped.");

        if (agentConfig.autoPairOnFirstBootstrap() || agentConfig.pairOnStart()) {
            readyStatus = readyStatus.withPairingCode(requestAndPrintPairingCode(state, agentConfig));
        }
        status = readyStatus;
        webSocketStarter.accept(stateStore, state);
        return status;
    }

    private synchronized void startWebSocket(AgentStateStore stateStore, AgentState state) {
        webSocketClient = new AgentWebSocketClient(
                config.agent(),
                stateStore,
                capabilitiesSupplier,
                new AgentWebSocketClient.StatusListener() {
                    @Override
                    public void connected(String timestamp) {
                        markWebSocketConnected(timestamp);
                    }

                    @Override
                    public void disconnected(String reason, String timestamp) {
                        markWebSocketDisconnected(reason, timestamp);
                    }

                    @Override
                    public void seen(String timestamp) {
                        markWebSocketSeen(timestamp);
                    }
                },
                jobHandler
        );
        webSocketClient.start(state);
    }

    private synchronized void markWebSocketConnected(String timestamp) {
        status = status.withConnected(timestamp);
    }

    private synchronized void markWebSocketDisconnected(String reason, String timestamp) {
        status = status.withDisconnected(reason, timestamp);
    }

    private synchronized void markWebSocketSeen(String timestamp) {
        status = status.withSeen(timestamp);
    }

    private AgentState bootstrapState(AgentStateStore stateStore, AgentConfig agentConfig) {
        AgentBootstrapResponse response = bridgeClientFactory.get().bootstrap(
                AGENT_TYPE,
                agentConfig.displayName(),
                agentConfig.clientVersion(),
                capabilitiesSupplier.get()
        );
        validateBootstrapResponse(response);

        AgentState state = new AgentState(
                response.agentId(),
                response.agentSecret(),
                blankToDefault(response.agentType(), AGENT_TYPE),
                bridgeBaseUrl(agentConfig),
                response.relayWebSocketUrl(),
                blankToDefault(response.displayName(), agentConfig.displayName()),
                blankToDefault(response.createdAt(), Instant.now(clock).toString()),
                null
        );

        stateStore.save(state);
        print("codex connector bootstrapped local agent " + state.agentId() + ".");
        return state;
    }

    private AgentPairingCodeResponse requestAndPrintPairingCode(AgentState state, AgentConfig agentConfig) {
        AgentPairingCodeResponse response = requestPairingCodeFromBridge(state, agentConfig);

        if (response.connectUrl() != null && !response.connectUrl().isBlank()) {
            print("codex connector pairing URL: " + response.connectUrl());
        }
        print("codex connector pairing code: " + response.pairingCode());
        if (response.expiresAt() != null && !response.expiresAt().isBlank()) {
            print("codex connector pairing expires at: " + response.expiresAt());
        }
        return response;
    }

    private AgentPairingCodeResponse requestPairingCodeFromBridge(AgentState state, AgentConfig agentConfig) {
        AgentPairingCodeResponse response = bridgeClientFactory.get().createPairingCode(state, agentConfig.displayName());
        validatePairingCodeResponse(response);
        return response;
    }

    private Optional<String> storedBridgeMismatchMessage(
            AgentStateStore stateStore,
            AgentState state,
            AgentConfig agentConfig
    ) {
        String storedBridge = normalizeBridgeBaseUrl(state.bridgeBaseUrl());
        String configuredBridge = bridgeBaseUrl(agentConfig);
        if (!storedBridge.equals(configuredBridge)) {
            return Optional.of(
                    "Stored connector state is for bridge '" + storedBridge
                            + "' but CODEX_AGENT_BRIDGE_BASE_URL is '" + configuredBridge
                            + "'. Delete " + stateStore.stateFile() + " to pair with a different bridge."
            );
        }
        return Optional.empty();
    }

    private void validateBootstrapResponse(AgentBootstrapResponse response) {
        if (!response.ok()) {
            throw new BridgeAgentClientException("Bridge bootstrap response was not ok.");
        }
        requireResponseField(response.agentId(), "agentId");
        if (response.agentSecret() == null || response.agentSecret().isBlank()) {
            throw new BridgeAgentClientException("Bridge bootstrap response missing required secret.");
        }
        requireResponseField(response.relayWebSocketUrl(), "relayWebSocketUrl");
    }

    private void validatePairingCodeResponse(AgentPairingCodeResponse response) {
        if (!response.ok()) {
            throw new BridgeAgentClientException("Bridge pairing-code response was not ok.");
        }
        requireResponseField(response.pairingCode(), "pairingCode");
    }

    private void requireResponseField(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BridgeAgentClientException("Bridge response missing " + field + ".");
        }
    }

    private String bridgeBaseUrl(AgentConfig agentConfig) {
        return agentConfig.bridgeBaseUrl()
                .map(AgentConnectorService::normalizeBridgeBaseUrl)
                .orElseThrow(() -> new BridgeAgentClientException("CODEX_AGENT_BRIDGE_BASE_URL is not configured."));
    }

    private static String normalizeBridgeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private void print(String message) {
        output.println(message);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }
}

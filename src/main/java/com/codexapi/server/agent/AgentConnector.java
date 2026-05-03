package com.codexapi.server.agent;

public interface AgentConnector {
    AgentConnectorStatus status();

    AgentPairingCodeResponse requestPairingCode();
}

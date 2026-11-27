package com.gdou.agent.infra.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FileTransfer(
        @JsonProperty("fileName")
        String fileName,
        @JsonProperty("from")
        String from,
        @JsonProperty("to")
        String to
) {
}

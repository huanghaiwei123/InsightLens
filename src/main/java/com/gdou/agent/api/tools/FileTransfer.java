package com.gdou.agent.api.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FileTransfer(
        @JsonProperty("fileName")
        String fileName,
        @JsonProperty("from")
        String from,
        @JsonProperty("to")
        String to,
        @JsonProperty("uuid")
        String uuid
) {
}

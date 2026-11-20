package com.gdou.agent.api.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record FileAction (
        @JsonProperty("transfers")
    List<FileTransfer> transfers,
        @JsonProperty("delete")
    String[] delete,
        @JsonProperty("create")
    String[] create
) {}

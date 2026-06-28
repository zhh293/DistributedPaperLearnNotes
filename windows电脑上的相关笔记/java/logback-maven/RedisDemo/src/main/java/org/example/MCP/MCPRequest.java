package org.example.MCP;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MCPRequest {
    private String id;
    private String method;
    private Map<String, String> params;
    private String timeout;
}

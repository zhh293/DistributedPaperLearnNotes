package org.example.MCP;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MCPResponse {
    private String id;
    private String status;
    private Map<String,String> result;
    private String error;
}

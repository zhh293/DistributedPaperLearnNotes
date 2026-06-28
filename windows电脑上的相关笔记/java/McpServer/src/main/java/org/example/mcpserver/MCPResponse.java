package org.example.mcpserver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MCPResponse {
    private String id;
    private String status;
    private List<Map<String,String>>result;
    private String error;
}

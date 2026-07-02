package com.microsoft.mcp.sample.server.client;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.web.reactive.function.client.WebClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.Map;

public class SDKClient {

    public static void main(String[] args) {
        var transport = new WebFluxSseClientTransport(WebClient.builder().baseUrl("http://localhost:8081"));
        new SDKClient(transport).run();
    }

    private final McpClientTransport transport;

    public SDKClient(McpClientTransport transport) {
        this.transport = transport;
    }

    public void run() {
        var client = McpClient.sync(this.transport).build();
        client.initialize();

        McpSchema.ListToolsResult toolsList = client.listTools();
        System.out.println("Available Tools = " + toolsList);

        client.ping();

        McpSchema.CallToolResult resultAdd = client.callTool(new McpSchema.CallToolRequest("add", Map.of("a", 5.0, "b", 3.0)));
        System.out.println("Add Result = " + resultAdd);

        McpSchema.CallToolResult resultSubtract = client.callTool(new McpSchema.CallToolRequest("subtract", Map.of("a", 10.0, "b", 4.0)));
        System.out.println("Subtract Result = " + resultSubtract);

        McpSchema.CallToolResult resultMultiply = client.callTool(new McpSchema.CallToolRequest("multiply", Map.of("a", 6.0, "b", 7.0)));
        System.out.println("Multiply Result = " + resultMultiply);

        McpSchema.CallToolResult resultDivide = client.callTool(new McpSchema.CallToolRequest("divide", Map.of("a", 20.0, "b", 4.0)));
        System.out.println("Divide Result = " + resultDivide);

        McpSchema.CallToolResult resultHelp = client.callTool(new McpSchema.CallToolRequest("help", Map.of()));
        System.out.println("Help = " + resultHelp);
    }
}

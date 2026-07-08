package com.microsoft.mcp.sample.server.client;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.web.reactive.function.client.WebClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.Map;

/**
 * Cliente MCP de ejemplo (patron 03-GettingStarted/02-client) apuntado a las
 * herramientas de migracion Selenium -> Playwright de este servidor.
 *
 * <p>Uso: {@code ./gradlew runSdkClient [-PscanPath=C:/ruta/al/proyecto-selenium]}
 * (el servidor debe estar corriendo: {@code ./gradlew bootRun}). Sin argumento,
 * escanea el directorio actual.
 */
public class SDKClient {

    public static void main(String[] args) {
        String projectPath = args.length > 0 ? args[0] : ".";
        var transport = new WebFluxSseClientTransport(WebClient.builder().baseUrl("http://localhost:8081"));
        new SDKClient(transport).run(projectPath);
    }

    private final McpClientTransport transport;

    public SDKClient(McpClientTransport transport) {
        this.transport = transport;
    }

    public void run(String projectPath) {
        var client = McpClient.sync(this.transport).build();
        client.initialize();

        McpSchema.ListToolsResult toolsList = client.listTools();
        System.out.println("Available Tools = " + toolsList);

        client.ping();

        // scanSeleniumProject es de solo lectura: inventario de migracion del proyecto destino
        McpSchema.CallToolResult scanResult = client.callTool(
                new McpSchema.CallToolRequest("scanSeleniumProject", Map.of("projectPath", projectPath)));
        System.out.println("Scan Result = " + scanResult);
    }
}

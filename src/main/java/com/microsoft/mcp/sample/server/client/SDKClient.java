package com.microsoft.mcp.sample.server.client;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.web.reactive.function.client.WebClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.time.Duration;
import java.util.Map;

/**
 * Cliente MCP de consola (patron 03-GettingStarted/02-client de mcp-for-beginners)
 * apuntado a las herramientas de migracion Selenium -> Playwright de este servidor.
 *
 * <p>Permite ejecutar el flujo completo de migracion desde consola, sin LLM
 * (el servidor debe estar corriendo: {@code ./gradlew bootRun}):
 * <pre>
 *   ./gradlew runSdkClient -PscanPath=C:/ruta/al/proyecto     (inventario, solo lectura)
 *   ./gradlew runSdkClient -PconvertFile=C:/ruta/A/Page.java  (migra el archivo EN EL SITIO)
 *   ./gradlew runSdkClient -PverifyPath=C:/ruta/al/proyecto   (compila y reporta residuos)
 * </pre>
 * Sin propiedades, escanea el directorio actual.
 */
public class SDKClient {

    public static void main(String[] args) {
        String tool = args.length > 0 ? args[0] : "scan";
        String value = args.length > 1 ? args[1] : ".";
        var transport = new WebFluxSseClientTransport(WebClient.builder().baseUrl("http://localhost:8081"));
        new SDKClient(transport).run(tool, value);
    }

    private final McpClientTransport transport;

    public SDKClient(McpClientTransport transport) {
        this.transport = transport;
    }

    public void run(String tool, String value) {
        // Timeout largo: verifyCompilation compila el proyecto destino y puede tardar minutos.
        var client = McpClient.sync(this.transport)
                .requestTimeout(Duration.ofMinutes(10))
                .build();
        client.initialize();

        McpSchema.ListToolsResult toolsList = client.listTools();
        System.out.println("Available Tools = " + toolsList);

        client.ping();

        McpSchema.CallToolRequest request = switch (tool) {
            case "scan" -> new McpSchema.CallToolRequest("scanSeleniumProject", Map.of("projectPath", value));
            case "convert" -> new McpSchema.CallToolRequest("convertPageObject", Map.of("filePath", value));
            case "verify" -> new McpSchema.CallToolRequest("verifyCompilation", Map.of("projectPath", value));
            default -> throw new IllegalArgumentException(
                    "Herramienta desconocida: " + tool + " (use scan | convert | verify)");
        };
        McpSchema.CallToolResult result = client.callTool(request);
        printResult(tool, result);
    }

    /** Imprime el texto del resultado des-escapado, legible en consola. */
    private void printResult(String tool, McpSchema.CallToolResult result) {
        System.out.println("=== " + tool + (Boolean.TRUE.equals(result.isError()) ? " (ERROR)" : "") + " ===");
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                // El servidor devuelve el String del tool serializado como JSON ("...\n...");
                // se des-escapa para mostrarlo con saltos de linea reales.
                String t = text.text();
                if (t != null && t.startsWith("\"") && t.endsWith("\"")) {
                    t = t.substring(1, t.length() - 1).replace("\\n", "\n").replace("\\\"", "\"");
                }
                System.out.println(t);
            } else {
                System.out.println(content);
            }
        }
    }
}

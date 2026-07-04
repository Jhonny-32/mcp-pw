package com.microsoft.mcp.sample.server.client;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;

import java.time.Duration;
import java.util.List;
import java.util.Scanner;

/**
 * Interactive LLM agent (03-GettingStarted/03-llm-client pattern) wired to the
 * Selenium -> Playwright migration tools of this MCP server.
 *
 * <p>Unlike {@link LangChain4jClient} (fixed calculator prompts), this client keeps a
 * conversation loop with chat memory, so multi-step migrations work naturally:
 * "escanea C:/mi/proyecto" -> "migra todos los page objects" -> "verifica la compilacion".
 *
 * <p>Requires the server running ({@code ./gradlew bootRun}) and the GITHUB_TOKEN env var.
 * Run with: {@code ./gradlew runMigrationAgent}
 */
public class MigrationAgentClient {

    public static void main(String[] args) throws Exception {
        String githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.isBlank()) {
            System.err.println("ERROR: la variable de entorno GITHUB_TOKEN no esta configurada.");
            System.err.println("Genera un token en GitHub (Settings > Developer settings > " +
                    "Personal access tokens > Fine-grained tokens) con el permiso \"Models\" y exportala:");
            System.err.println("  PowerShell:  $env:GITHUB_TOKEN = \"<tu-token>\"");
            System.err.println("  Git Bash:    export GITHUB_TOKEN=<tu-token>");
            System.exit(1);
        }

        ChatLanguageModel model = OpenAiOfficialChatModel.builder()
                .isGitHubModels(true)
                .apiKey(githubToken)
                .timeout(Duration.ofSeconds(120))
                .modelName("gpt-4.1-nano")
                .build();

        // Timeout largo: verifyCompilation compila el proyecto destino y puede tardar minutos.
        McpTransport transport = new HttpMcpTransport.Builder()
                .sseUrl("http://localhost:8081/sse")
                .timeout(Duration.ofMinutes(10))
                .logRequests(false)
                .logResponses(false)
                .build();

        McpClient mcpClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();

        ToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(List.of(mcpClient))
                .build();

        MigrationBot bot = AiServices.builder(MigrationBot.class)
                .chatLanguageModel(model)
                .toolProvider(toolProvider)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(30))
                .build();

        System.out.println("""
                ============================================================
                 Agente de migracion Selenium -> Playwright (MCP + LLM)
                 Ejemplos:
                   - escanea el proyecto C:/ruta/a/mi-proyecto-selenium
                   - migra el page object src/test/java/pages/LoginPage.java
                   - migra todos los page objects del inventario
                   - verifica la compilacion
                 Escribe 'salir' para terminar.
                ============================================================
                """);

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("migracion> ");
                if (!scanner.hasNextLine()) break;
                String prompt = scanner.nextLine().trim();
                if (prompt.isEmpty()) continue;
                if (prompt.equalsIgnoreCase("salir") || prompt.equalsIgnoreCase("exit")) break;
                try {
                    System.out.println(bot.chat(prompt));
                } catch (Exception e) {
                    System.err.println("Error en la conversacion: " + e.getMessage());
                }
            }
        } finally {
            mcpClient.close();
        }
        System.out.println("Hasta luego.");
    }
}
